#!/usr/bin/env python3
"""Pin Gradle bootstrap artifacts before executing code from the wrapper JAR."""
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Official https://services.gradle.org/distributions/gradle-8.11.1-{wrapper.jar,bin.zip}.sha256
WRAPPER_SHA256 = "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"
DISTRIBUTION_SHA256 = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"


def verify(root: Path) -> None:
    wrapper = root / "gradle/wrapper"
    if sha256((wrapper / "gradle-wrapper.jar").read_bytes()).hexdigest() != WRAPPER_SHA256:
        raise ValueError("Gradle wrapper JAR checksum mismatch")
    properties = dict(
        line.split("=", 1) for line in (wrapper / "gradle-wrapper.properties").read_text().splitlines()
        if line.strip() and not line.lstrip().startswith("#") and "=" in line
    )
    if properties.get("distributionSha256Sum") != DISTRIBUTION_SHA256:
        raise ValueError("Gradle distribution checksum must match the reviewed version")
    if properties.get("distributionUrl", "").replace("\\:", ":") != "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip":
        raise ValueError("Gradle distribution URL must use the pinned official HTTPS artifact")


if __name__ == "__main__":
    verify(ROOT)
    print("PASS: reviewed Gradle wrapper and distribution checksums")
