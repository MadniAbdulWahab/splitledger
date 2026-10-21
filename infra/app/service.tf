resource "aws_ecs_cluster" "main" {
  name = local.name

  # Container Insights costs extra; the log group and (day 5) alarms are enough here.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256" # 0.25 vCPU, the smallest Fargate size
  memory                   = "512" # MB; the image sizes the heap to 75 % of this
  execution_role_arn       = aws_iam_role.api_execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "api"
    image     = "${aws_ecr_repository.api.repository_url}:${var.api_image_tag != "" ? var.api_image_tag : "not-pushed-yet"}"
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    # Flyway migrates as the RDS master user; the application itself connects as the
    # least-privilege role (see V7__application_role.sql and AppRolePasswordCallback).
    environment = [
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${local.db_name}" },
      { name = "DB_USER", value = local.db_app_user },
      { name = "SPRING_FLYWAY_USER", value = local.db_owner },
    ]

    # ECS reads these from SSM when the task starts; they never appear in the task definition.
    secrets = [
      { name = "DB_PASSWORD", valueFrom = aws_ssm_parameter.db_app_password.arn },
      { name = "SPRING_FLYWAY_PASSWORD", valueFrom = aws_ssm_parameter.db_owner_password.arn },
      { name = "SPLITLEDGER_REGISTRATION_CODE", valueFrom = aws_ssm_parameter.registration_code.arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.api.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "api"
      }
    }

    # Restarts the container if Spring Boot stops answering. Generous start period: 0.25 vCPU
    # needs a while for the JVM start and the Flyway migrations.
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health/liveness || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 120
    }
  }])
}

resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"
  propagate_tags  = "SERVICE"

  # One task at a time: stop the old one before starting the new one. A short gap during a deploy
  # is fine for a demo, and two versions never run their migrations at the same moment.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  # A task that keeps failing its health check rolls the service back to the last working version.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = [for subnet in aws_subnet.public : subnet.id]
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true
  }
}
