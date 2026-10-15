# Traceback AWS foundations

This code has **not been applied to AWS**. It is not a complete running deployment.
Read [the M17 deployment report](../../docs/M17_DEPLOYMENT.md) and
[ADR-0011](../../docs/DECISIONS/0011-production-packaging-and-explicit-deployment-blockers.md).

Three modules cover network, durable data and operations. Compute/ingress/IdP/model/secret
injection and PDF runtime validation are deliberate deployment prerequisites. The examples
have no region/account/AZ default; never copy test fixture values into a real deployment.

Safe local checks (after installing the pinned tools):

```sh
terraform fmt -check -recursive
terraform init -backend=false -input=false -lockfile=readonly
terraform validate
terraform test
trivy config --tf-vars tests/scan.tfvars.example --severity HIGH,CRITICAL --exit-code 1 .
```

Tests use a mocked provider; no account credentials or AWS resources are needed. A live
plan/backend/bootstrap or mutation requires separately approved account, region and cost.
State, actual tfvars and plans are ignored and must never be attached as public artifacts.
