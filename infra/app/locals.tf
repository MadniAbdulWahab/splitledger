locals {
  name = var.project_name

  # Two availability zones: RDS requires a subnet group spanning at least two.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  # 10.20.0.0/24 and 10.20.1.0/24 public, 10.20.10.0/24 and 10.20.11.0/24 private.
  public_subnets  = { for i, az in local.azs : az => cidrsubnet(var.vpc_cidr, 8, i) }
  private_subnets = { for i, az in local.azs : az => cidrsubnet(var.vpc_cidr, 8, i + 10) }

  # The least-privilege database role created by the V7 migration; the application logs in with it.
  db_app_user = "splitledger_app"
  db_name     = "splitledger"
  db_owner    = "splitledger"
}
