# Provider is mocked: these plans cannot contact/provision AWS. Regions/accounts are fixtures.
mock_provider "aws" {}
variables {
  region             = "us-east-1"
  account_id         = "123456789012"
  availability_zones = ["us-east-1a", "us-east-1b"]
  environment        = "demo"
  postgres_version   = "17.11"
}
run "minimal_foundations" {
  command = plan
  assert {
    condition     = module.data.bucket_name == "traceback-demo-123456789012-us-east-1-documents"
    error_message = "Resource names must isolate environment/account/region."
  }
}
run "resilient_foundations" {
  command = plan
  variables {
    environment           = "production"
    multi_az              = true
    nat_gateways          = 2
    backup_retention_days = 14
  }
}
run "reject_unsafe_production" {
  command = plan
  variables { environment = "production" }
  expect_failures = [var.environment]
}
