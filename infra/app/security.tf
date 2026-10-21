# Security groups are stateful: replies to allowed traffic are allowed automatically, so each rule
# only describes who may start a connection. DNS to the VPC resolver is never filtered by them.

resource "aws_security_group" "api" {
  name        = "${local.name}-api"
  description = "SplitLedger API task"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name}-api"
  }
}

resource "aws_vpc_security_group_ingress_rule" "api_http" {
  for_each = toset(var.api_allowed_cidrs)

  security_group_id = aws_security_group.api.id
  description       = "API requests"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 8080
  to_port           = 8080
}

# HTTPS out: pulling the image from ECR, sending logs to CloudWatch, reading SSM parameters.
resource "aws_vpc_security_group_egress_rule" "api_https" {
  security_group_id = aws_security_group.api.id
  description       = "AWS APIs (ECR, CloudWatch Logs, SSM)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "api_postgres" {
  security_group_id            = aws_security_group.api.id
  description                  = "Database"
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "SplitLedger PostgreSQL, reachable only from the API task"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name}-db"
  }
}

# The only way into the database: from a task that carries the API security group.
resource "aws_vpc_security_group_ingress_rule" "db_from_api" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from the API task"
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
