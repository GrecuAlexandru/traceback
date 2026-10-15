variable "name" { type = string }
variable "account_id" { type = string }
variable "region" { type = string }
variable "bucket_arn" { type = string }
variable "documents_kms_arn" { type = string }
variable "queue_arn" { type = string }
variable "queue_name" { type = string }
variable "dlq_name" { type = string }
variable "db_identifier" { type = string }
variable "log_retention" { type = number }
variable "alarm_topic_arn" {
  type    = string
  default = null
}
locals {
  services = toset(["api", "worker", "frontend", "pdf"])
  ecs_trust = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ecs-tasks.amazonaws.com" },
    Condition = { StringEquals = { "aws:SourceAccount" = var.account_id },
    ArnLike = { "aws:SourceArn" = "arn:aws:ecs:${var.region}:${var.account_id}:*" } }
  }] })
}
resource "aws_ecr_repository" "this" {
  for_each             = local.services
  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  lifecycle { prevent_destroy = true }
}
# Deliberately no lifecycle deletion of release images: reviewed rollback retention first.
resource "aws_cloudwatch_log_group" "this" {
  for_each          = local.services
  name              = "/traceback/${var.name}/${each.key}"
  retention_in_days = var.log_retention
  kms_key_id        = aws_kms_key.logs.arn
}
resource "aws_kms_key" "logs" {
  description             = "${var.name} bounded CloudWatch logs"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Sid = "AccountAdministration", Effect = "Allow", Principal = { AWS = "arn:aws:iam::${var.account_id}:root" }, Action = "kms:*", Resource = "*" },
    { Sid    = "RegionalLogs", Effect = "Allow", Principal = { Service = "logs.${var.region}.amazonaws.com" },
      Action = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"], Resource = "*",
    Condition = { ArnLike = { "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.region}:${var.account_id}:log-group:/traceback/${var.name}/*" } } }
  ] })
  lifecycle { prevent_destroy = true }
}
resource "aws_iam_role" "runtime" {
  for_each           = toset(["api", "worker", "frontend"])
  name               = "${var.name}-${each.key}"
  assume_role_policy = local.ecs_trust
}
resource "aws_iam_role_policy" "runtime" {
  for_each = toset(["api", "worker"])
  role     = aws_iam_role.runtime[each.key].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = each.key == "api" ? ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"] : ["s3:GetObject"], Resource = "${var.bucket_arn}/documents/*" },
    { Effect = "Allow", Action = ["s3:ListBucket"], Resource = var.bucket_arn },
    { Effect = "Allow", Action = each.key == "api" ? ["kms:Decrypt", "kms:GenerateDataKey"] : ["kms:Decrypt"], Resource = var.documents_kms_arn },
    { Effect = "Allow", Action = concat(["sqs:GetQueueUrl", "sqs:GetQueueAttributes", "sqs:SendMessage"], each.key == "worker" ? ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"] : []), Resource = var.queue_arn }
  ] })
}
# Frontend has no AWS data permissions. Secret fetch belongs to a separately scoped
# execution role with reviewed existing secret ARNs. No secret values enter Terraform.
resource "aws_ecs_cluster" "future" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}
resource "aws_cloudwatch_metric_alarm" "dlq" {
  alarm_name          = "${var.name}-dlq-nonempty"
  alarm_description   = "Inspect durable failure code   repair before scoped retry/redrive. Never log message bodies."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.dlq_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
resource "aws_cloudwatch_metric_alarm" "queue_age" {
  alarm_name          = "${var.name}-queue-age"
  alarm_description   = "Provisional 15 minute processing SLO   inspect workers, model and durable backlog. Tune after measured load."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = var.queue_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 900
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
resource "aws_cloudwatch_metric_alarm" "database_storage" {
  alarm_name          = "${var.name}-database-free-storage"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = var.db_identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 5368709120
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
output "repository_urls" { value = { for name, repo in aws_ecr_repository.this : name => repo.repository_url } }
output "runtime_role_arns" { value = { for name, role in aws_iam_role.runtime : name => role.arn } }
