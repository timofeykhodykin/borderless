#!/usr/bin/env python3
"""
One source of texts for both platforms (CLAUDE.md "Platforms in sync"): turns the Android app's strings.xml of every
language into Apple string tables for the Mac app, under the same key names.

    python3 -I scripts/mac_strings.py <out dir>     # writes <out>/<lang>.lproj/Shared.strings and Shared.stringsdict

Run by macos/build.sh; the output is build output (not committed). Placeholders: %s / %1$s -> %@ / %1$@, %d -> %ld
(64-bit integers from Swift), %% stays. Plurals become .stringsdict entries.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "src", "main", "res")


def unescape_android(text):
    """Android resource text (already XML-decoded) -> plain text."""
    out, i = [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            n = text[i + 1]
            # Android also takes \uXXXX escapes (strings.xml's \u0020 keeps a leading space alive);
            # without this the escape's letters go out raw ("serversu0020(through…", owner 2026-11-10).
            if n == "u" and i + 5 < len(text):
                try:
                    out.append(chr(int(text[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
            out.append({"n": "\n", "t": "\t", "'": "'", '"': '"', "\\": "\\", "@": "@", "?": "?"}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    s = "".join(out)
    # Unescaped double quotes delimit literal whitespace in Android; the texts here don't rely on it.
    return s.replace('"', "") if s.count('"') and not text.count('\\"') else s


def apple_format(s):
    s = re.sub(r"%(\d+\$)?s", lambda m: "%" + (m.group(1) or "") + "@", s)
    s = re.sub(r"%(\d+\$)?d", lambda m: "%" + (m.group(1) or "") + "ld", s)
    return s


def strings_literal(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t") + '"'


def text_of(el):
    # Inline markup (rare) is flattened to its text.
    return "".join(el.itertext())


def convert(lang_dir, out_dir):
    tree = ET.parse(os.path.join(RES, lang_dir, "strings.xml"))
    strings, plurals = [], []
    for el in tree.getroot():
        name = el.get("name")
        if el.get("translatable") == "false" and lang_dir != "values":
            continue
        if el.tag == "string":
            strings.append((name, apple_format(unescape_android(text_of(el)))))
        elif el.tag == "plurals":
            items = {it.get("quantity"): apple_format(unescape_android(text_of(it))) for it in el.findall("item")}
            plurals.append((name, items))
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "Shared.strings"), "w", encoding="utf-8") as f:
        f.write("/* Generated from android/src/main/res/%s/strings.xml by scripts/mac_strings.py — do not edit. */\n" % lang_dir)
        for name, value in strings:
            f.write("%s = %s;\n" % (strings_literal(name), strings_literal(value)))
    with open(os.path.join(out_dir, "Shared.stringsdict"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
                '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0">\n<dict>\n')
        for name, items in plurals:
            # The count is the first integer placeholder; the variable stands in for the whole phrase.
            f.write("<key>%s</key><dict>\n<key>NSStringLocalizedFormatKey</key><string>%%#@n@</string>\n" % escape(name))
            f.write("<key>n</key><dict><key>NSStringFormatSpecTypeKey</key><string>NSStringPluralRuleType</string>"
                    "<key>NSStringFormatValueTypeKey</key><string>ld</string>\n")
            for q in ["zero", "one", "two", "few", "many", "other"]:
                if q in items:
                    f.write("<key>%s</key><string>%s</string>\n" % (q, escape(items[q])))
            f.write("</dict></dict>\n")
        f.write("</dict>\n</plist>\n")
    return len(strings), len(plurals)


def main():
    out = sys.argv[1]
    for d in sorted(os.listdir(RES)):
        if d == "values" or (d.startswith("values-") and os.path.isfile(os.path.join(RES, d, "strings.xml"))):
            lang = "en" if d == "values" else d[len("values-"):]
            n, p = convert(d, os.path.join(out, lang + ".lproj"))
            print("%s: %d strings, %d plurals" % (lang, n, p))


if __name__ == "__main__":
    main()
