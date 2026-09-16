---
name: pptx-builder
description: Use when the user wants a PowerPoint presentation (.pptx) — a slide deck with title, bullet, two-column, table and text slides, speaker notes, or diagram slides with boxes and arrows that stay connected when moved
tools: code_execute, bash
---
# Building a PowerPoint deck

A deck is written by `mc_office.pptx_builder`, which the `code_execute` python
image carries: you describe the slides as a spec, it writes the `.pptx`. It
produces a clean 16:9 deck: dark-blue titles, Calibri, one blank layout, real
tables, speaker notes where you give them, and **diagram slides whose arrows
are real connectors** — they snap to the boxes and follow when someone drags a
box in PowerPoint.

## 1. Plan the deck first

Decide the slides before generating. One message per slide; at most six
bullets of one line each; a table no wider than five columns and no longer
than eight rows. A title slide first, and a closing slide that says what
happens next. Speaker notes are where the sentences go — the slide carries
the headline, the notes carry the argument.

## Where the file lands

`code_execute` works in the chat's working directory, mounted under its real
path; its description names it. Give `path` relative to it (or as that full
path) and the file is the user's the moment the call returns — they find it
in the chat's Files dialog. Tell them the full host path. Do not write to
`/mnt/host`; that mount, where there is one, is read-only.

## 2. The spec

```python
spec = {
  "path": "roadmap.pptx",  # relative: lands in the chat's working directory
  "title": "Roadmap 2027",             # document property
  "author": "Mindconnect",
  "slides": [
    {"type": "title", "title": "Roadmap 2027", "subtitle": "Where we go next", "notes": "Open with the numbers."},
    {"type": "bullets", "title": "Where we stand",
     "bullets": ["**Revenue** up 12 %", "Costs flat", ["Cloud spend down", "Hiring paused"], "Churn below 2 %"]},
    {"type": "two-column", "title": "Options",
     "left": {"heading": "Build", "bullets": ["Own stack", "Slow"]},
     "right": {"heading": "Buy", "bullets": ["Faster", "Lock-in"]}},
    {"type": "table", "title": "Figures", "rows": [["Quarter", "Revenue", "Costs"], ["Q1", "1.2 M", "0.9 M"]]},
    {"type": "text", "title": "Closing", "paragraphs": ["Decide by March.", "Then we build."]},
  ]
}
```

Slide types: `title`, `bullets` (a nested list is one level of sub-bullets),
`two-column`, `table` (first row is the header), `text`, and `diagram`
(below). Any slide may carry `"notes"`. `**bold**` works inside any text;
nothing else is interpreted.

### Diagram slides

Architecture, data flow, process, organisation: boxes with arrows between
them. Every element is its own shape, named for PowerPoint's selection pane
(`Box: gw`, `Arrow: gw->api (HTTPS)`, `Label: gw->api (HTTPS)`), and every
arrow is a **connector glued to the boxes' connection points** — move a box
in PowerPoint and the arrows re-route themselves.

```python
{"type": "diagram", "title": "Data flow: sensor to dashboard",
 "canvas": [1000, 560],                 # virtual drawing area, scaled onto the slide
 "orthogonal": True,                    # arrows as elbow connectors: stay right-angled when boxes move
 "frames": [{"x": 330, "y": 40, "w": 640, "h": 460, "title": "Cloud"}],      # dashed outline, no fill
 "lanes":  [],                                                                 # filled bands for columns/rows
 "boxes": [
   {"id": "sensor", "x": 40,  "y": 120, "w": 140, "h": 52, "title": "Sensor", "sub": "field device", "style": "actor"},
   {"id": "gw",     "x": 360, "y": 120, "w": 140, "h": 52, "title": "Gateway", "sub": "MQTT broker"},
   {"id": "api",    "x": 580, "y": 120, "w": 140, "h": 52, "title": "Cloud API", "sub": "checks token", "style": "accent"},
   {"id": "db",     "x": 800, "y": 120, "w": 140, "h": 52, "title": "Database", "shape": "ellipse"},
   {"id": "dash",   "x": 580, "y": 400, "w": 140, "h": 52, "title": "Dashboard"},
   {"id": "ok",     "x": 360, "y": 400, "w": 140, "h": 52, "title": "Allowed?", "shape": "diamond"},
 ],
 "arrows": [
   {"from": "sensor", "to": "gw",  "label": "MQTT",  "number": 1},
   {"from": "gw",     "to": "api", "label": "HTTPS", "number": 2},
   {"from": "api",    "to": "db",  "label": "writes", "accent": True},
   {"from": "dash",   "to": "db",  "label": "reads", "from_side": "top", "to_side": "bottom", "via": [[650, 240], [870, 240]]},
   {"from": "ok",     "to": "dash", "label": "yes", "dashed": True, "both": True},
 ],
 "texts": [{"x": 40, "y": 540, "text": "Tinted: outside the system.  Amber: checkpoint.", "style": "muted"}],
 "notes": "What the diagram shows, in sentences."}
```

