#!/bin/sh
set -eu
# Local emulator only; this script cannot target a real AWS endpoint.
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
if ! awslocal s3api head-bucket --bucket traceback-documents-local >/dev/null 2>&1; then
  awslocal s3api create-bucket --bucket traceback-documents-local >/dev/null
fi
awslocal s3api put-public-access-block --bucket traceback-documents-local \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
awslocal s3api put-bucket-encryption --bucket traceback-documents-local \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
dlq_url=$(awslocal sqs create-queue --queue-name traceback-ingestion-dlq-local \
  --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name traceback-ingestion-local --attributes \
  "{\"VisibilityTimeout\":\"150\",\"MessageRetentionPeriod\":\"345600\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"8\\\"}\"}" >/dev/null
