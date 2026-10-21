variable "alert_email" {
  description = "Email address that receives the budget alerts (set it in terraform.tfvars, which is git-ignored)"
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alert_email))
    error_message = "alert_email must be an email address."
  }
}

variable "aws_region" {
  description = "Region for the provider; AWS Budgets itself is global"
  type        = string
  default     = "eu-central-1"
}

variable "monthly_limit_usd" {
  description = "Monthly budget in USD (AWS Budgets bills and compares in USD); 5 USD is about 4.30 EUR"
  type        = number
  default     = 5

  validation {
    condition     = var.monthly_limit_usd > 0
    error_message = "monthly_limit_usd must be positive."
  }
}

variable "project_name" {
  description = "Name used for the budget and in resource tags"
  type        = string
  default     = "splitledger"
}
