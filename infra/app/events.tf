# Day 6: ledger events from the API's outbox go through an SQS FIFO queue to the statement worker
# (Lambda, Kotlin), which keeps one monthly summary per group as JSON in S3.
#
#   API task --SendMessage--> ledger-events.fifo --event source mapping--> Lambda --> S3
#                                    |
#                                    +-- after 3 failed attempts --> ledger-events-dlq.fifo --> alarm
#
# Build the jar first: (cd statement-worker && ./mvnw -B package)

resource "aws_sqs_queue" "ledger_events_dlq" {
  name                      = "${local.name}-ledger-events-dlq.fifo"
  fifo_queue                = true
  message_retention_seconds = 1209600 # 14 days, the maximum: time to inspect and redrive
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "ledger_events" {
  name                    = "${local.name}-ledger-events.fifo"
  fifo_queue              = true
  sqs_managed_sse_enabled = true

  # AWS recommends at least six times the function timeout, so a message is not handed out again
  # while a slow invocation is still working on it.
  visibility_timeout_seconds = 6 * local.worker_timeout_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.ledger_events_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "ledger_events_dlq" {
  queue_url = aws_sqs_queue.ledger_events_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.ledger_events.arn]
  })
}

resource "aws_s3_bucket" "statements" {
  # Bucket names are global; the account id keeps this one unique.
  bucket = "${local.name}-statements-${data.aws_caller_identity.current.account_id}"
  # Statements are derived data that can be rebuilt from the ledger; destroy may delete them.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "statements" {
  bucket = aws_s3_bucket.statements.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "statements" {
  bucket = aws_s3_bucket.statements.id

  rule {
    object_ownership = "BucketOwnerEnforced" # no ACLs at all; access only through IAM
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "statements" {
  bucket = aws_s3_bucket.statements.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_cloudwatch_log_group" "statement_worker" {
  name              = "/aws/lambda/${local.name}-statement-worker"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "statement_worker" {
  name               = "${local.name}-statement-worker"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "statement_worker" {
  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.statement_worker.arn}:*"]
  }

  statement {
    sid = "ConsumeLedgerEvents"
    actions = [
      "sqs:ChangeMessageVisibility",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:ReceiveMessage",
    ]
    resources = [aws_sqs_queue.ledger_events.arn]
  }

  statement {
    sid       = "ReadWriteStatements"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.statements.arn}/statements/*"]
  }

  # Without ListBucket, reading a statement that does not exist yet returns 403 instead of 404,
  # and the worker could not tell "first event of the month" from a permission problem.
  statement {
    sid       = "SeeMissingStatements"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.statements.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["statements/*"]
    }
  }
}

resource "aws_iam_role_policy" "statement_worker" {
  name   = "consume-events-write-statements"
  role   = aws_iam_role.statement_worker.id
  policy = data.aws_iam_policy_document.statement_worker.json
}

resource "aws_lambda_function" "statement_worker" {
  function_name    = "${local.name}-statement-worker"
  description      = "Kotlin: ledger events from SQS to monthly group statements in S3"
  role             = aws_iam_role.statement_worker.arn
  runtime          = "java21"
  handler          = "dev.splitledger.statements.StatementHandler::handleRequest"
  filename         = local.worker_jar
  source_code_hash = filebase64sha256(local.worker_jar)
  architectures    = ["arm64"] # Graviton: about 20 % cheaper per GB-second; the jar is plain JVM bytecode
  memory_size      = 512
  timeout          = local.worker_timeout_seconds

  environment {
    variables = {
      STATEMENT_BUCKET = aws_s3_bucket.statements.id
      # Shorter cold starts for a function that runs briefly: skip the optimizing JIT tier.
      JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    }
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.statement_worker.name
  }

  depends_on = [aws_iam_role_policy.statement_worker]
}

resource "aws_lambda_event_source_mapping" "statement_worker" {
  event_source_arn = aws_sqs_queue.ledger_events.arn
  function_name    = aws_lambda_function.statement_worker.arn
  batch_size       = 10
  # Only the failed messages (and, on FIFO, everything after them) go back to the queue.
  function_response_types = ["ReportBatchItemFailures"]
}

# The API task may only send to this queue.
data "aws_iam_policy_document" "api_task" {
  statement {
    sid       = "PublishLedgerEvents"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.ledger_events.arn]
  }
}

resource "aws_iam_role_policy" "api_task" {
  name   = "publish-ledger-events"
  role   = aws_iam_role.api_task.id
  policy = data.aws_iam_policy_document.api_task.json
}

# A message in the DLQ means an event failed three times and the statement for its month is now
# incomplete. First check: the worker's log group, then the message itself in the DLQ console;
# after a fix, "Start DLQ redrive" sends it back.
resource "aws_cloudwatch_metric_alarm" "ledger_events_dlq" {
  alarm_name          = "${local.name}-ledger-events-dlq"
  alarm_description   = "A ledger event failed 3 times and is in ${aws_sqs_queue.ledger_events_dlq.name}. Check ${aws_cloudwatch_log_group.statement_worker.name}, fix, then redrive."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    QueueName = aws_sqs_queue.ledger_events_dlq.name
  }
}
