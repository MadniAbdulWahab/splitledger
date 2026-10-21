resource "aws_db_subnet_group" "main" {
  name        = "${local.name}-db"
  description = "Private subnets for the SplitLedger database"
  subnet_ids  = [for subnet in aws_subnet.private : subnet.id]
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name}-db"
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = local.db_name
  username = local.db_owner
  # Same ephemeral value that is written to SSM in this run; never stored in the state.
  password_wo         = ephemeral.random_password.db_owner.result
  password_wo_version = var.secrets_version

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  # Demo settings: one day of automated backups, no final snapshot and no deletion protection,
  # so `terraform destroy` removes everything without leaving billable snapshots behind.
  backup_retention_period    = 1
  skip_final_snapshot        = true
  deletion_protection        = false
  delete_automated_backups   = true
  auto_minor_version_upgrade = true
  apply_immediately          = true
}
