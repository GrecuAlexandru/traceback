terraform {
  required_version = "= 1.16.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
  # Backend resources are a separate, approved bootstrap prerequisite. No local state in Git.
  backend "s3" {}
}
provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
  default_tags {
    tags = { Project = "Traceback", Environment = var.environment, ManagedBy = "Terraform", CostCenter = "portfolio" }
  }
}
