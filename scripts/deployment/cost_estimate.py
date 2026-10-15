"""Illustrative USD monthly arithmetic only; no AWS API calls or deployment region selection.

Reference prices checked 2026-10-15; sources/uncertainties in docs/M17_DEPLOYMENT.md.
Excludes taxes/credits/support. Workload: low traffic, 730h, five GB originals, <=100 uploads.
"""
import json
HOURS = 730
FARGATE_CPU = 0.000011244 * 3600
FARGATE_MEMORY = 0.000001235 * 3600
core = {
    "application_t3_medium": .0418 * HOURS,
    "rds_t4g_small_single_az_20gb": .032 * HOURS + 20 * .115,
    "application_40gb_gp3": 40 * .08,
    "application_ipv4": .005 * HOURS,
    "nat_and_load_balancer": 0,
    "s3_5gb_1000_put_10000_get": 5 * .023 + 1 * .005 + 10 * .0004,
    "sqs_900000_requests_no_free_tier": .9 * .40,
    "logs_1gb_10_metrics_3_alarms": .5 + .03 + 10 * .3 + 3 * .1,
    "three_kms_keys_30000_requests": 3 + 3 * .03,
    "four_secrets_10000_requests": 4 * .4 + .05,
    "ecr_3gb": 3 * .1,
    "backup_and_state_allowance": 3,
    "dns_zone_50000_queries": .5 + .05 * .4,
    "internet_5gb_no_free_allowance": 5 * .09,
}
resilient = {
    "future_fargate_frontend_api_two_workers": HOURS * (FARGATE_CPU * (1 + .25 + .5 * 2) + FARGATE_MEMORY * (2 + .5 + 1 * 2)),
    "rds_t4g_medium_multi_az_20gb": .129 * HOURS + 20 * .23,
    "two_nats_two_ipv4_five_gb": 2 * .045 * HOURS + 2 * .005 * HOURS + 5 * .045,
    "alb_two_ipv4_point_one_lcu": .0225 * HOURS + 2 * .005 * HOURS + .1 * .008 * HOURS,
    "separate_cpu_model_t3_xlarge_80gb": .1670 * HOURS + 80 * .08,
    "sandbox_host_reserve_t3_small_20gb": .0209 * HOURS + 20 * .08,
    "s3_sqs_ecr_secrets_kms_dns_egress_backup_allowance": 15,
    "logs_two_gb_20_metrics_3_alarms": 2 * .5 + .06 + 20 * .3 + .3,
}
if __name__ == "__main__":
    print(json.dumps({"referenceOnly": "US East N. Virginia, not deployment selection", "pricingChecked": "2026-10-15", "core": {k: round(v, 2) for k, v in core.items()},
        "coreSubtotal": round(sum(core.values()), 2), "keycloakAdditionalBudget": [20, 60],
        "single_host_ai_upgradeAdditional": round((.1670 - .0418) * HOURS + 40 * .08, 2),
        "resilientProposal": {k: round(v, 2) for k, v in resilient.items()}, "resilientSubtotal": round(sum(resilient.values()), 2),
        "resilientKeycloakAdditionalBudget": [40, 100], "exclusions": ["tax/support/domain registration", "CPU burst-credit surplus", "unmeasured cross-AZ transfer", "new private endpoints/proxies", "cloud runtime adapter work"]}, indent=2))
