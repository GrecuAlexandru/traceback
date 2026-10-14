"""Fail on scanner errors or findings. OSV receives only a generated dependency SBOM.

Gitleaks scans a disposable copy of current tracked/nonignored source, not Git history,
ignored .env files or build output. Its retained report is fully redacted.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def run(root, binaries, output):
    output.mkdir(parents=True, exist_ok=True)
    sbom = root / "backend/target/bom.json"
    if not sbom.is_file():
        raise SystemExit("Generate backend/target/bom.json with the documented CycloneDX command first")
    for name in ("gitleaks", "osv-scanner"):
        if not (binaries / name).is_file():
            raise SystemExit(f"Missing required scanner: {name}")
    source_files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root).split(b"\0")
    with tempfile.TemporaryDirectory(prefix="traceback-source-scan-") as directory:
        stage = Path(directory)
        for raw in source_files:
            if not raw:
                continue
            relative = Path(raw.decode())
            source = root / relative
            if source.is_symlink():
                raise SystemExit("Linked source requires explicit scanner review")
            if source.is_file():
                target = stage / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        with (output / "gitleaks.log").open("w") as log:
            secret_result = subprocess.run([str(binaries / "gitleaks"), "dir", str(stage), "--redact=100", "--no-banner", "--report-format", "json", "--report-path", str(output / "gitleaks.json")], stdout=log, stderr=log)
    with (output / "osv.log").open("w") as log:
        dependency_result = subprocess.run([str(binaries / "osv-scanner"), "scan", "source", "--sbom", str(sbom), "--format", "json", "--output-file", str(output / "osv.json")], stdout=log, stderr=log)
    # A missing/unreadable report remains an error, never an empty successful scan.
    secrets = json.loads((output / "gitleaks.json").read_text())
    dependencies = json.loads((output / "osv.json").read_text())
    affected = [p for result in dependencies.get("results", []) for p in result.get("packages", []) if p.get("vulnerabilities")]
    summary = {
        "evaluatedAtUTC": datetime.now(timezone.utc).isoformat(),
        "sbomSha256": hashlib.sha256(sbom.read_bytes()).hexdigest(),
        "packagesInSbom": len(json.loads(sbom.read_text()).get("components", [])),
        "gitleaksVersion": subprocess.check_output([str(binaries / "gitleaks"), "version"], text=True).strip(),
        "osvVersion": subprocess.check_output([str(binaries / "osv-scanner"), "--version"], text=True).splitlines()[0],
        "gitleaksExitCode": secret_result.returncode, "osvExitCode": dependency_result.returncode,
        "secretFindings": len(secrets), "vulnerablePackages": len(affected),
        "advisories": sorted({v["id"] for p in affected for v in p["vulnerabilities"]}),
        "limitations": ["Current nonignored source, not Git history or ignored local credentials", "OSV online vulnerability database and GitHub release downloads are network dependencies", "SBOM package match does not establish exploit reachability; no container image vulnerability scan"],
    }
    (output / "scan-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    if secret_result.returncode or dependency_result.returncode:
        raise SystemExit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binaries", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("backend/target/m15/scans"))
    args = parser.parse_args()
    run(Path(__file__).resolve().parents[2], args.binaries.resolve(), args.output.resolve())
