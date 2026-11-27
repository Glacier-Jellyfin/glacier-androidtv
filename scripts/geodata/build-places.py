#!/usr/bin/env python3
"""Builds the offline place-name packs of Glacier (places-<level>.bin.gz) from GeoNames.

A pack lists places of GeoNames of feature class P, with the name of the first-order
administrative region (admin1) and the names of place and region in English, German and
Spanish. The app finds the nearest place of a coordinate in it. Countries are not stored,
the app localizes them through java.util.Locale.

Which places a level holds (90.6 % of the class P rows have population 0, so the population
alone would drop almost every small place):
  - 15000 and 5000: class P with a population of at least N, or a national or first or
    second order administrative seat (feature codes PPLC, PPLA, PPLA2) whatever its
    population;
  - 1000 and 500: class P with a population of at least N, or an administrative seat of
    any order (PPLC, PPLA, PPLA2, PPLA3, PPLA4, PPLG) whatever its population;
  - "all": every class P row, without the population filter;
  - always left out: PPLX (section of a populated place), PPLH (historical), PPLQ
    (abandoned), PPLW (destroyed) and PPLCH (former capital).
  - "all-plx" (only to measure, written to <out>/experimental/, not in the manifest): like
    "all" but with PPLX kept.

Data: GeoNames (https://www.geonames.org, CC BY 4.0): allCountries.zip,
admin1CodesASCII.txt and alternateNamesV2.zip, downloaded into --work if not yet there and
streamed from the zips (nothing is extracted). Python 3 standard library only. The "all"
level needs a few GB of memory.

    python3 -I scripts/geodata/build-places.py --work ~/.cache/glacier-geodata --out build/geodata
        [--levels 15000,5000,1000,500,all] [--stats] [--stats-file FILE] [--assets]

--assets also copies places-15000.bin.gz as places-15000.pack (same bytes) to app/src/main/assets/geodata/ of this repository.

Writes places-<level>.bin.gz (deterministic: same input, same bytes), manifest.json and,
with --stats, stats.json, and prints the Kotlin lines for the app's table of pinned packs
(level = 0 stands for "all").

Binary format v1 (places-<level>.bin, shipped gzipped). All integers little-endian.
Every section starts at a 4-byte aligned offset (zero padding).

Header, 64 bytes at offset 0:
    0  4 bytes  magic "GLPL"
    4  u16      version = 1
    6  u16      flags = 0
    8  u32      minPopulation (the level, 0 for "all")
    12 u32      dataDate as yyyymmdd (Last-Modified of allCountries.zip, else build date UTC)
    16 u32      entryCount
    20 u32      admin1Count
    24 u32      localizedCount
    28 u32      languageCount (3)
    32 u32      languagesOffset
    36 u32      cellIndexOffset
    40 u32      entriesOffset
    44 u32      admin1Offset
    48 u32      localizedOffset
    52 u32      namesOffset
    56 u32      namesSize in bytes
    60 u32      reserved = 0
Languages: languageCount x 4 bytes, 2 ASCII bytes ISO 639-1 ("en", "de", "es") + 2 zero
    bytes. The index in this table is the language index below.
Cell index: (180 * 360 + 1) x u32. Grid of 1x1 degree cells,
    cell(lat, lon) = (floor(lat) + 90) * 360 + (floor(lon) + 180), lat clamped to
    [-90, 89.99999], lon normalised to [-180, 180) (180 -> -180). cellIndex[c] is the index
    of the first entry of cell c, cellIndex[64800] = entryCount.
Entries: entryCount x 16 bytes, sorted by (cell, lat, lon, geonameid):
    0 i32 latitude in 1e-5 degrees (rounded half away from zero), 4 i32 longitude likewise,
    8 2 bytes ISO 3166-1 alpha-2 country code (zero bytes if missing), 10 u16 admin1 index
    (0xFFFF = none), 12 u32 offset of the name in the name blob.
    The cell of an entry is taken from its stored (rounded) coordinates.
Admin1 table: admin1Count x 8 bytes, sorted by (country, admin1 code), only regions that an
    entry references: 0 2 bytes country code, 2 u16 reserved 0, 4 u32 name offset (the
    name of the region's own GeoNames entry (class A, code ADM1, the geonameid of
    admin1CodesASCII.txt), because the name columns of that file are ASCII ("Baden-Wurttemberg")
    or a long form ("State of Berlin"); column 2 only if the entry is not in the dump).
Localized names: localizedCount x 8 bytes sorted by key: u32 key = (target << 2) | language
    index, where target is the entry index for places and entryCount + admin1 index for
    regions; u32 name offset. A record exists only where the name differs from the stored
    GeoNames name. Names of places: the first preferred name, else the first plain name; names
    of regions: only a preferred name (isPreferredName=1, not historic or colloquial).
Name blob: namesSize bytes of strings, each u16 byte length + UTF-8 bytes, deduplicated;
    offsets point at the length prefix.

Limits: entryCount + admin1Count must stay below 2^30 (key), admin1Count below 65535, the
name blob below 4 GiB; the script checks all three.

Lookup: nearest entry by haversine (radius 6371.0088 km) over the cell of the query and its
neighbours, widening ring by ring until the ring's minimum distance exceeds the best found
or 50 km; nothing is found beyond 50 km. Longitude wraps at the antimeridian, near the
poles the needed number of columns follows from the latitude (all columns above 89 degrees).
Ties go to the lower entry index.
"""
from __future__ import annotations

