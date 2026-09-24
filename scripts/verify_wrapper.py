#!/usr/bin/env python3
"""Pin Gradle bootstrap artifacts before executing code from the wrapper JAR."""
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Official https://services.gradle.org/distributions/gradle-8.11.1-{wrapper.jar,bin.zip}.sha256
WRAPPER_SHA256 = "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"
DISTRIBUTION_SHA256 = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
EXPECTED_PROPERTIES = (
    "distributionBase=GRADLE_USER_HOME\n"
    "distributionPath=wrapper/dists\n"
    f"distributionSha256Sum={DISTRIBUTION_SHA256}\n"
    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip\n"
    "networkTimeout=10000\n"
    "validateDistributionUrl=true\n"
    "zipStoreBase=GRADLE_USER_HOME\n"
    "zipStorePath=wrapper/dists\n"
)


def verify(root: Path) -> None:
    wrapper = root / "gradle/wrapper"
    if sha256((wrapper / "gradle-wrapper.jar").read_bytes()).hexdigest() != WRAPPER_SHA256:
        raise ValueError("Gradle wrapper JAR checksum mismatch")
    # Java Properties supports escaped keys, multiple separators, continuations and
    # last-declaration wins. Pin the whole reviewed file instead of approximating its
    # parser. Text mode normalizes only platform line endings (LF/CRLF/CR).
    if (wrapper / "gradle-wrapper.properties").read_text(encoding="utf-8") != EXPECTED_PROPERTIES:
        raise ValueError("Gradle wrapper properties must match the reviewed canonical configuration")


if __name__ == "__main__":
    verify(ROOT)
    print("PASS: reviewed Gradle wrapper and distribution checksums")
