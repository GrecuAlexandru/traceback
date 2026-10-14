"""Install two pinned, checksum-verified public scanners outside the repository.

Requires curl (uses the host trust store). No model downloads, package source uploads,
account credentials or paid services. Supported hosts match local development and CI.
"""
import argparse
import hashlib
import platform
import subprocess
import tarfile
from pathlib import Path

RELEASES = {
    ("Darwin", "arm64"): [
        ("gitleaks/gitleaks", "v8.30.1", "gitleaks_8.30.1_darwin_arm64.tar.gz", "gitleaks", "b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5"),
        ("google/osv-scanner", "v2.7.0", "osv-scanner_darwin_arm64", "osv-scanner", "d4926ef8da134332543be276dff157e4c170f99ad91fc48e89448a8816bdf70b"),
    ],
    ("Linux", "x86_64"): [
        ("gitleaks/gitleaks", "v8.30.1", "gitleaks_8.30.1_linux_x64.tar.gz", "gitleaks", "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"),
        ("google/osv-scanner", "v2.7.0", "osv-scanner_linux_amd64", "osv-scanner", "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b"),
    ],
}


def install(destination):
    key = (platform.system(), platform.machine())
    if key not in RELEASES:
        raise SystemExit(f"Unsupported scanner binary platform: {key}")
    destination.mkdir(parents=True, exist_ok=True)
    for repo, version, archive_name, binary, digest in RELEASES[key]:
        archive = destination / archive_name
        url = f"https://github.com/{repo}/releases/download/{version}/{archive_name}"
        subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "120", url, "--output", str(archive)], check=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise SystemExit(f"Checksum verification failed for {archive_name}")
        output = destination / binary
        if archive_name.endswith(".tar.gz"):
            with tarfile.open(archive) as source:
                output.write_bytes(source.extractfile("gitleaks").read())
        elif archive != output:
            output.write_bytes(archive.read_bytes())
        output.chmod(0o755)
        print(f"Verified {binary} {version}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    install(parser.parse_args().directory.resolve())
