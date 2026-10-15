"""Install checksum-pinned Terraform/Trivy public binaries outside the checkout."""
import argparse
import hashlib
import platform
import subprocess
import tarfile
import zipfile
from pathlib import Path

RELEASES = {
    ("Darwin", "arm64"): [
        ("terraform", "https://releases.hashicorp.com/terraform/1.16.6/terraform_1.16.6_darwin_arm64.zip", "85e1bc78ab45bdc5f82a7bac3929e37d072834a584d22583a2498dc67bb624cd"),
        ("trivy", "https://github.com/aquasecurity/trivy/releases/download/v0.76.0/trivy_0.76.0_macOS-ARM64.tar.gz", "e7ec0d21fe842553f59af99aba2efbcc822369fbaed27019c575408b6d135fce"),
    ],
    ("Linux", "x86_64"): [
        ("terraform", "https://releases.hashicorp.com/terraform/1.16.6/terraform_1.16.6_linux_amd64.zip", "38034b38d03096399a7a1264a14561a8029b2b496fd1f169452656e53968f1b7"),
        ("trivy", "https://github.com/aquasecurity/trivy/releases/download/v0.76.0/trivy_0.76.0_Linux-64bit.tar.gz", "e24b1c51293c1b15e4ba8d45bbc7891ba053f90278ec2698f3562c993e145fe7"),
    ],
}


def install(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for binary, url, checksum in RELEASES[(platform.system(), platform.machine())]:
        archive = directory / url.rsplit("/", 1)[1]
        subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "120", url, "--output", str(archive)], check=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
            raise SystemExit("Public tool checksum mismatch")
        if url.endswith(".zip"):
            data = zipfile.ZipFile(archive).read(binary)
        else:
            with tarfile.open(archive) as source:
                data = source.extractfile(binary).read()
        output = directory / binary
        output.write_bytes(data)
        output.chmod(0o755)
        print("Checksum verified:", binary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    install(parser.parse_args().directory.resolve())
