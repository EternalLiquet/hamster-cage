#!/usr/bin/env python3
"""Audit resolved Maven dependencies against OSV; unavailable/incomplete is a failure."""
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    reports = ROOT / "app/build/reports"
    packages = json.loads((reports / "runtime-dependencies.json").read_text(encoding="utf-8"))
    if not packages:
        raise SystemExit("Dependency inventory is empty")
    payload = {"queries": [{"package": {"name": p["name"], "ecosystem": "Maven"}, "version": p["version"]} for p in packages]}
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    if not isinstance(results, list) or len(results) != len(packages):
        raise SystemExit("Incomplete vulnerability response")
    if not all(isinstance(result, dict) for result in results):
        raise SystemExit("Malformed vulnerability response")
    if any(result.get("next_page_token") for result in results):
        raise SystemExit("Paginated vulnerability response requires further review")
    findings = [{**package, "vulnerabilities": result["vulns"]} for package, result in zip(packages, results) if result.get("vulns")]
    (reports / "dependency-audit.json").write_text(json.dumps({"packages_checked": len(packages), "findings": findings}, indent=2), encoding="utf-8")
    if findings:
        raise SystemExit("Known runtime dependency vulnerabilities require review; see dependency-audit.json")
    print(f"PASS: OSV reports no known vulnerabilities for {len(packages)} resolved Maven dependencies")


if __name__ == "__main__":
    main()