import argparse
import datetime
import email.utils
import gzip
import hashlib
import io
import json
import lzma
import os
import struct
import sys
import time
import unicodedata
import urllib.request
import zipfile
import zlib
from decimal import ROUND_HALF_UP, Decimal

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
BASE_URL = "https://download.geonames.org/export/dump/"
FILES = ("allCountries.zip", "admin1CodesASCII.txt", "alternateNamesV2.zip")
USER_AGENT = "glacier-geodata-build/1.0"
LANGS = ("en", "de", "es")
EXCLUDED_CODES = {"PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH"}
ALWAYS_EXCLUDED = {"PPLH", "PPLQ", "PPLW", "PPLCH"}
SEAT_CODES_HIGH = {"PPLC", "PPLA", "PPLA2"}  # levels 15000 and 5000
SEAT_CODES_ALL = SEAT_CODES_HIGH | {"PPLA3", "PPLA4", "PPLG"}  # levels 1000 and below
ALL = "all"
ALL_WITH_PLX = "all-plx"  # experimental, only measured
DEFAULT_LEVELS = "15000,5000,1000,500,all"
NO_ADMIN1 = 0xFFFF
CELLS = 180 * 360
HEADER_SIZE = 64
MAX_LAT_I = 8999999  # 89.99999 degrees in 1e-5 units

ENTRY = struct.Struct("<ii2sHI")
ADMIN = struct.Struct("<2sHI")
PAIR = struct.Struct("<II")


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def download(url: str, target: str) -> None:
    log(f"downloading {url}")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    part = target + ".part"
    with urllib.request.urlopen(request, timeout=60) as response, open(part, "wb") as out:
        modified = response.headers.get("Last-Modified")
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
    os.replace(part, target)
    if modified:
        with open(target + ".lastmodified", "w", encoding="ascii") as f:
            f.write(modified)


def fetch_all(work: str) -> dict[str, str]:
    os.makedirs(work, exist_ok=True)
    paths = {}
    for name in FILES:
        path = os.path.join(work, name)
        if not os.path.exists(path):
            download(BASE_URL + name, path)
        paths[name] = path
    return paths


