"""Snapshot and restore ONLY the owned M17 synthetic stack into a NEW DB and bucket.

Never replaces an existing DB, deletes source objects/volumes, prints content or calls AWS.
Artifacts contain synthetic data and stay in a private new directory outside the checkout.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

PG = "traceback-m17-postgres-1"
AWS = "traceback-m17-localstack-1"
APP = "traceback-m17-api-1"
WORKER = "traceback-m17-worker-1"


def command(*args, data=None):
    return subprocess.run(list(args), input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def sql(database, query):
    return command("docker", "exec", PG, "psql", "-v", "ON_ERROR_STOP=1", "-U", "traceback_m17", "-d", database, "-Atc", query)


INVENTORY = """SELECT coalesce(json_agg(row_to_json(r) ORDER BY r.id),'[]'::json) FROM
 (SELECT id,organization_id,document_id,storage_provider,storage_key,checksum,file_size
  FROM document_revisions WHERE status='AVAILABLE') r"""


def exercise(directory):
    directory = directory.resolve()
    checkout = Path(__file__).resolve().parents[2]
    if directory.is_relative_to(checkout):
        raise ValueError("Backup artifacts must be outside the checkout")
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    for container in [PG, AWS, APP, WORKER]:
        label = command("docker", "inspect", "--format", '{{ index .Config.Labels "com.docker.compose.project" }}', container).decode().strip()
        if label != "traceback-m17":
            raise ValueError("Only the owned M17 disposable project is allowed")
    # Quiesce writes for a coordinated metadata/object checkpoint. Original DB stays intact.
    command("docker", "stop", "--time", "35", WORKER, APP)
    try:
        dump = command("docker", "exec", PG, "pg_dump", "-U", "traceback_m17", "-d", "traceback_m17", "-Fc")
        with open(directory / "database.dump", "xb", opener=lambda p, f: os.open(p, f, 0o600)) as out:
            out.write(dump)
        inventory = json.loads(sql("traceback_m17", INVENTORY))
        if len(inventory) > 100:
            raise ValueError("Synthetic exercise inventory limit")
        target_db = "m17_restore_" + uuid.uuid4().hex
        target_bucket = "traceback-m17-restore-" + uuid.uuid4().hex
        command("docker", "exec", PG, "createdb", "-U", "traceback_m17", target_db)
        command("docker", "exec", "-i", PG, "pg_restore", "--exit-on-error", "-U", "traceback_m17", "-d", target_db, data=dump)
        assert json.loads(sql(target_db, INVENTORY)) == inventory
        # Fixed loopback-only emulator SDK, no default endpoint or credential chain.
        copy_code = '''import boto3,sys,json,hashlib
s=boto3.client("s3",endpoint_url="http://127.0.0.1:4566",region_name="us-east-1",aws_access_key_id="test",aws_secret_access_key="test")
p=json.load(sys.stdin);s.create_bucket(Bucket=p["target"])
for r in p["rows"]:
 key="documents/"+r["storage_key"]
 b=s.get_object(Bucket="traceback-documents-local",Key=key)["Body"].read(10485761)
 assert len(b)==r["file_size"] and hashlib.sha256(b).hexdigest()==r["checksum"]
 s.put_object(Bucket=p["target"],Key=key,Body=b,IfNoneMatch="*",ServerSideEncryption="AES256")
 restored=s.get_object(Bucket=p["target"],Key=key)["Body"].read(10485761)
 assert hashlib.sha256(restored).hexdigest()==r["checksum"]
print("Object checksums verified")'''
        for row in inventory:
            uuid.UUID(row["storage_key"])
            if row["storage_provider"] != "S3":
                raise ValueError("Exercise expects synthetic S3 sources; legacy LOCAL needs coordinated file backup")
        command("docker", "exec", "-i", AWS, "python", "-c", copy_code,
                data=json.dumps({"rows": inventory, "target": target_bucket}).encode())
        assert int(sql(target_db, "SELECT count(*) FROM flyway_schema_history WHERE success")) == 8
        report = {"passed": True, "environment": "disposable local PostgreSQL/LocalStack", "revisions": len(inventory),
                  "dumpSha256": hashlib.sha256(dump).hexdigest(), "restoreDatabase": target_db, "restoreBucket": target_bucket,
                  "checks": ["quiesced snapshot", "new database restore", "immutable metadata equality", "all S3 byte checksums", "new bucket integrity", "V1-V8 history"],
                  "limitations": ["not RDS PITR", "not real S3 durability/IAM", "not LOCAL file restore", "no guaranteed RPO/RTO"]}
        (directory / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    finally:
        command("docker", "start", APP)
        command("docker", "start", WORKER)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--directory", type=Path, required=True)
    try:
        exercise(p.parse_args().directory)
    except Exception:
        raise SystemExit("Local restore exercise failed; source data retained, inspect owned service health") from None
