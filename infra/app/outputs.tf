output "db_endpoint" {
  description = "Private address of the database (reachable only from the API task)"
  value       = aws_db_instance.main.address
}

output "ecr_repository_url" {
  description = "Where to push the API image"
  value       = aws_ecr_repository.api.repository_url
}

output "ecs_cluster_name" {
  description = "ECS cluster running the API"
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "ECS service of the API"
  value       = aws_ecs_service.api.name
}

output "log_group_name" {
  description = "CloudWatch log group with the API logs"
  value       = aws_cloudwatch_log_group.api.name
}

output "registration_code_parameter" {
  description = "SSM parameter holding the registration code (read with --with-decryption)"
  value       = aws_ssm_parameter.registration_code.name
}
