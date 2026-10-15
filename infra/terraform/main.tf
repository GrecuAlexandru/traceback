locals { name = "traceback-${var.environment}" }
module "network" {
  source             = "./modules/network"
  name               = local.name
  cidr               = var.vpc_cidr
  region             = var.region
  availability_zones = var.availability_zones
  nat_gateways       = var.nat_gateways
  https_egress_cidrs = var.https_egress_cidrs
}
module "data" {
  source                      = "./modules/data"
  name                        = local.name
  account_id                  = var.account_id
  region                      = var.region
  vpc_id                      = module.network.vpc_id
  private_subnet_ids          = module.network.private_subnet_ids
  app_security_group_id       = module.network.api_security_group_id
  worker_security_group_id    = module.network.worker_security_group_id
  migration_security_group_id = module.network.migration_security_group_id
  postgres_version            = var.postgres_version
  instance_class              = var.db_instance_class
  multi_az                    = var.multi_az
  backup_retention            = var.backup_retention_days
  log_retention               = var.log_retention_days
}
module "operations" {
  source            = "./modules/operations"
  name              = local.name
  account_id        = var.account_id
  region            = var.region
  bucket_arn        = module.data.bucket_arn
  documents_kms_arn = module.data.documents_kms_arn
  queue_arn         = module.data.queue_arn
  queue_name        = module.data.queue_name
  dlq_name          = module.data.dlq_name
  db_identifier     = module.data.db_identifier
  log_retention     = var.log_retention_days
  alarm_topic_arn   = var.alarm_topic_arn
}
# No compute, DNS, certificates, Keycloak, inference or extraction launcher is silently
# created. See docs/M17_DEPLOYMENT.md for prerequisites and production blockers.
