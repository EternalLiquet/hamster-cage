#!/usr/bin/env python3
"""Audit actual resolved Maven dependencies against OSV; service failure is not a pass."""
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1]
packages = json.loads((root / "app/build/reports/runtime-dependencies.json").read_text())
assert packages, "Dependency inventory is empty"
payload = {"queries": [{"package": {"name": p["name"], "ecosystem": "Maven"}, "version": p["version"]} for p in packages]}
request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
with urllib.request.urlopen(request, timeout=60) as response:
    results = json.load(response)["results"]
assert len(results) == len(packages), "Incomplete vulnerability response"
assert not any(r.get("next_page_token") for r in results), "Paginated vulnerability response requires further review"
findings = [{**p, "vulnerabilities": r["vulns"]} for p, r in zip(packages, results) if r.get("vulns")]
(root / "app/build/reports/dependency-audit.json").write_text(json.dumps({"packages_checked": len(packages), "findings": findings}, indent=2))
for finding in findings:
    print(finding["name"], finding["version"], ", ".join(v["id"] for v in finding["vulnerabilities"]))
assert not findings, "Known runtime dependency vulnerabilities require review and remediation"
print(f"PASS: OSV reports no known vulnerabilities for {len(packages)} resolved Maven dependencies")
