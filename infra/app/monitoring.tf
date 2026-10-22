# Alarms send an email through SNS. AWS sends a confirmation email first; alarms only arrive after
# the link in it was clicked. Everything here fits the CloudWatch free tier (10 alarms, 10 custom
# metrics, 3 dashboards).

resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

resource "aws_sns_topic_subscription" "alarm_email" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# The API writes one JSON line per request (RequestLoggingFilter) with a numeric "status" field.
# These filters turn those lines into metrics; there is no load balancer that would count them.
resource "aws_cloudwatch_log_metric_filter" "requests" {
  name           = "${local.name}-requests"
  log_group_name = aws_cloudwatch_log_group.api.name
  pattern        = "{ $.status > 0 }"

  metric_transformation {
    name      = "Requests"
    namespace = local.metrics_namespace
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_log_metric_filter" "server_errors" {
  name           = "${local.name}-server-errors"
  log_group_name = aws_cloudwatch_log_group.api.name
  pattern        = "{ $.status >= 500 }"

  metric_transformation {
    name      = "ServerErrors"
    namespace = local.metrics_namespace
    value     = "1"
    unit      = "Count"
  }
}

# Percentage of requests answered with 5xx in five minutes. A rate instead of a count, so a single
# failure during heavy use does not page anyone, but 1 of 3 requests failing does.
resource "aws_cloudwatch_metric_alarm" "server_error_rate" {
  alarm_name          = "${local.name}-api-5xx-rate"
  alarm_description   = "More than 5 % of API requests failed with a 5xx status in the last 5 minutes. Check the log group ${aws_cloudwatch_log_group.api.name}, filter { $.status >= 500 }, and follow the requestId."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 5
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  metric_query {
    id          = "rate"
    expression  = "IF(requests > 0, 100 * errors / requests, 0)"
    label       = "5xx rate (%)"
    return_data = true
  }

  metric_query {
    id = "errors"

    metric {
      namespace   = local.metrics_namespace
      metric_name = "ServerErrors"
      period      = 300
      stat        = "Sum"
    }
  }

  metric_query {
    id = "requests"

    metric {
      namespace   = local.metrics_namespace
      metric_name = "Requests"
      period      = 300
      stat        = "Sum"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "api_cpu" {
  alarm_name          = "${local.name}-api-cpu"
  alarm_description   = "API task CPU above 80 % for 15 minutes: 0.25 vCPU may be too small, or a request loops."
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    ClusterName = aws_ecs_cluster.main.name
    ServiceName = aws_ecs_service.api.name
  }
}

resource "aws_cloudwatch_metric_alarm" "api_memory" {
  alarm_name          = "${local.name}-api-memory"
  alarm_description   = "API task memory above 90 % for 15 minutes: the JVM may run out of memory soon."
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = 90
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    ClusterName = aws_ecs_cluster.main.name
    ServiceName = aws_ecs_service.api.name
  }
}

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = local.name

  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "Requests and 5xx errors (per minute)"
          region = var.aws_region
          view   = "timeSeries"
          stat   = "Sum"
          period = 60
          metrics = [
            [local.metrics_namespace, "Requests", { label = "requests" }],
            [local.metrics_namespace, "ServerErrors", { label = "5xx", color = "#d62728" }],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "API task CPU and memory (%)"
          region = var.aws_region
          view   = "timeSeries"
          stat   = "Average"
          period = 60
          yAxis  = { left = { min = 0, max = 100 } }
          metrics = [
            ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.api.name],
            ["AWS/ECS", "MemoryUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.api.name],
          ]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "Database"
          region = var.aws_region
          view   = "timeSeries"
          period = 300
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", aws_db_instance.main.identifier, { stat = "Average" }],
            ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", aws_db_instance.main.identifier, { stat = "Maximum", yAxis = "right" }],
          ]
        }
      },
      {
        type   = "alarm"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          title = "Alarms"
          alarms = [
            aws_cloudwatch_metric_alarm.server_error_rate.arn,
            aws_cloudwatch_metric_alarm.api_cpu.arn,
            aws_cloudwatch_metric_alarm.api_memory.arn,
          ]
        }
      },
      {
        type   = "log"
        x      = 0
        y      = 12
        width  = 24
        height = 6
        properties = {
          title  = "Latest failed or slow requests"
          region = var.aws_region
          view   = "table"
          query  = "SOURCE '${aws_cloudwatch_log_group.api.name}' | fields @timestamp, status, durationMs, method, path, requestId, groupId | filter status >= 500 or durationMs > 1000 | sort @timestamp desc | limit 20"
        }
      },
    ]
  })
}