| Field | Meaning |
|-------|---------|
| `canvas` | width and height of the virtual drawing area; default `[1000, 600]`. Coordinates below are in these units, scaled proportionally onto the slide |
| `boxes` | `id` unique. `shape`: `box` (rounded, default), `rect`, `ellipse`, `diamond`. `style`: `plain`, `accent` (amber border, for the step that matters), `actor` (tinted, people and external systems), `soft` (lightly tinted, artefacts), `hatched` (somebody else's responsibility), `dashed` (open questions). `fill`/`line` override colours (hex, no `#`). `text_top: True` when another box sits inside |
| `arrows` | `from`/`to` are box ids. Sides are chosen from the boxes' positions (right→left, bottom→top) unless `from_side`/`to_side` (`top`, `left`, `bottom`, `right`) say otherwise; `from_at`/`to_at` (0–1) slide the anchor along the side. `via` is a list of waypoints for a detour around other boxes; missing corners are added. `label` sits at the longest segment (`label_pos`: `below` or `left` to flip it), `number` draws a numbered circle, `dashed`, `accent` and `both` (arrowheads at both ends) are what they say |
| `frames` / `lanes` | rectangles with a title top-left: a frame is a dashed outline, a lane a filled band (`color`). Arrows do not connect to them |
| `texts` | free text: `x`, `y`, `style` containing `muted`, `bold` or `accent`, `anchor` `start`/`middle`/`end` relative to `x` |
| `orthogonal` | `True` makes every arrow an elbow connector, also the straight ones — they look the same but stay right-angled when a box is dragged. Also settable per arrow |

Layout rules that work: boxes 140 × 52, columns 60 apart (room for a short
label between two boxes), rows 30–40 apart. Put connected boxes next to each
other so arrows do not cross other boxes; an unavoidable crossing beats a
detour across the slide. One accent colour, used sparingly. Two or three
words on an arrow, the explanation in the notes. Arrows with more than five
segments cannot be connectors; the generator falls back to a straight line.
Labels are separate text boxes and do **not** move with a box — say so when
handing the file over.

## 3. Run it

One `code_execute` call, language `python`, with the spec and two lines — the
generator is part of the image as `mc_office.pptx_builder`, so never paste or
rewrite it:

```python
from mc_office import pptx_builder
spec = { ... }          # your spec here
pptx_builder.build(spec, spec["path"])
```

It prints `wrote <path>: N slides` on success.

If the call fails with `ModuleNotFoundError: No module named 'mc_office'`, the
installation runs `code_execute` on a plain python image. Say so: the operator
sets `mindconnect.code-exec.languages` back to the default image
(`ghcr.io/mindconnect-ai/code-exec-python`). Offer the content as Markdown
meanwhile; do not pretend a file was delivered, and do not hand-write the
.pptx format.

## 4. Check and report

After the call, confirm the file exists and is not empty
(`import os; print(os.path.getsize(path))` in a second call, or `ls -l` via
`bash` on the host path) and tell the user the path and the slide titles. If
the run fails, read the traceback: a `KeyError` or `ValueError` names the
slide that is wrong in the spec. Rendering is not possible here, so ask the
user to page through the deck once in PowerPoint.
