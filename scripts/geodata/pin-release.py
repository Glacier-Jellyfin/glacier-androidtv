#!/usr/bin/env python3
"""Pins the app to a geodata release made by the Geodata workflow.

GeoNames changes daily, so a local build never equals the published packs. The app has to
pin exactly what the workflow published: this script takes the release, verifies it and
writes the result into the repository.

    python3 -I scripts/geodata/pin-release.py geodata-2026-11
        [--repo Glacier-Jellyfin/glacier-androidtv] [--skip-attestation]

Steps:
  1. Reads the release from the GitHub API (not draft, not pre-release, the five
     places-<level>.bin.gz and manifest.json present).
  2. Downloads manifest.json and places-15000.bin.gz into a fresh temporary folder. Only
     https://api.github.com and https://github.com are asked; redirects are followed only to
     github.com, api.github.com and *.githubusercontent.com (where GitHub keeps the assets).
  3. Verifies the build provenance attestation of both files with `gh attestation verify`
     (signer workflow .github/workflows/geodata.yml, ref refs/heads/main). Without `gh` the
     commands are printed and the script stops, unless --skip-attestation is given.
  4. Checks the manifest and the downloaded pack (sha256, sizes, header of the format).
  5. Rewrites BASE_URL and the table ALL in GeoPacks.kt and writes the verified pack as
     app/src/main/assets/geodata/places-15000.pack (same bytes as the .gz; the Android build
     would gunzip an asset ending in .gz).

Afterwards run ./gradlew :core:data:testDebugUnitTest and commit GeoPacks.kt and
places-15000.pack together. GITHUB_TOKEN or GH_TOKEN is used for the API if set (optional).
Python 3 standard library only.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
GEOPACKS_KT = os.path.join(REPO_ROOT, "core", "data", "src", "main", "java", "io", "github", "glacier_jellyfin",
                           "androidtv", "core", "data", "geodata", "GeoPacks.kt")
ASSET = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "geodata", "places-15000.pack")
DEFAULT_REPO = "Glacier-Jellyfin/glacier-androidtv"
USER_AGENT = "glacier-geodata-pin/1.0"
TAG_RE = re.compile(r"^geodata-[0-9]{4}-(0[1-9]|1[0-2])(\.[0-9]+)?$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
DATE_RE = re.compile(r"^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
LEVELS = (15000, 5000, 1000, 500, "all")
MANIFEST = "manifest.json"
MAX_DOWNLOAD = 64 * 1024 * 1024
MAX_RAW = 512 * 1024 * 1024
ALLOWED_HOSTS = ("github.com", "api.github.com")
ALLOWED_SUFFIX = ".githubusercontent.com"


class PinError(Exception):
    pass


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def pack_file(level) -> str:
    return f"places-{level}.bin.gz"


def host_allowed(url: str) -> bool:
    parts = urllib.parse.urlsplit(url)
    host = (parts.hostname or "").lower()
    return parts.scheme == "https" and (host in ALLOWED_HOSTS or host.endswith(ALLOWED_SUFFIX))


class SafeRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not host_allowed(newurl):
            raise PinError(f"refusing redirect to {newurl}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(SafeRedirects)


def headers(accept: str | None = None, token: bool = False) -> dict[str, str]:
    result = {"User-Agent": USER_AGENT}
    if accept:
        result["Accept"] = accept
    secret = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and secret:
        result["Authorization"] = f"Bearer {secret}"
    return result


def fetch_release(repo: str, tag: str) -> dict:
    url = f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
    log(f"reading {url}")
    request = urllib.request.Request(url, headers=headers("application/vnd.github+json", token=True))
    try:
        with opener().open(request, timeout=60) as response:
            body = response.read(MAX_DOWNLOAD)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise PinError(f"release not found: {tag} in {repo} (HTTP 404)")
        raise PinError(f"GitHub API answered HTTP {e.code} for {url}")
    except urllib.error.URLError as e:
        raise PinError(f"cannot reach GitHub: {e.reason}")
    try:
        release = json.loads(body)
    except ValueError:
        raise PinError("the GitHub API answer is not JSON")
    if not isinstance(release, dict):
        raise PinError("unexpected answer of the GitHub API")
    return release


def check_release(release: dict, tag: str) -> dict[str, int]:
    """Returns the asset sizes by name."""
    if release.get("tag_name") != tag:
        raise PinError(f"the release has tag {release.get('tag_name')!r}, not {tag!r}")
    if release.get("draft"):
        raise PinError("the release is a draft")
    if release.get("prerelease"):
        raise PinError("the release is marked as pre-release")
    sizes = {a.get("name"): a.get("size") for a in release.get("assets") or [] if isinstance(a, dict)}
    missing = [n for n in [pack_file(lv) for lv in LEVELS] + [MANIFEST] if n not in sizes]
    if missing:
        raise PinError("assets missing in the release: " + ", ".join(missing))
    return sizes


def download(url: str, target: str) -> None:
    if not host_allowed(url):
        raise PinError(f"refusing to download from {url}")
    log(f"downloading {url}")
    request = urllib.request.Request(url, headers=headers())
    total = 0
    try:
        with opener().open(request, timeout=120) as response, open(target, "wb") as out:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_DOWNLOAD:
                    raise PinError(f"{url} is larger than {MAX_DOWNLOAD} bytes")
                out.write(chunk)
    except urllib.error.HTTPError as e:
        raise PinError(f"download failed with HTTP {e.code}: {url}")
    except urllib.error.URLError as e:
        raise PinError(f"download failed: {e.reason}")


def verify_attestation(repo: str, files: list[str]) -> None:
    workflow = f"{repo}/.github/workflows/geodata.yml"
    commands = [["gh", "attestation", "verify", f, "--repo", repo, "--signer-workflow", workflow,
                 "--source-ref", "refs/heads/main"] for f in files]
    if shutil.which("gh") is None:
        text = "\n".join("  " + " ".join(c) for c in commands)
        raise PinError("`gh` is not installed, so the attestation was not verified. Run by hand, then run this "
                       "script again with --skip-attestation:\n" + text)
    for command in commands:
        log("running " + " ".join(command))
        try:
            subprocess.run(command, check=True)
        except (subprocess.CalledProcessError, OSError) as e:
            raise PinError(f"attestation check failed for {command[3]}: {e}")


def is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def check_manifest(manifest, sizes: dict[str, int]) -> list[dict]:
    if not isinstance(manifest, dict) or manifest.get("formatVersion") != 1:
        raise PinError("manifest: formatVersion is not 1")
    date = manifest.get("dataDate")
    if not isinstance(date, str) or not DATE_RE.match(date):
        raise PinError(f"manifest: bad dataDate {date!r}")
    packs = manifest.get("packs")
    if not isinstance(packs, list) or len(packs) != len(LEVELS):
        raise PinError("manifest: packs must list exactly the levels " + ", ".join(map(str, LEVELS)))
    for pack, level in zip(packs, LEVELS):
        if not isinstance(pack, dict):
            raise PinError("manifest: a pack is not an object")
        if pack.get("level") != level or type(pack.get("level")) is not type(level):
            raise PinError(f"manifest: expected level {level!r}, found {pack.get('level')!r}")
        if pack.get("file") != pack_file(level):
            raise PinError(f"manifest: level {level}: file is {pack.get('file')!r}")
        sha = pack.get("sha256")
        if not isinstance(sha, str) or not SHA_RE.match(sha):
            raise PinError(f"manifest: level {level}: bad sha256")
        for key in ("entries", "rawSize", "gzSize"):
            if not is_int(pack.get(key)) or pack[key] <= 0:
                raise PinError(f"manifest: level {level}: {key} is not a positive integer")
        if sizes.get(pack["file"]) != pack["gzSize"]:
            raise PinError(f"manifest: level {level}: gzSize {pack['gzSize']} differs from the asset size "
                           f"{sizes.get(pack['file'])}")
    return packs


def check_pack(path: str, pack: dict) -> bytes:
    """Checks the downloaded 15000 pack against the manifest, returns its bytes."""
    with open(path, "rb") as f:
        data = f.read(MAX_DOWNLOAD + 1)
    if len(data) != pack["gzSize"]:
        raise PinError(f"places-15000.bin.gz has {len(data)} bytes, the manifest says {pack['gzSize']}")
    if hashlib.sha256(data).hexdigest() != pack["sha256"]:
        raise PinError("places-15000.bin.gz: sha256 differs from the manifest")
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as g:
            raw = g.read(MAX_RAW + 1)
    except (OSError, EOFError) as e:
        raise PinError(f"places-15000.bin.gz is not valid gzip: {e}")
    if len(raw) != pack["rawSize"]:
        raise PinError(f"unpacked size {len(raw)} differs from rawSize {pack['rawSize']}")
    if len(raw) < 64 or raw[:4] != b"GLPL":
        raise PinError("places-15000.bin.gz: wrong magic")
    version = struct.unpack_from("<H", raw, 4)[0]
    min_population = struct.unpack_from("<I", raw, 8)[0]
    entry_count = struct.unpack_from("<I", raw, 16)[0]
    if version != 1:
        raise PinError(f"places-15000.bin.gz: format version {version}, expected 1")
    if min_population != 15000:
        raise PinError(f"places-15000.bin.gz: minPopulation {min_population}, expected 15000")
    if entry_count != pack["entries"]:
        raise PinError(f"places-15000.bin.gz: {entry_count} entries, the manifest says {pack['entries']}")
    return data


def underscored(number: int) -> str:
    return f"{number:,}".replace(",", "_")


def kotlin_table(packs: list[dict]) -> str:
    lines = ["    val ALL: List<GeoPack> = listOf("]
    for p in packs:
        level = 0 if p["level"] == "all" else p["level"]
        lines += ["        GeoPack(",
                  f"            level = {underscored(level)},",
                  f"            file = \"{p['file']}\",",
                  f"            sha256 = \"{p['sha256']}\",",
                  f"            size = {underscored(p['gzSize'])}L,",
                  f"            rawSize = {underscored(p['rawSize'])}L,",
                  "        ),"]
    lines.append("    )")
    return "\n".join(lines) + "\n"


def rewrite_kotlin(text: str, repo: str, tag: str, packs: list[dict]) -> str:
    base = re.compile(r'(const val BASE_URL = ")[^"\n]*(")')
    table = re.compile(r"^    val ALL: List<GeoPack> = listOf\(\n.*?\n    \)\n", re.DOTALL | re.MULTILINE)
    if len(base.findall(text)) != 1:
        raise PinError("GeoPacks.kt: BASE_URL not found exactly once")
    if len(table.findall(text)) != 1:
        raise PinError("GeoPacks.kt: the table ALL not found exactly once")
    url = f"https://github.com/{repo}/releases/download/{tag}/"
    text = base.sub(lambda m: m.group(1) + url + m.group(2), text)
    return table.sub(lambda m: kotlin_table(packs), text)


def update_repo(repo: str, tag: str, packs: list[dict], data: bytes) -> None:
    with open(GEOPACKS_KT, encoding="utf-8", newline="") as f:
        old = f.read()
    new = rewrite_kotlin(old, repo, tag, packs)  # fails before anything is written
    with open(GEOPACKS_KT, "w", encoding="utf-8", newline="\n") as f:
        f.write(new)
    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    with open(ASSET, "wb") as f:
        f.write(data)
    log(f"wrote {os.path.relpath(GEOPACKS_KT, REPO_ROOT)} and {os.path.relpath(ASSET, REPO_ROOT)}")


def pin(repo: str, tag: str, skip_attestation: bool) -> None:
    sizes = check_release(fetch_release(repo, tag), tag)
    work = tempfile.mkdtemp(prefix="glacier-geodata-pin-")
    try:
        base = f"https://github.com/{repo}/releases/download/{tag}/"
        manifest_path = os.path.join(work, MANIFEST)
        pack_path = os.path.join(work, pack_file(15000))
        download(base + MANIFEST, manifest_path)
        download(base + pack_file(15000), pack_path)
        if skip_attestation:
            log("WARNING: --skip-attestation: the files are NOT verified against the Geodata workflow. "
                "Do not commit this unless you checked them another way.")
        else:
            verify_attestation(repo, [manifest_path, pack_path])
        with open(manifest_path, "rb") as f:
            try:
                manifest = json.loads(f.read(MAX_DOWNLOAD))
            except ValueError:
                raise PinError("manifest.json is not valid JSON")
        packs = check_manifest(manifest, sizes)
        data = check_pack(pack_path, packs[0])
        update_repo(repo, tag, packs, data)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print(f"pinned {tag}, data date {manifest['dataDate']}")
    print(f"{'level':>7} {'entries':>10} {'rawSize':>12} {'gzSize':>11}  sha256")
    for p in packs:
        print(f"{p['level']!s:>7} {p['entries']:>10,} {p['rawSize']:>12,} {p['gzSize']:>11,}  {p['sha256']}")
    print("\nNext: ./gradlew :core:data:testDebugUnitTest, then commit GeoPacks.kt and "
          "app/src/main/assets/geodata/places-15000.pack together.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Pins the app to a geodata release of the Geodata workflow.")
    parser.add_argument("tag", help="release tag, for example geodata-2026-11")
    parser.add_argument("--repo", default=DEFAULT_REPO, help=f"default {DEFAULT_REPO}")
    parser.add_argument("--skip-attestation", action="store_true",
                        help="do not verify the attestation (unsafe)")
    args = parser.parse_args(argv)
    try:
        if not TAG_RE.match(args.tag):
            raise PinError(f"bad tag {args.tag!r}, expected geodata-YYYY-MM or geodata-YYYY-MM.N")
        if not REPO_RE.match(args.repo):
            raise PinError(f"bad repository {args.repo!r}, expected owner/name")
        pin(args.repo, args.tag, args.skip_attestation)
    except PinError as e:
        log(f"error: {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
