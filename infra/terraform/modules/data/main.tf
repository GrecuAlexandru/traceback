variable "name" { type = string }
variable "account_id" { type = string }
variable "region" { type = string }
variable "vpc_id" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "app_security_group_id" { type = string }
variable "worker_security_group_id" { type = string }
variable "migration_security_group_id" { type = string }
variable "postgres_version" { type = string }
variable "instance_class" { type = string }
variable "multi_az" { type = bool }
variable "backup_retention" { type = number }
variable "log_retention" { type = number }
resource "aws_kms_key" "database" {
  description             = "${var.name} RDS and managed master secret encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Principal = { AWS = "arn:aws:iam::${var.account_id}:root" }, Action = "kms:*", Resource = "*" },
    { Effect = "Allow", Principal = { Service = "logs.${var.region}.amazonaws.com" },
      Action = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"], Resource = "*",
    Condition = { ArnEquals = { "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.region}:${var.account_id}:log-group:/aws/rds/instance/${var.name}/postgresql" } } }
  ] })
  lifecycle { prevent_destroy = true }
}
resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.private_subnet_ids
}
resource "aws_security_group" "database" {
  name_prefix = "${var.name}-db-"
  description = "PostgreSQL only from app, worker and approved migration runner"
  vpc_id      = var.vpc_id
}
locals { clients = { api = var.app_security_group_id, worker = var.worker_security_group_id, migration = var.migration_security_group_id } }
resource "aws_vpc_security_group_ingress_rule" "database" {
  for_each                     = local.clients
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_vpc_security_group_egress_rule" "database" {
  for_each                     = local.clients
  security_group_id            = each.value
  referenced_security_group_id = aws_security_group.database.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name}-"
  family      = "postgres17"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  # Do not log SQL text/bind values: document content and identities are private.
  parameter {
    name  = "log_statement"
    value = "none"
  }
  parameter {
    name  = "log_min_error_statement"
    value = "panic"
  }
  parameter {
    name  = "log_error_verbosity"
    value = "terse"
  }
}
resource "aws_db_instance" "this" {
  identifier                      = var.name
  engine                          = "postgres"
  engine_version                  = var.postgres_version
  instance_class                  = var.instance_class
  db_name                         = "traceback"
  username                        = "traceback_migration_owner"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = aws_kms_key.database.arn
  allocated_storage               = 20
  max_allocated_storage           = 100
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.database.arn
  multi_az                        = var.multi_az
  publicly_accessible             = false
  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  parameter_group_name            = aws_db_parameter_group.this.name
  backup_retention_period         = var.backup_retention
  backup_window                   = "02:00-03:00"
  maintenance_window              = "sun:03:00-sun:04:00"
  copy_tags_to_snapshot           = true
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.name}-final-reviewed"
  auto_minor_version_upgrade      = true
  allow_major_version_upgrade     = false
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["postgresql"]
  depends_on                      = [aws_cloudwatch_log_group.database]
  lifecycle { prevent_destroy = true }
}
resource "aws_s3_bucket" "documents" {
  bucket        = "${var.name}-${var.account_id}-${var.region}-documents"
  force_destroy = false
  lifecycle { prevent_destroy = true }
}
resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_ownership_controls" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule { object_ownership = "BucketOwnerEnforced" }
}
resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id
  versioning_configuration { status = "Enabled" }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.documents.arn
    }
  }
}
resource "aws_s3_bucket_policy" "documents" {
  bucket = aws_s3_bucket.documents.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Sid       = "DenyInsecureTransport", Effect = "Deny", Principal = "*", Action = "s3:*",
    Resource  = [aws_s3_bucket.documents.arn, "${aws_s3_bucket.documents.arn}/*"],
    Condition = { Bool = { "aws:SecureTransport" = "false" } }
  }] })
}
resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name}-ingestion-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
  lifecycle { prevent_destroy = true }
}
resource "aws_sqs_queue" "ingestion" {
  name                       = "${var.name}-ingestion"
  message_retention_seconds  = 345600
  visibility_timeout_seconds = 150
  receive_wait_time_seconds  = 5
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dlq.arn, maxReceiveCount = 8 })
}
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  queue_url            = aws_sqs_queue.dlq.url
  redrive_allow_policy = jsonencode({ redrivePermission = "byQueue", sourceQueueArns = [aws_sqs_queue.ingestion.arn] })
}
resource "aws_sqs_queue_policy" "tls" {
  for_each  = { ingestion = aws_sqs_queue.ingestion, dlq = aws_sqs_queue.dlq }
  queue_url = each.value.url
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect    = "Deny", Principal = "*", Action = "sqs:*", Resource = each.value.arn,
    Condition = { Bool = { "aws:SecureTransport" = "false" } }
  }] })
}
output "database_address" { value = aws_db_instance.this.address }
output "db_identifier" { value = aws_db_instance.this.identifier }
output "bucket_name" { value = aws_s3_bucket.documents.bucket }
output "bucket_arn" { value = aws_s3_bucket.documents.arn }
output "queue_arn" { value = aws_sqs_queue.ingestion.arn }
output "queue_name" { value = aws_sqs_queue.ingestion.name }
output "dlq_name" { value = aws_sqs_queue.dlq.name }

resource "aws_kms_key" "documents" {
  description             = "${var.name} private immutable documents"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  lifecycle { prevent_destroy = true }
}
output "documents_kms_arn" { value = aws_kms_key.documents.arn }

resource "aws_cloudwatch_log_group" "database" {
  name              = "/aws/rds/instance/${var.name}/postgresql"
  retention_in_days = var.log_retention
  kms_key_id        = aws_kms_key.database.arn
}
