#!/usr/bin/env python3
"""Builds compact geoip.dat / geosite.dat for the app from the sources named in assets/regions.json.

Categories are found at the source by the hash of their name and written under their local names (see regions.json).

The full files are tens of MB; the app only needs the categories its regions use (and a few common
ones), so the selected protobuf entries are copied verbatim into much smaller files under
app/src/main/assets. Entries from several sources are concatenated (the first source wins a category).

Usage: python3 scripts/build_geo.py [--cache DIR]
"""
import argparse
import hashlib
import json
import os
import time
import urllib.request


def varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return r, i


def entries(b):
    """Yields (country_code, raw_bytes_including_tag) for each top-level entry."""
    i = 0
    while i < len(b):
        start = i
        key, i = varint(b, i)
        if key & 7 != 2:
            raise ValueError("unexpected wire type")
        length, i = varint(b, i)
        body = b[i:i + length]
        i += length
        # field 1 of the entry is the country code
        _, j = varint(body, 0)
        code_len, j = varint(body, j)
        yield body[j:j + code_len].decode(), b[start:i]


def name_hash(code):
    """How regions.json names a source category: SHA-256 of its upper-case name, first 16 hex digits."""
    return hashlib.sha256(code.upper().encode()).hexdigest()[:16]


def encode_varint(v):
    out = bytearray()
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)
    return bytes(out)


def renamed(raw, code):
    """The entry with its category name replaced by code (the rest byte for byte)."""
    _, i = varint(raw, 0)
    _, i = varint(raw, i)
    _, i = varint(raw, i)
    n, i = varint(raw, i)
    rest = raw[i + n:]
    name = code.encode()
    body = b"\x0a" + encode_varint(len(name)) + name + rest
    return b"\x0a" + encode_varint(len(body)) + body


def pick(src, keep, out, found):
    """Appends the entries whose name hash is in keep (hash -> local name), renamed, unless taken already."""
    data = open(src, "rb").read()
    for code, raw in entries(data):
        local = keep.get(name_hash(code))
        if local and local.upper() not in found:
            out += renamed(raw, local.upper())
            found.add(local.upper())


def wanted(regions):
    """(source URL, geoip {hash: local}, geosite {hash: local}) for the common lists and every region, without the
    notBundled categories (they change daily; the app downloads them itself)."""
    parts = [regions["common"]] + [r["geo"] for r in regions.get("regions", []) if "geo" in r]
    out = []
    for p in parts:
        skip = {c.upper() for c in p.get("notBundled", [])}
        ip = {h: local for local, h in p.get("geoip", {}).items() if local.upper() not in skip}
        site = {h: local for local, h in p.get("geosite", {}).items() if local.upper() not in skip}
        out.append((p["source"], ip, site))
    return out


def fetch(cache, source, name):
    folder = os.path.join(cache, hashlib.sha1(source.encode()).hexdigest()[:12])
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name)
    if not os.path.exists(path):
        print(f"downloading {source}{name}...")
        urllib.request.urlretrieve(source + name, path)
    return path


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.join(root, "build", "geo-cache"))
    args = ap.parse_args()
    assets = os.path.join(root, "app", "src", "main", "assets")
    regions = json.load(open(os.path.join(assets, "regions.json"), encoding="utf-8"))
    parts = wanted(regions)
    # What the bundled lists were built from: the app checks for newer ones by these hashes (GeoLists).
    stamp = {"updatedAt": int(time.time() * 1000), "sha": {}}
    for name, index in (("geoip.dat", 1), ("geosite.dat", 2)):
        out, found, keep_all = bytearray(), set(), set()
        for part in parts:
            keep = part[index]
            if not keep:
                continue
            keep_all |= {v.upper() for v in keep.values()}
            path = fetch(args.cache, part[0], name)
            stamp["sha"][part[0] + name] = sha256(path)
            pick(path, keep, out, found)
        missing = keep_all - found
        if missing:
            print(f"warning: {name} lacks {sorted(missing)}")
        open(os.path.join(assets, name), "wb").write(out)
        print(f"{name}: {len(out) / 1e6:.1f} MB, {len(found)} categories")
    json.dump(stamp, open(os.path.join(assets, "geo.json"), "w"), indent=2)


if __name__ == "__main__":
    main()
