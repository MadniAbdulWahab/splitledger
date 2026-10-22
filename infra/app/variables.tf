variable "alarm_email" {
  description = "Email address for CloudWatch alarms (set it in terraform.tfvars, which is git-ignored)"
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alarm_email))
    error_message = "alarm_email must be an email address."
  }
}

variable "api_allowed_cidrs" {
  description = "IPv4 ranges allowed to call the API on port 8080; narrow it to your own IP (x.x.x.x/32) for a private demo"
  type        = list(string)
  default     = ["0.0.0.0/0"]

  validation {
    condition     = alltrue([for cidr in var.api_allowed_cidrs : can(cidrnetmask(cidr))])
    error_message = "Every entry must be an IPv4 CIDR such as 203.0.113.7/32."
  }
}

variable "api_desired_count" {
  description = "Number of running API tasks; 0 until an image has been pushed to ECR"
  type        = number
  default     = 0

  validation {
    condition     = var.api_desired_count >= 0 && var.api_desired_count <= 2
    error_message = "api_desired_count must be between 0 and 2."
  }

  validation {
    condition     = var.api_desired_count == 0 || var.api_image_tag != ""
    error_message = "Set api_image_tag (an image pushed to ECR) before starting tasks."
  }
}

variable "api_image_tag" {
  description = "Tag of the API image in ECR, e.g. the git commit SHA"
  type        = string
  default     = ""
}

variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "eu-central-1"
}

variable "db_engine_version" {
  description = "PostgreSQL major version on RDS (same as in tests and docker-compose)"
  type        = string
  default     = "16"
}

variable "db_instance_class" {
  description = "RDS instance size; db.t4g.micro is the smallest"
  type        = string
  default     = "db.t4g.micro"
}

variable "github_repository" {
  description = "GitHub repository whose deploy workflow may assume the deploy role, as it appears in GitHub's OIDC token: owner@ownerId/name@repositoryId"
  type        = string
  default     = "MadniAbdulWahab@105889425/splitledger@1427604325"
}

variable "log_retention_days" {
  description = "How long CloudWatch keeps the API logs"
  type        = number
  default     = 7
}

variable "project_name" {
  description = "Prefix for resource names and the Project tag"
  type        = string
  default     = "splitledger"
}

variable "secrets_version" {
  description = "Increase by one to generate and store new database passwords and a new registration code"
  type        = number
  default     = 1
}

variable "vpc_cidr" {
  description = "Address range of the VPC"
  type        = string
  default     = "10.20.0.0/16"
}
