variable "region" {
  type        = string
  description = "Explicitly approved AWS region   no deployment default."
  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-[0-9]+$", var.region))
    error_message = "Supply an approved standard AWS region. Other partitions are not validated."
  }
}
variable "account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "Supply the approved account ID."
  }
}
variable "environment" {
  type = string
  validation {
    condition     = contains(["demo", "production"], var.environment) && (var.environment != "production" || (var.multi_az && var.nat_gateways == 2 && var.backup_retention_days >= 14))
    error_message = "Use separate demo/production state; production requires Multi-AZ, two NAT gateways and 14+ backup days."
  }
}
variable "availability_zones" {
  type = list(string)
  validation {
    condition     = length(var.availability_zones) == 2 && length(toset(var.availability_zones)) == 2 && alltrue([for az in var.availability_zones : startswith(az, var.region)])
    error_message = "Two distinct AZs in the approved region are required."
  }
}
variable "vpc_cidr" {
  type    = string
  default = "10.72.0.0/16"
}
variable "nat_gateways" {
  type        = number
  default     = 0
  description = "0 for no private internet egress (demo host proposal)   2 for per-AZ resilient egress."
  validation {
    condition     = contains([0, 2], var.nat_gateways)
    error_message = "Use zero or two NAT gateways   avoid a hidden cross-AZ single-NAT dependency."
  }
}
variable "postgres_version" {
  type        = string
  description = "Approved available RDS PostgreSQL 17 minor with vector support   confirm in target region before planning."
  validation {
    condition     = can(regex("^17\\.[0-9]+$", var.postgres_version))
    error_message = "Only explicitly selected PostgreSQL 17 minors are supported."
  }
}
variable "db_instance_class" {
  type    = string
  default = "db.t4g.small"
}
variable "multi_az" {
  type    = bool
  default = false
}
variable "backup_retention_days" {
  type    = number
  default = 7
  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "Keep seven to thirty-five days of PITR backups."
  }
}
variable "log_retention_days" {
  type    = number
  default = 14
  validation {
    condition     = contains([14, 30, 60, 90], var.log_retention_days)
    error_message = "Use a bounded CloudWatch retention."
  }
}
variable "alarm_topic_arn" {
  type        = string
  default     = null
  description = "Optional existing reviewed SNS destination   no subscriptions or messages are created here."
}

variable "https_egress_cidrs" {
  type        = list(string)
  default     = []
  description = "Reviewed IdP/ECR/SQS/proxy IPv4 ranges. Empty fails closed; no arbitrary internet egress."
  validation {
    condition     = alltrue([for cidr in var.https_egress_cidrs : can(cidrhost(cidr, 0)) && !strcontains(cidr, ":") && cidr != "0.0.0.0/0"])
    error_message = "Explicit bounded egress CIDRs required; no catch-all internet rule."
  }
}
