# Cost guard for the whole AWS account. Kept apart from the application stack (infra/app) so
# `terraform destroy` there never removes the alert. AWS Budgets is free; it only sends emails and
# does not stop or block anything.
resource "aws_budgets_budget" "monthly" {
  name         = "${var.project_name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # On the AWS Free plan the credits pay the bill, so the cost after credits stays at 0 and an
  # alert on it would never fire. Measuring usage before credits warns about real consumption.
  cost_types {
    include_credit = false
    include_refund = false
  }

  # Early warning: half of the budget is used.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  # The budget is used up.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  # AWS predicts the month will end above the budget: usually means something was left running.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
