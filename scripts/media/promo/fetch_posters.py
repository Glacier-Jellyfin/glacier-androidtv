"""Download the Primary images (posters) of all movies and series of the demo server.

What:  Reads the library of the Glacier demo server (read only) and stores every
       poster as <slug>.jpg plus an index.json (title, official rating, type, kids flag).
How:   python scripts/media/promo/fetch_posters.py [--server http://localhost:8098]
                                                   [--out build/promo/cache/posters]
       Logs in as user "Nora" (no password), like the demo profile in the clips.
Output: build/promo/cache/posters/<slug>.jpg, index.json (movies, series) and collections.json (BoxSets)
        Kids titles are those with an official rating up to FSK-6 (the Kids profile maximum).
"""
import argparse
import json
import re
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
AUTH = 'MediaBrowser Client="Glacier Promo", Device="fetch_posters.py", DeviceId="glacier-promo-posters", Version="1"'
KIDS_MAX = 6


def slug(title: str) -> str:
    text = unicodedata.normalize("NFKD", title).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def request(server: str, path: str, token: str | None = None, body=None) -> bytes:
    header = AUTH + (f', Token="{token}"' if token else "")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(server + path, data=data, headers={"Authorization": header, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()


def rating_value(rating: str | None) -> int | None:
    match = re.search(r"(\d+)", rating or "")
    if match:
        return int(match.group(1))
    return 0 if rating and "0" in rating else None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", default="http://localhost:8098")
    parser.add_argument("--out", type=Path, default=ROOT / "build/promo/cache/posters")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    users = json.loads(request(args.server, "/Users/Public"))
    nora = next(user for user in users if user["Name"] == "Nora")
    login = json.loads(request(args.server, "/Users/AuthenticateByName", body={"Username": "Nora", "Pw": ""}))
    token = login["AccessToken"]
    query = urllib.parse.urlencode({
        "IncludeItemTypes": "Movie,Series", "Recursive": "true", "CollapseBoxSetItems": "false",
        "Fields": "OfficialRating", "SortBy": "SortName", "UserId": nora["Id"],
    })
    items = json.loads(request(args.server, "/Items?" + query, token))["Items"]

    # collections (BoxSet) appear in the library grid too; they go into collections.json, not index.json,
    # so the poster wall (posterwall.py) keeps its layout
    query = urllib.parse.urlencode({"IncludeItemTypes": "BoxSet", "Recursive": "true", "Fields": "OfficialRating",
                                    "SortBy": "SortName", "UserId": nora["Id"]})
    boxsets = json.loads(request(args.server, "/Items?" + query, token))["Items"]
    extra = []
    for item in boxsets:
        if "Primary" not in item.get("ImageTags", {}):
            continue
        name = slug(item["Name"])
        (args.out / f"{name}.jpg").write_bytes(request(args.server, f"/Items/{item['Id']}/Images/Primary?maxWidth=1000&quality=92", token))
        extra.append({"slug": name, "title": item["Name"], "rating": item.get("OfficialRating"), "type": item["Type"], "kids": False})
    (args.out / "collections.json").write_text(json.dumps(extra, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{len(extra)} collections")

    index = []
    for item in items:
        if "Primary" not in item.get("ImageTags", {}):
            continue
        name = slug(item["Name"])
        (args.out / f"{name}.jpg").write_bytes(request(args.server, f"/Items/{item['Id']}/Images/Primary?maxWidth=1000&quality=92", token))
        rating = item.get("OfficialRating")
        value = rating_value(rating)
        index.append({"slug": name, "title": item["Name"], "rating": rating, "type": item["Type"],
                      "kids": value is not None and value <= KIDS_MAX})
    (args.out / "index.json").write_text(json.dumps(index, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{len(index)} posters ({sum(e['kids'] for e in index)} kids) in {args.out}")


if __name__ == "__main__":
    main()
