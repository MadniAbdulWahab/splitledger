# Two roles, as ECS separates them:
#   execution role: used by ECS itself to start the task (pull the image, write logs, read secrets)
#   task role:      used by the application code; it has no AWS permissions yet (SQS/S3 on day 6)

data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    # Only ECS in this account may use the roles (protects against the "confused deputy" problem).
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "api_execution" {
  name               = "${local.name}-api-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# Instead of the broad managed AmazonECSTaskExecutionRolePolicy (any repository, any log group),
# exactly this repository, this log group and these three parameters.
data "aws_iam_policy_document" "api_execution" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this action cannot be limited to a repository
  }

  statement {
    sid = "PullApiImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.api.arn]
  }

  statement {
    sid       = "WriteApiLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.api.arn}:*"]
  }

  statement {
    sid     = "ReadApiSecrets"
    actions = ["ssm:GetParameters"]
    resources = [
      aws_ssm_parameter.db_app_password.arn,
      aws_ssm_parameter.db_owner_password.arn,
      aws_ssm_parameter.registration_code.arn,
    ]
  }
}

resource "aws_iam_role_policy" "api_execution" {
  name   = "pull-log-read-secrets"
  role   = aws_iam_role.api_execution.id
  policy = data.aws_iam_policy_document.api_execution.json
}

resource "aws_iam_role" "api_task" {
  name               = "${local.name}-api-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}
