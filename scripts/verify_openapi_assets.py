#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Final

try:
    import yaml
except ModuleNotFoundError:
    print(
        "Missing Python dependency 'PyYAML'. Install OpenAPI verification dependencies with: "
        "python3 -m pip install -r python/requirements.txt",
        file=sys.stderr,
    )
    sys.exit(1)

OPENAPI_BUNDLE_NAMES: Final[tuple[str, ...]] = (
    "product-catalog",
    "locations",
    "order-process",
)


class OpenApiAssetVerificationError(Exception):
    pass


def resolve_root_dir() -> Path:
    if root_dir := os.environ.get("ROOT_DIR"):
        return Path(root_dir).resolve()

    return Path(__file__).resolve().parents[1]


def read_gradle_version(root_dir: Path) -> str:
    gradle_file = root_dir / "build.gradle.kts"
    if not gradle_file.exists():
        raise OpenApiAssetVerificationError(f"Gradle build file not found: {gradle_file}")

    for line in gradle_file.read_text(encoding="utf-8").splitlines():
        match = re.match(r'^\s*version\s*=\s*"([^"]+)"\s*$', line)
        if match:
            return match.group(1)

    raise OpenApiAssetVerificationError(f"Unable to determine version from {gradle_file}")


def verify_openapi_pair(yaml_path: Path, json_path: Path, expected_version: str) -> None:
    yaml_document = load_yaml_document(yaml_path)
    json_document = load_json_document(json_path)

    issues: list[str] = []

    yaml_version = extract_info_version(yaml_document, yaml_path)
    if yaml_version != expected_version:
        issues.append(
            f"Version drift in {yaml_path}: expected {expected_version} from build.gradle.kts, found {yaml_version}"
        )

    json_version = extract_info_version(json_document, json_path)
    if json_version != expected_version:
        issues.append(
            f"Version drift in {json_path}: expected {expected_version} from build.gradle.kts, found {json_version}"
        )

    yaml_json = canonical_json(yaml_document)
    json_json = canonical_json(json_document)
    if yaml_json != json_json:
        issues.append(
            f"OpenAPI YAML/JSON drift detected for {yaml_path.name}: regenerate {json_path.name} from {yaml_path.name}"
        )

    if issues:
        raise OpenApiAssetVerificationError("\n".join(issues))

    print(f"Verified {yaml_path.name} ↔ {json_path.name}: version {expected_version}")


def load_yaml_document(spec_path: Path) -> dict[str, Any]:
    if not spec_path.exists():
        raise OpenApiAssetVerificationError(f"OpenAPI asset not found: {spec_path}")

    try:
        document = yaml.safe_load(spec_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as error:  # type: ignore[attr-defined]
        raise OpenApiAssetVerificationError(f"Invalid YAML in {spec_path}: {error}") from error

    if not isinstance(document, dict):
        raise OpenApiAssetVerificationError(f"Expected mapping document in {spec_path}")

    return document


def load_json_document(spec_path: Path) -> dict[str, Any]:
    if not spec_path.exists():
        raise OpenApiAssetVerificationError(f"OpenAPI asset not found: {spec_path}")

    try:
        document = json.loads(spec_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise OpenApiAssetVerificationError(f"Invalid JSON in {spec_path}: {error}") from error

    if not isinstance(document, dict):
        raise OpenApiAssetVerificationError(f"Expected object document in {spec_path}")

    return document


def extract_info_version(document: dict[str, Any], spec_path: Path) -> str:
    info = document.get("info")
    if not isinstance(info, dict) or not info.get("version"):
        raise OpenApiAssetVerificationError(f"Missing info.version in {spec_path}")

    return str(info["version"])


def canonical_json(document: dict[str, Any]) -> str:
    return json.dumps(document, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def verify_openapi_assets(root_dir: Path) -> None:
    expected_version = read_gradle_version(root_dir)
    openapi_dir = root_dir / "api_collections" / "open_api_spec"

    errors: list[str] = []

    for bundle_name in OPENAPI_BUNDLE_NAMES:
        yaml_path = openapi_dir / f"{bundle_name}.yaml"
        json_path = openapi_dir / f"{bundle_name}.json"
        try:
            verify_openapi_pair(yaml_path, json_path, expected_version)
        except OpenApiAssetVerificationError as error:
            errors.append(str(error))

    if errors:
        raise OpenApiAssetVerificationError("\n\n".join(errors))

    print(f"OpenAPI assets are synchronized with build.gradle.kts version {expected_version}")


def main() -> int:
    try:
        root_dir = resolve_root_dir()
        verify_openapi_assets(root_dir)
    except OpenApiAssetVerificationError as error:
        print(f"OpenAPI asset verification failed: {error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
