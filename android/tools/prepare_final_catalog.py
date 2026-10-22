"""Read the approved master workbook; generate the bundled catalogue (not an XLSX edit).

Usage: python tools/prepare_final_catalog.py path/to/final.xlsx
2026-10-08 owner decisions: 18% GST, Ivory/Coffee Brown grout Rs130,
unpriced rows remain drafts. Exact duplicate product identities are coalesced.
"""
import collections
import hashlib
import json
import sys
from decimal import Decimal
from pathlib import Path

import openpyxl


def text(value):
    return "" if value is None else str(value).strip()


def number(value):
    value = text(value)
    if not value:
        return ""
    amount = Decimal(value)
    assert amount.is_finite() and amount >= 0, value
    assert amount * 100 == (amount * 100).to_integral_value(), value
    return format(amount, "f")


def prepare(path):
    workbook = openpyxl.load_workbook(path, data_only=True)
    rows = collections.OrderedDict()
    unpriced = 0
    duplicates = 0
    for rownum, row in enumerate(workbook["Catalog"].values, 1):
        if rownum == 1 or not row[3]:
            continue
        name = text(row[3])
        selling = number(row[6])
        if not selling or Decimal(selling) == 0:
            selling = ""
            unpriced += 1
        if name.casefold() in {"grout – ivory", "grout – coffee brown"}:
            selling = "130"
        entry = dict(category=text(row[1]) or "General", type=text(row[2]) or "General",
                     name=name, size=text(row[4]), unit=text(row[5]) or "Piece",
                     selling=selling, purchase=number(row[7]), gst="18",
                     stock=number(row[9]), minimum=number(row[10]),
                     sourceRows=[rownum], sourceIds=[text(row[0])], notes=text(row[11]))
        key = tuple(" ".join(entry[k].lower().split()) for k in ("category", "type", "name", "size", "unit"))
        entry["id"] = "final-" + hashlib.sha256("\x1f".join(key).encode()).hexdigest()[:32]
        if key in rows:
            old = rows[key]
            assert old["selling"] == selling, (rownum, name, old["selling"], selling)
            for field in ("purchase", "stock", "minimum"):
                assert not old[field] or not entry[field] or Decimal(old[field]) == Decimal(entry[field]), (name, field)
                old[field] = old[field] or entry[field]
            old["sourceRows"] += entry["sourceRows"]
            old["sourceIds"] += entry["sourceIds"]
            duplicates += 1
        else:
            rows[key] = entry
    assert unpriced == 240, unpriced
    assert sum(not r["selling"] for r in rows.values()) == 240
    output = Path(__file__).resolve().parents[1] / "app/src/main/assets/final_catalog.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(list(rows.values()), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(entries=len(rows), products=sum(bool(r["selling"]) for r in rows.values()),
                          pending=240, duplicates=duplicates, source_sha256=hashlib.sha256(path.read_bytes()).hexdigest())))


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))
