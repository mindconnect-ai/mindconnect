"""Builds one file per generator from the skills' example specs and checks the
result is a readable Office package — run inside the image by CI."""
import sys
import tempfile
import zipfile
from pathlib import Path

from mc_office import docx_builder, pptx_builder, xlsx_builder

out = Path(sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp())

deck = {
    "title": "Roadmap 2027", "author": "Mindconnect",
    "slides": [
        {"type": "title", "title": "Roadmap 2027", "subtitle": "Where we go next", "notes": "Open with the numbers."},
        {"type": "bullets", "title": "Where we stand",
         "bullets": ["**Revenue** up 12 %", "Costs flat", ["Cloud spend down", "Hiring paused"]]},
        {"type": "two-column", "title": "Options",
         "left": {"heading": "Build", "bullets": ["Own stack", "Slow"]},
         "right": {"heading": "Buy", "bullets": ["Faster", "Lock-in"]}},
        {"type": "table", "title": "Figures", "rows": [["Quarter", "Revenue"], ["Q1", "1.2 M"]]},
        {"type": "text", "title": "Closing", "paragraphs": ["Decide by March."]},
        {"type": "diagram", "title": "Data flow", "orthogonal": True,
         "boxes": [{"id": "a", "x": 40, "y": 120, "w": 140, "h": 52, "title": "Sensor", "style": "actor"},
                   {"id": "b", "x": 360, "y": 120, "w": 140, "h": 52, "title": "Gateway"}],
         "arrows": [{"from": "a", "to": "b", "label": "MQTT", "number": 1}]},
    ],
}
document = {
    "title": "Quarterly Report", "author": "Mindconnect",
    "blocks": [
        {"type": "heading", "level": 1, "text": "Summary"},
        {"type": "paragraph", "text": "Revenue grew **12 %** while costs stayed *flat*."},
        {"type": "bullets", "items": ["First point", "Second point"]},
        {"type": "numbered", "items": ["Step one", "Step two"]},
        {"type": "table", "rows": [["Quarter", "Revenue"], ["Q1", "1.2 M"]], "header": True},
        {"type": "page_break"},
    ],
}
workbook = {
    "title": "Sales 2026", "author": "Mindconnect",
    "sheets": [
        {"name": "Revenue", "autofilter": True,
         "columns": [{"header": "Quarter", "width": 12}, {"header": "Revenue", "width": 14, "format": "#,##0.00"}],
         "rows": [["Q1", 1200000], ["Q2", 1400000.5], [{"value": "Total", "bold": True}, "=SUM(B2:B3)"]]},
    ],
}

for builder, spec, name, part in [
    (pptx_builder, deck, "deck.pptx", "ppt/presentation.xml"),
    (docx_builder, document, "document.docx", "word/document.xml"),
    (xlsx_builder, workbook, "workbook.xlsx", "xl/workbook.xml"),
]:
    path = out / name
    builder.build(spec, str(path))
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None, f"{name}: corrupt zip"
        assert part in z.namelist(), f"{name}: {part} missing"

# The libraries the image promises for everything the generators do not cover.
if "--libraries" in sys.argv:
    import docx, matplotlib, openpyxl, pandas, pptx  # noqa: F401
    print("libraries: python-docx, matplotlib, openpyxl, pandas, python-pptx")
print("ok")
