"""Require executed tests and reject skipped, failed or errored default CI tests."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[2] / "backend/target/surefire-reports"
counts = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
for path in root.glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    for name in counts:
        counts[name] += int(suite.attrib.get(name, "0"))
print(counts)
sys.exit(0 if counts["tests"] and not any(counts[k] for k in ("failures", "errors", "skipped")) else 1)