def data_date(path: str) -> int:
    """yyyymmdd of the dump: the Last-Modified of allCountries.zip, else today (UTC)."""
    header = None
    sidecar = path + ".lastmodified"
    if os.path.exists(sidecar):
        with open(sidecar, encoding="ascii") as f:
            header = f.read().strip()
    else:
        try:
            request = urllib.request.Request(BASE_URL + "allCountries.zip", method="HEAD",
                                             headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=30) as response:
                header = response.headers.get("Last-Modified")
            if header:
                with open(sidecar, "w", encoding="ascii") as f:
                    f.write(header)
        except OSError:
            header = None
    try:
        when = email.utils.parsedate_to_datetime(header).astimezone(datetime.timezone.utc)
    except (TypeError, ValueError):
        when = datetime.datetime.now(datetime.timezone.utc)
    return when.year * 10000 + when.month * 100 + when.day


def e5(text: str) -> int:
    """Degrees to 1e-5 units, rounded half away from zero."""
    return int((Decimal(text) * 100000).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def wanted_place(code: str, pop: int, level: str) -> bool:
    """Does a place with this feature code and population belong to the pack of this level?"""
    if level == ALL_WITH_PLX:
        return code not in ALWAYS_EXCLUDED
    if level == ALL:
        return code not in EXCLUDED_CODES
    seats = SEAT_CODES_HIGH if int(level) >= 5000 else SEAT_CODES_ALL
    return code not in EXCLUDED_CODES and (pop >= int(level) or code in seats)


def read_places(path: str, levels: list[str]):
    """First pass: the places of any level and the class P counts for the statistics.

    A place is (geonameid, name, lat, lon, country, admin1 code, population, feature code),
    the short strings shared.
    """
    places = []
    counts = {"p_total": 0, "p_total_de": 0, "p_pop0": 0, "p_pop0_de": 0}
    per_country: dict[str, int] = {}
    adm1_names: dict[int, str] = {}
    intern: dict[str, str] = {}
    with zipfile.ZipFile(path) as z, z.open("allCountries.txt") as raw:
        for line in io.TextIOWrapper(raw, encoding="utf-8", newline="\n"):
            f = line.rstrip("\n").split("\t")
            if len(f) >= 15 and f[6] == "A" and f[7] == "ADM1":
                adm1_names[int(f[0])] = f[1]
            if len(f) < 15 or f[6] != "P":
                continue
            cc = f[8]
            pop = int(f[14]) if f[14] else 0
            counts["p_total"] += 1
            per_country[cc] = per_country.get(cc, 0) + 1
            if cc == "DE":
                counts["p_total_de"] += 1
            if pop == 0:
                counts["p_pop0"] += 1
                if cc == "DE":
                    counts["p_pop0_de"] += 1
            code = f[7]
            if any(wanted_place(code, pop, lv) for lv in levels):
                short = [intern.setdefault(v, v) for v in (cc, f[10], code)]
                places.append((int(f[0]), f[1], e5(f[4]), e5(f[5]), short[0], short[1], pop, short[2]))
    return places, counts, per_country, adm1_names


def read_admin1(path: str) -> dict[tuple[str, str], tuple[str, int]]:
    table = {}
    with open(path, encoding="utf-8", newline="\n") as f:
        for line in f:
            c = line.rstrip("\n").split("\t")
            if len(c) < 4 or "." not in c[0]:
                continue
            country, code = c[0].split(".", 1)
            table[(country, code)] = (c[1], int(c[3]))
    return table


def read_alternates(path: str, wanted: set[int], preferred_only: set[int]) -> dict[int, str]:
    """Localized name per key geonameid * 4 + language index, chosen by the rules of the pack.

    Per (id, language): the first isPreferredName=1 row that is not historic or colloquial,
    else the first row that is not short, colloquial or historic. For the ids in
    preferred_only (the admin1 regions) only the first rule applies.
    """
    preferred: dict[int, str] = {}
    fallback: dict[int, str] = {}
    lang_index = {lang: i for i, lang in enumerate(LANGS)}
    with zipfile.ZipFile(path) as z, z.open("alternateNamesV2.txt") as raw:
        for line in io.TextIOWrapper(raw, encoding="utf-8", newline="\n"):
            f = line.rstrip("\n").split("\t")
            if len(f) < 4 or not f[3]:
                continue
            li = lang_index.get(f[2])
            if li is None:
                continue
            gid = int(f[1])
            if gid not in wanted:
                continue
            f += [""] * (8 - len(f))
            if f[6] == "1" or f[7] == "1":  # colloquial or historic
                continue
            key = gid * 4 + li
            if f[4] == "1":
                preferred.setdefault(key, f[3])
            elif f[5] != "1" and gid not in preferred_only:
                fallback.setdefault(key, f[3])
    fallback.update(preferred)
    return fallback


class Blob:
    def __init__(self) -> None:
        self.data = bytearray()
        self.offsets: dict[str, int] = {}

    def add(self, text: str) -> int:
        offset = self.offsets.get(text)
        if offset is None:
            encoded = text.encode("utf-8")
            if len(encoded) > 0xFFFF:
                encoded = encoded[:0xFFFF].decode("utf-8", "ignore").encode("utf-8")
            offset = len(self.data)
            self.data += struct.pack("<H", len(encoded)) + encoded
            self.offsets[text] = offset
        return offset


def cell_of(lat_i: int, lon_i: int) -> int:
    lat_i = max(-9000000, min(MAX_LAT_I, lat_i))
    if lon_i >= 18000000:
        lon_i -= 36000000
    return ((lat_i // 100000) + 90) * 360 + (lon_i // 100000) + 180


def cc_bytes(cc: str) -> bytes:
    return cc.encode("ascii") if len(cc) == 2 and cc.isascii() else b"\0\0"


def ascii_fold(text: str) -> str:
    """The text without accents ("Baden-Wurttemberg" for "Baden-Württemberg"): the English names of GeoNames are
    often just this, and a region must not lose its umlaut to it."""
    return "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))


def has_admin1(p, admin1_table) -> bool:
    return p[5] not in ("", "00") and (p[4], p[5]) in admin1_table


def build_pack(level, places, admin1_table, alternates, date):
    """Returns (raw pack bytes, info dict for the statistics)."""
    chosen = [p for p in places if wanted_place(p[7], p[6], level)]
    # one integer per place: cell, latitude, longitude, geonameid; sorted indexes into chosen
    keys = [(cell_of(p[2], p[3]) << 100) | ((p[2] + 9000000) << 75) | ((p[3] + 18000000) << 50) | p[0]
            for p in chosen]
    order = sorted(range(len(chosen)), key=keys.__getitem__)
    entries = [chosen[i] for i in order]
    cells = [keys[i] >> 100 for i in order]
    del chosen, keys, order
    n = len(entries)

    used = sorted({(p[4], p[5]) for p in entries if has_admin1(p, admin1_table)})
    admin1_index = {key: i for i, key in enumerate(used)}
    if len(used) >= NO_ADMIN1 or n + len(used) >= 1 << 30:
        raise SystemExit("pack too large for format v1")

    blob = Blob()
    entry_names = [blob.add(p[1]) for p in entries]
    admin_names = [blob.add(admin1_table[key][0]) for key in used]
    base_size = len(blob.data)

    localized = []  # (key, name)
    for i, p in enumerate(entries):
        for li in range(len(LANGS)):
            name = alternates.get(p[0] * 4 + li)
            if name is not None and name != p[1]:
                localized.append(((i << 2) | li, name))
    for j, key in enumerate(used):
        base, gid = admin1_table[key]
        for li in range(len(LANGS)):
            name = alternates.get(gid * 4 + li)
            if name is not None and name != base and name != ascii_fold(base):
                localized.append((((n + j) << 2) | li, name))
    localized.sort(key=lambda r: r[0])
    localized_offsets = [(key, blob.add(name)) for key, name in localized]
    del localized
    if len(blob.data) >= 1 << 32:
        raise SystemExit("name blob too large for format v1")

    counts = [0] * (CELLS + 1)
    for c in cells:
        counts[c + 1] += 1
    for c in range(CELLS):
        counts[c + 1] += counts[c]
    del cells

    languages = b"".join(lang.encode("ascii") + b"\0\0" for lang in LANGS)
    cell_index = struct.pack(f"<{CELLS + 1}I", *counts)
    entry_bytes = b"".join(
        ENTRY.pack(p[2], p[3], cc_bytes(p[4]),
                   admin1_index[(p[4], p[5])] if has_admin1(p, admin1_table) else NO_ADMIN1, entry_names[i])
        for i, p in enumerate(entries))
    admin_bytes = b"".join(ADMIN.pack(cc_bytes(key[0]), 0, admin_names[j]) for j, key in enumerate(used))
    localized_bytes = b"".join(PAIR.pack(k, o) for k, o in localized_offsets)
    names = bytes(blob.data)

    languages_off = HEADER_SIZE
    cell_off = languages_off + len(languages)
    entries_off = cell_off + len(cell_index)
    admin_off = entries_off + len(entry_bytes)
    localized_off = admin_off + len(admin_bytes)
    names_off = localized_off + len(localized_bytes)
    for off in (languages_off, cell_off, entries_off, admin_off, localized_off, names_off):
        assert off % 4 == 0
    min_pop = int(level) if level.isdigit() else 0
    header = b"GLPL" + struct.pack("<HHIIIIIIIIIIIII", 1, 0, min_pop, date, n, len(used), len(localized_offsets),
                                   len(LANGS), languages_off, cell_off, entries_off, admin_off, localized_off,
                                   names_off, len(names)) + struct.pack("<I", 0)
    assert len(header) == HEADER_SIZE
    raw = header + languages + cell_index + entry_bytes + admin_bytes + localized_bytes + names

    # name blob bytes only referenced by localized records
    only_localized = sum(2 + len(t.encode("utf-8")) for t, off in blob.offsets.items() if off >= base_size)
    info = {
        "entries": n,
        "entriesDE": sum(1 for p in entries if p[4] == "DE"),
        "admin1": len(used),
        "localized": len(localized_offsets),
        "localizedShareBytes": len(localized_bytes) + only_localized,
    }
    return raw, info


def gzip_deterministic(raw: bytes) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as g:
        g.write(raw)
    return buf.getvalue()


def deflate(data: bytes) -> int:
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return len(c.compress(data) + c.flush())


def kotlin_underscored(number: int) -> str:
    return f"{number:,}".replace(",", "_")


def stats_text(counts, stats, levels) -> str:
    cols = ["entries", "entriesDE", "admin1", "localized", "localizedShareBytes", "rawSize", "gzSize", "xzSize",
            "apkDeflateOfRaw", "apkDeflateOfGz"]
    lines = ["class P: total %(p_total)d (DE %(p_total_de)d), population 0: %(p_pop0)d (DE %(p_pop0_de)d)" % counts,
             f"{'level':>9} " + " ".join(f"{c:>20}" for c in cols)]
    for level in levels:
        row = stats["levels"].get(level)
        if row:
            lines.append(f"{level:>9} " + " ".join(f"{row[c]:>20,}" for c in cols))
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Builds the offline place packs from GeoNames.")
    parser.add_argument("--work", default=os.path.join(os.path.expanduser("~"), ".cache", "glacier-geodata"))
    parser.add_argument("--out", help="output folder (default <work>/out)")
    parser.add_argument("--levels", default=DEFAULT_LEVELS,
                        help="comma separated: numbers, all, and the experimental all-plx")
    parser.add_argument("--assets", action="store_true",
                        help="also copy places-15000.bin.gz as places-15000.pack to app/src/main/assets/geodata/ (shipped in the APK)")
    parser.add_argument("--stats", action="store_true", help="also print and write stats.json")
    parser.add_argument("--stats-file", help="with --stats: rewrite this text file after every level")
    args = parser.parse_args()
    levels: list[str] = []
    for x in args.levels.split(","):
        x = x.strip()
        if x:
            x = x if x in (ALL, ALL_WITH_PLX) else str(int(x))
            if x not in levels:
                levels.append(x)
    work = os.path.abspath(args.work)
    out = os.path.abspath(args.out or os.path.join(work, "out"))
    os.makedirs(out, exist_ok=True)
    started = time.time()

    paths = fetch_all(work)
    date = data_date(paths["allCountries.zip"])
    log(f"data date {date}")
    log("reading allCountries")
    places, counts, per_country, adm1_names = read_places(paths["allCountries.zip"], levels)
    # The admin1 name: the name of the region's own GeoNames entry (class A, code ADM1), because the UTF-8 column
    # of admin1CodesASCII.txt holds the ASCII spelling ("Baden-Wurttemberg") and a long form ("State of Berlin").
    # The table value is only the fallback if the entry is not in the dump.
    admin1_table_raw = read_admin1(paths["admin1CodesASCII.txt"])
    admin1_table = {k: (adm1_names.get(gid, name), gid) for k, (name, gid) in admin1_table_raw.items()}
    admin1_ids = {gid for _, gid in admin1_table.values()}
    log(f"{len(places)} candidate places")

    wanted = {p[0] for p in places}
    for p in places:
        if has_admin1(p, admin1_table):
            wanted.add(admin1_table[(p[4], p[5])][1])
    log("reading alternate names")
    alternates = read_alternates(paths["alternateNamesV2.zip"], wanted, admin1_ids)
    del wanted
    log(f"{len(alternates)} localized names chosen")

    manifest_packs = []
    stats = {"dataDate": date, "classP": dict(counts), "classPPerCountry": dict(sorted(per_country.items())),
             "levels": {}}
    snippets = []
    for level in levels:
        raw, info = build_pack(level, places, admin1_table, alternates, date)
        gz = gzip_deterministic(raw)
        name = f"places-{level}.bin.gz"
        experimental = level == ALL_WITH_PLX
        target = os.path.join(out, "experimental") if experimental else out
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, name), "wb") as f:
            f.write(gz)
        sha = hashlib.sha256(gz).hexdigest()
        if args.assets and level == "15000":
            assets = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "geodata")
            os.makedirs(assets, exist_ok=True)
            # Same bytes, other name: the Android build would gunzip an asset ending in .gz.
            asset = name.replace(".bin.gz", ".pack")
            with open(os.path.join(assets, asset), "wb") as f:
                f.write(gz)
            log(f"copied {name} to {assets} as {asset}")
        if not experimental:
            number = 0 if level == ALL else int(level)
            manifest_packs.append({"level": level if level == ALL else number, "file": name,
                                   "entries": info["entries"], "rawSize": len(raw), "gzSize": len(gz),
                                   "sha256": sha})
            snippets.append(f'GeoPack(level = {kotlin_underscored(number)}, file = "{name}", sha256 = "{sha}", '
                            f'size = {kotlin_underscored(len(gz))}L),')
        log(f"level {level}: {info['entries']} entries, raw {len(raw)}, gz {len(gz)}")
        if args.stats:
            stats["levels"][level] = {
                **info,
                "rawSize": len(raw),
                "gzSize": len(gz),
                "xzSize": len(lzma.compress(raw, format=lzma.FORMAT_XZ, preset=9 | lzma.PRESET_EXTREME)),
                "apkDeflateOfRaw": deflate(raw),
                "apkDeflateOfGz": deflate(gz),
            }
            if args.stats_file:
                with open(args.stats_file, "w", encoding="utf-8", newline="\n") as f:
                    f.write(stats_text(counts, stats, levels) + "\n")
        del raw, gz

    manifest = {"formatVersion": 1, "dataDate": f"{date // 10000:04d}-{date // 100 % 100:02d}-{date % 100:02d}",
                "packs": manifest_packs}
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    if args.stats:
        with open(os.path.join(out, "stats.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(stats, f, indent=2)
            f.write("\n")
        print(stats_text(counts, stats, levels))

    print()
    for line in snippets:
        print(line)
    log(f"done in {time.time() - started:.0f} s")


if __name__ == "__main__":
    main()
