#!/usr/bin/env python3
"""Mirror CorsairPlay GitHub releases for the in-app updater's fallback source."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path


REPOSITORY = os.environ.get("CORSAIRPLAY_GITHUB_REPO", "kriskwok/CorsairPlay")
API_URL = f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100"
PUBLIC_ROOT = os.environ.get(
    "CORSAIRPLAY_MIRROR_PUBLIC_ROOT",
    "https://node.czwyf.top:9443/corsairplay",
).rstrip("/")
ROOT = Path(os.environ.get("CORSAIRPLAY_MIRROR_ROOT", "/var/www/corsairplay"))
USER_AGENT = "CorsairPlay-Mirror/1.0"
CHECKSUMS_FILE = "SHA256SUMS.txt"
SAFE_PART = re.compile(r"^[A-Za-z0-9._-]+$")


def request(url: str, accept: str) -> urllib.request.Request:
    return urllib.request.Request(
        url,
        headers={
            "Accept": accept,
            "User-Agent": USER_AGENT,
        },
    )


def fetch_json(url: str) -> list[dict]:
    with urllib.request.urlopen(request(url, "application/vnd.github+json"), timeout=30) as response:
        return json.load(response)


def safe_path_part(value: str) -> str:
    if not value or not SAFE_PART.fullmatch(value):
        raise ValueError(f"unsafe release asset path component: {value!r}")
    return value


def download_asset(asset: dict, destination: Path) -> None:
    expected_size = int(asset.get("size") or 0)
    if destination.is_file() and (expected_size <= 0 or destination.stat().st_size == expected_size):
        return

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".part")
    digest = asset.get("digest")
    hasher = hashlib.sha256() if isinstance(digest, str) and digest.startswith("sha256:") else None
    written = 0
    try:
        with urllib.request.urlopen(
            request(asset["browser_download_url"], "application/octet-stream"),
            timeout=60,
        ) as response, temporary.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                written += len(chunk)
                if hasher is not None:
                    hasher.update(chunk)
        if expected_size > 0 and written != expected_size:
            raise ValueError(f"{asset['name']}: expected {expected_size} bytes, got {written}")
        if hasher is not None and hasher.hexdigest() != digest.split(":", 1)[1].lower():
            raise ValueError(f"{asset['name']}: SHA-256 mismatch")
        os.chmod(temporary, 0o644)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def mirror_release(release: dict, manifest: list[dict]) -> None:
    if release.get("draft"):
        return
    tag = safe_path_part(str(release.get("tag_name") or ""))
    assets = []
    for asset in release.get("assets") or []:
        name = str(asset.get("name") or "")
        if not (name.endswith(".apk") or name.casefold() == CHECKSUMS_FILE.casefold()):
            continue
        safe_path_part(name)
        destination = ROOT / "releases" / tag / name
        download_asset(asset, destination)
        quoted_tag = urllib.parse.quote(tag, safe="")
        quoted_name = urllib.parse.quote(name, safe="")
        assets.append(
            {
                "name": name,
                "size": destination.stat().st_size,
                "browser_download_url": f"{PUBLIC_ROOT}/releases/{quoted_tag}/{quoted_name}",
            }
        )
    if not any(asset["name"].endswith(".apk") for asset in assets):
        return
    if not any(asset["name"].casefold() == CHECKSUMS_FILE.casefold() for asset in assets):
        return
    manifest.append(
        {
            "tag_name": release.get("tag_name"),
            "draft": False,
            "prerelease": bool(release.get("prerelease")),
            "body": release.get("body") or "",
            "published_at": release.get("published_at"),
            "assets": assets,
        }
    )


def write_manifest(manifest: list[dict]) -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".releases.", suffix=".json", dir=ROOT)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(manifest, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.chmod(temporary_name, 0o644)
        os.replace(temporary_name, ROOT / "releases.json")
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def main() -> int:
    ROOT.mkdir(parents=True, exist_ok=True)
    lock_path = ROOT / ".sync.lock"
    with lock_path.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        releases = fetch_json(API_URL)
        manifest: list[dict] = []
        for release in releases:
            mirror_release(release, manifest)
        if not manifest:
            raise RuntimeError("GitHub returned no downloadable CorsairPlay releases")
        write_manifest(manifest)
    print(f"Mirrored {len(manifest)} releases to {ROOT / 'releases.json'}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"corsairplay-mirror-sync: {error}", file=sys.stderr)
        raise
