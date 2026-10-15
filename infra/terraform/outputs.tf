output "vpc_id" { value = module.network.vpc_id }
output "private_subnet_ids" { value = module.network.private_subnet_ids }
output "database_address" { value = module.data.database_address }
output "document_bucket" { value = module.data.bucket_name }
output "document_kms_key_arn" { value = module.data.documents_kms_arn }
output "ingestion_queue" { value = module.data.queue_name }
output "repository_urls" { value = module.operations.repository_urls }
output "runtime_role_arns" { value = module.operations.runtime_role_arns }
# No password, secret value, connection URL, token or state contents are output.
