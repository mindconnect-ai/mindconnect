import json, re, sys, zipfile
from xml.sax.saxutils import escape

EMU = 12700  # per point; slide is 16:9, 13.333 x 7.5 in
SLIDE_W, SLIDE_H = 12192000, 6858000
NS = ('xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
      'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
      'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"')
XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'

def runs(text, size, bold=False, color=None):
    out = []
    for tok in re.split(r'(\*\*.+?\*\*)', text):
        if not tok: continue
        b = bold or tok.startswith("**")
        if tok.startswith("**"): tok = tok[2:-2]
        fill = f'<a:solidFill><a:srgbClr val="{color}"/></a:solidFill>' if color else ""
        out.append(f'<a:r><a:rPr lang="en-US" sz="{size*100}"{" b=\"1\"" if b else ""} dirty="0">{fill}</a:rPr>'
                   f'<a:t>{escape(tok)}</a:t></a:r>')
    return "".join(out)

def textbox(sid, name, x, y, w, h, paras, anchor="t"):
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sid}" name="{name}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{w}" cy="{h}"/></a:xfrm>'
            f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>'
            f'<p:txBody><a:bodyPr wrap="square" anchor="{anchor}"><a:normAutofit/></a:bodyPr><a:lstStyle/>{paras}</p:txBody></p:sp>')

def para(text, size, bold=False, color=None, bullet=False, level=0, align=None):
    ppr = f'<a:pPr lvl="{level}"' + (f' algn="{align}"' if align else "") + ' marL="{0}" indent="{1}">'.format(342900 * (level + 1) if bullet else 0, -342900 if bullet else 0)
    ppr += ('<a:buChar char="&#8226;"/>' if bullet else '<a:buNone/>') + '</a:pPr>'
    return f'<a:p>{ppr}{runs(text, size, bold, color)}</a:p>'

def table_shape(sid, x, y, w, rows, header=True):
    cols = len(rows[0]); cw = w // cols; rh = 370840
    grid = "".join(f'<a:gridCol w="{cw}"/>' for _ in range(cols))
    trs = []
    for i, row in enumerate(rows):
        tcs = []
        for cell in row:
            hdr = header and i == 0
            fill = '<a:solidFill><a:srgbClr val="1F3864"/></a:solidFill>' if hdr else ('<a:solidFill><a:srgbClr val="F2F2F2"/></a:solidFill>' if i % 2 == 0 else "")
            tcs.append(f'<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>{para(str(cell), 14, hdr, "FFFFFF" if hdr else None)}</a:txBody>'
                       f'<a:tcPr marL="68580" marR="68580" marT="34290" marB="34290">{fill}</a:tcPr></a:tc>')
        trs.append(f'<a:tr h="{rh}">' + "".join(tcs) + '</a:tr>')
    return (f'<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="{sid}" name="Table {sid}"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp="1"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>'
            f'<p:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{w}" cy="{rh*len(rows)}"/></p:xfrm>'
            f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">'
            f'<a:tbl><a:tblPr firstRow="1" bandRow="1"/><a:tblGrid>{grid}</a:tblGrid>{"".join(trs)}</a:tbl></a:graphicData></a:graphic></p:graphicFrame>')


# ---------------------------------------------------------------- diagrams
# Boxes are shapes, arrows are connectors glued to the boxes' connection
# sites (stCxn/endCxn), so they follow when a box is moved in PowerPoint.
# Labels are separate text boxes: a connector cannot carry text.
DIAG_COLORS = {"ink": "1B2431", "muted": "5B6774", "accent": "D9741A", "actor": "DCE6F2",
               "soft": "E4EBF4", "rule": "B1B0B1", "band": "E5F3FA"}
SIDE_IDX = {"rect": {"top": 0, "left": 1, "bottom": 2, "right": 3},
            "ellipse": {"top": 0, "left": 2, "bottom": 4, "right": 6}}
PRESET = {"box": "roundRect", "rect": "rect", "ellipse": "ellipse", "diamond": "diamond"}

def diag_text(sid, name, x, y, w, h, lines, anchor="ctr", align="ctr", margin=45720):
    """lines: list of (text, size_pt, bold, color)"""
    ps = "".join(para(t, max(6, round(sz)), b, c, align=align) for t, sz, b, c in lines)
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sid}" name="{escape(name)}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr><a:xfrm><a:off x="{int(x)}" y="{int(y)}"/><a:ext cx="{int(w)}" cy="{int(h)}"/></a:xfrm>'
            f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>'
            f'<p:txBody><a:bodyPr wrap="square" lIns="{margin}" tIns="{margin}" rIns="{margin}" bIns="{margin}" anchor="{anchor}"><a:normAutofit/></a:bodyPr><a:lstStyle/>{ps}</p:txBody></p:sp>')

def diag_box(sid, name, x, y, w, h, preset, fill, line_color, line_w, dash, lines, hatched=False, anchor="ctr"):
    adj = '<a:avLst><a:gd name="adj" fmla="val 6000"/></a:avLst>' if preset == "roundRect" else "<a:avLst/>"
    if hatched:
        fill_xml = f'<a:pattFill prst="ltUpDiag"><a:fgClr><a:srgbClr val="{line_color}"/></a:fgClr><a:bgClr><a:srgbClr val="FFFFFF"/></a:bgClr></a:pattFill>'
    elif fill is None:
        fill_xml = "<a:noFill/>"
    else:
        fill_xml = f'<a:solidFill><a:srgbClr val="{fill}"/></a:solidFill>'
    ln = ("<a:ln><a:noFill/></a:ln>" if line_color is None else
          f'<a:ln w="{line_w}"><a:solidFill><a:srgbClr val="{line_color}"/></a:solidFill>' + (f'<a:prstDash val="{dash}"/>' if dash else "") + "</a:ln>")
    ps = "".join(para(t, max(6, round(sz)), b, c, align="ctr") for t, sz, b, c in lines) or "<a:p/>"
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sid}" name="{escape(name)}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr><a:xfrm><a:off x="{int(x)}" y="{int(y)}"/><a:ext cx="{int(w)}" cy="{int(h)}"/></a:xfrm>'
            f'<a:prstGeom prst="{preset}">{adj}</a:prstGeom>{fill_xml}{ln}</p:spPr>'
            f'<p:txBody><a:bodyPr wrap="square" lIns="45720" tIns="27432" rIns="45720" bIns="27432" anchor="{anchor}"><a:normAutofit/></a:bodyPr><a:lstStyle/>{ps}</p:txBody></p:sp>')

def diag_connector(sid, name, pts, start, end, color, width, dashed, both):
    """pts: axis-aligned polyline in EMU (2..6 points); start/end: (shape id, site idx)."""
    n = len(pts) - 1
    x0, y0 = pts[0]; xn, yn = pts[-1]
    cxn = f'<a:stCxn id="{start[0]}" idx="{start[1]}"/><a:endCxn id="{end[0]}" idx="{end[1]}"/>'
    if n == 1:
        w, h = abs(xn - x0), abs(yn - y0)
        xfrm = (f'<a:xfrm{" flipH=\"1\"" if xn < x0 else ""}{" flipV=\"1\"" if yn < y0 else ""}>'
                f'<a:off x="{int(min(x0, xn))}" y="{int(min(y0, yn))}"/><a:ext cx="{int(w)}" cy="{int(h)}"/></a:xfrm>')
        geom = '<a:prstGeom prst="straightConnector1"><a:avLst/></a:prstGeom>'
    else:
        best = _elbow(pts)
        if best is None:
            return diag_connector(sid, name, [pts[0], pts[-1]], start, end, color, width, dashed, both)
        rot, fh, fv, Wl, Hl, adj, preset = best
        cx, cy = (x0 + xn) / 2, (y0 + yn) / 2
        offx, offy = (min(x0, xn), min(y0, yn)) if rot == 0 else (cx - Wl / 2, cy - Hl / 2)
        attrs = (f' rot="{rot * 60000}"' if rot else "") + (' flipH="1"' if fh else "") + (' flipV="1"' if fv else "")
        xfrm = f'<a:xfrm{attrs}><a:off x="{int(offx)}" y="{int(offy)}"/><a:ext cx="{int(Wl)}" cy="{int(Hl)}"/></a:xfrm>'
        gds = "".join(f'<a:gd name="adj{i}" fmla="val {int(round(a * 100000))}"/>' for i, a in enumerate(adj, 1))
        geom = f'<a:prstGeom prst="{preset}"><a:avLst>{gds}</a:avLst></a:prstGeom>'
    ln = (f'<a:ln w="{width}"><a:solidFill><a:srgbClr val="{color}"/></a:solidFill>'
          + ('<a:prstDash val="dash"/>' if dashed else "")
          + ('<a:headEnd type="triangle" w="med" len="med"/>' if both else "")
          + '<a:tailEnd type="triangle" w="med" len="med"/></a:ln>')
    return (f'<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="{sid}" name="{escape(name)}"/><p:cNvCxnSpPr>{cxn}</p:cNvCxnSpPr><p:nvPr/></p:nvCxnSpPr>'
            f'<p:spPr>{xfrm}{geom}{ln}</p:spPr></p:cxnSp>')

def _elbow(P):
    """rot/flip/adjust values that make bentConnector3/4/5 follow the polyline P (EMU)."""
    n = len(P) - 1
    preset = {2: "bentConnector3", 3: "bentConnector3", 4: "bentConnector4", 5: "bentConnector5"}.get(n)
    if preset is None:
        return None
    x0, y0 = P[0]; xn, yn = P[-1]; cx, cy = (x0 + xn) / 2, (y0 + yn) / 2; eps = 1000.0
    for rot in (0, 90, 270):
        W_, H_ = (abs(xn - x0), abs(yn - y0)) if rot == 0 else (abs(yn - y0), abs(xn - x0))
        Wl = W_ if W_ > 1 else eps; Hl = H_ if H_ > 1 else eps
        for fh in (False, True):
            for fv in (False, True):
                loc = []
                for (px, py) in P:
                    dx, dy = px - cx, py - cy
                    lx, ly = (dx, dy) if rot == 0 else ((dy, -dx) if rot == 90 else (-dy, dx))
                    lx += W_ / 2; ly += H_ / 2
                    if fh: lx = W_ - lx
                    if fv: ly = H_ - ly
                    loc.append((lx, ly))
                if abs(loc[0][0]) > 2 or abs(loc[0][1]) > 2 or abs(loc[-1][0] - W_) > 2 or abs(loc[-1][1] - H_) > 2: continue
                if abs(loc[1][1] - loc[0][1]) > 2: continue
                if n in (2, 3): adj = [loc[1][0] / Wl]
                elif n == 4: adj = [loc[1][0] / Wl, loc[2][1] / Hl]
                else: adj = [loc[1][0] / Wl, loc[2][1] / Hl, loc[3][0] / Wl]
                return (rot, fh, fv, Wl, Hl, adj, preset)
    W_, H_ = abs(xn - x0), abs(yn - y0); Wl = W_ if W_ > 1 else eps; Hl = H_ if H_ > 1 else eps
    for fh in (False, True):
        for fv in (False, True):
            loc = [((px - x0) if not fh else (x0 - px), (py - y0) if not fv else (y0 - py)) for (px, py) in P]
            if abs(loc[-1][0] - W_) > 2 or abs(loc[-1][1] - H_) > 2: continue
            if n == 2 and abs(loc[1][0]) <= 2: return (0, fh, fv, Wl, Hl, [0.0], preset)
    return None

def _side_point(b, side, at=0.5):
    x, y, w, h = b["x"], b["y"], b["w"], b["h"]
    return {"top": (x + w * at, y), "bottom": (x + w * at, y + h), "left": (x, y + h * at), "right": (x + w, y + h * at)}[side]

def _auto_sides(a, b):
    acx, acy = a["x"] + a["w"] / 2, a["y"] + a["h"] / 2; bcx, bcy = b["x"] + b["w"] / 2, b["y"] + b["h"] / 2
    dx, dy = bcx - acx, bcy - acy
    if abs(dx) >= abs(dy): return ("right", "left") if dx > 0 else ("left", "right")
    return ("bottom", "top") if dy > 0 else ("top", "bottom")

def _route(a, b, arrow, orth):
    fs, ts = arrow.get("from_side"), arrow.get("to_side")
    if not fs or not ts:
        afs, ats = _auto_sides(a, b); fs = fs or afs; ts = ts or ats
    p0 = _side_point(a, fs, arrow.get("from_at", 0.5)); pn = _side_point(b, ts, arrow.get("to_at", 0.5))
    via = [tuple(v) for v in arrow.get("via", [])]
    horiz = lambda s: s in ("left", "right")
    if via:
        pts = [p0] + via + [pn]
    elif horiz(fs) and horiz(ts):
        if abs(p0[1] - pn[1]) < 1 and not orth: pts = [p0, pn]
        else: mx = (p0[0] + pn[0]) / 2; pts = [p0, (mx, p0[1]), (mx, pn[1]), pn]
    elif not horiz(fs) and not horiz(ts):
        if abs(p0[0] - pn[0]) < 1 and not orth: pts = [p0, pn]
        else: my = (p0[1] + pn[1]) / 2; pts = [p0, (p0[0], my), (pn[0], my), pn]
    elif horiz(fs): pts = [p0, (pn[0], p0[1]), pn]
    else: pts = [p0, (p0[0], pn[1]), pn]
    out = [pts[0]]
    for q in pts[1:]:
        p = out[-1]
        if abs(p[0] - q[0]) > 0.5 and abs(p[1] - q[1]) > 0.5: out.append((q[0], p[1]))
        out.append(q)
    if orth and len(out) == 4: return out, fs, ts
    clean = [out[0]]
    for q in out[1:]:
        if abs(q[0] - clean[-1][0]) > 0.5 or abs(q[1] - clean[-1][1]) > 0.5: clean.append(q)
    return clean, fs, ts

def diagram_shapes(slide, ax, ay, aw, ah):
    """Shapes of one diagram slide, drawn on the area (ax, ay, aw, ah) in EMU."""
    col = dict(DIAG_COLORS, **slide.get("colors", {}))
    cw, chh = slide.get("canvas", [1000, 600])
    sc = min(aw / cw, ah / chh); ox = ax + (aw - cw * sc) / 2; oy = ay + (ah - chh * sc) / 2
    X = lambda v: ox + v * sc; Y = lambda v: oy + v * sc; S = lambda v: v * sc
    PT = lambda px: max(7, round(px * sc / 12700 * 10) / 10)
    ts = slide.get("text_size", {}); T_TITLE, T_SUB, T_LBL = ts.get("title", 13), ts.get("sub", 11), ts.get("label", 11)
    orth_all = bool(slide.get("orthogonal", False))
    out, sid = [], 10
    def nid():
        nonlocal sid; sid += 1; return sid
    for l in slide.get("lanes", []):
        out.append(diag_box(nid(), "Lane: " + l.get("title", ""), X(l["x"]), Y(l["y"]), S(l["w"]), S(l["h"]), "roundRect",
                            l.get("color", col["band"]), None, 0, None, [], anchor="t"))
        if l.get("title"):
            out.append(diag_text(nid(), "Lane title: " + l["title"], X(l["x"]), Y(l["y"]), S(l["w"]), S(24), [(l["title"], PT(T_SUB), True, col["muted"])], "t", "l"))
    for f in slide.get("frames", []):
        out.append(diag_box(nid(), "Frame: " + f.get("title", ""), X(f["x"]), Y(f["y"]), S(f["w"]), S(f["h"]), "roundRect",
                            None, col["rule"], 12700, "dot" if f.get("style") == "dotted" else "dash", []))
        if f.get("title"):
            out.append(diag_text(nid(), "Frame title: " + f["title"], X(f["x"]), Y(f["y"]), S(f["w"]), S(24), [(f["title"], PT(T_SUB), True, col["muted"])], "t", "l"))
    boxes, box_ids = {}, {}
    for b in slide.get("boxes", []):
        style = b.get("style", "plain"); shape = b.get("shape", "box")
        fill = b.get("fill", {"actor": col["actor"], "soft": col["soft"]}.get(style, "FFFFFF"))
        line = b.get("line", col["accent"] if style == "accent" else col["ink"])
        lw = 28575 if style == "accent" else 12700
        dash = "dash" if style == "dashed" else None
        lines = [(b.get("title", ""), PT(T_TITLE), True, col["ink"])]
        if b.get("sub"): lines.append((b["sub"], PT(T_SUB), False, col["muted"]))
        i = nid(); boxes[b["id"]] = b; box_ids[b["id"]] = (i, "ellipse" if shape == "ellipse" else "rect")
        out.append(diag_box(i, "Box: " + b["id"], X(b["x"]), Y(b["y"]), S(b["w"]), S(b["h"]), PRESET.get(shape, "roundRect"),
                            fill, line, lw, dash, lines, hatched=style == "hatched", anchor="t" if b.get("text_top") else "ctr"))
    for a in slide.get("arrows", []):
        if a["from"] not in boxes or a["to"] not in boxes:
            raise ValueError(f"arrow {a['from']}->{a['to']} names a box that does not exist")
        pts, fs, tsd = _route(boxes[a["from"]], boxes[a["to"]], a, a.get("orthogonal", orth_all))
        emu = [(X(x), Y(y)) for x, y in pts]
        label = a.get("label", "")
        name = f"{a['from']}->{a['to']}" + (f" ({label})" if label else "")
        color = col["accent"] if a.get("accent") else col["ink"]
        s_id, s_kind = box_ids[a["from"]]; e_id, e_kind = box_ids[a["to"]]
        out.append(diag_connector(nid(), "Arrow: " + name, emu, (s_id, SIDE_IDX[s_kind][fs]), (e_id, SIDE_IDX[e_kind][tsd]),
                                  color, 28575 if a.get("accent") else 19050, a.get("dashed", False), a.get("both", False)))
        if label or a.get("number") is not None:
            i = max(range(len(pts) - 1), key=lambda k: abs(pts[k + 1][0] - pts[k][0]) + abs(pts[k + 1][1] - pts[k][1]))
            (p, q) = pts[i], pts[i + 1]; mx, my = (p[0] + q[0]) / 2, (p[1] + q[1]) / 2
            horizontal = abs(q[0] - p[0]) >= abs(q[1] - p[1])
            if a.get("number") is not None:
                r = 11
                nx, ny = (mx - r, my - r - 14) if horizontal else (mx - r - 14, my - r)
                out.append(diag_box(nid(), f"Number: {a['number']}", X(nx), Y(ny), S(2 * r), S(2 * r), "ellipse", col["accent"], None, 0, None,
                                    [(str(a["number"]), PT(T_LBL - 1), True, "FFFFFF")]))
            if label:
                lw_, lh_ = 120, 22; below = a.get("label_pos") == "below"; left = a.get("label_pos") == "left"
                if horizontal: lx, ly = mx - lw_ / 2, (my + 4) if below else (my - lh_ - 4)
                else: lx, ly = (mx - lw_ - 4) if left else (mx + 4), my - lh_ / 2
                out.append(diag_text(nid(), "Label: " + name, X(lx), Y(ly), S(lw_), S(lh_),
                                     [(label, PT(T_LBL), False, col["accent"] if a.get("accent") else col["muted"])],
                                     "b" if horizontal and not below else ("t" if horizontal else "ctr"),
                                     "ctr" if horizontal else ("r" if left else "l"), margin=0))
    for t in slide.get("texts", []):
        style = t.get("style", ""); anchor = t.get("anchor", "start"); w = S(t.get("w", 600))
        x = X(t["x"]) - (w / 2 if anchor == "middle" else w if anchor == "end" else 0)
        color = col["accent"] if "accent" in style else col["muted"] if "muted" in style else col["ink"]
        out.append(diag_text(nid(), "Text: " + t["text"][:30], x, Y(t["y"]) - S(10), w, S(24),
                             [(t["text"], PT(t.get("size", T_LBL)), "bold" in style, color)], "t",
                             {"start": "l", "middle": "ctr", "end": "r"}[anchor], margin=0))
    return out

def slide_xml(slide):
    kind = slide.get("type", "bullets")
    shapes = []
    M = 609600  # 0.67in margin
    if kind == "title":
        shapes.append(textbox(2, "Title", M, 2200000, SLIDE_W - 2*M, 1400000,
                              para(slide["title"], 44, True, "1F3864", align="ctr"), "b"))
        if slide.get("subtitle"):
            shapes.append(textbox(3, "Subtitle", M, 3700000, SLIDE_W - 2*M, 1000000,
                                  para(slide["subtitle"], 24, False, "595959", align="ctr")))
    else:
        shapes.append(textbox(2, "Title", M, 400000, SLIDE_W - 2*M, 900000,
                              para(slide["title"], 32, True, "1F3864"), "b"))
        top = 1500000; bw = SLIDE_W - 2*M
        if kind == "bullets":
            ps = "".join(para(t, 20, bullet=True, level=lvl) for t, lvl in bullets(slide.get("bullets", [])))
            shapes.append(textbox(3, "Body", M, top, bw, SLIDE_H - top - M, ps))
        elif kind == "two-column":
            half = (bw - 300000) // 2
            for i, key in enumerate(("left", "right")):
                col = slide.get(key, {})
                ps = para(col.get("heading", ""), 22, True, "2E74B5") if col.get("heading") else ""
                ps += "".join(para(t, 18, bullet=True, level=lvl) for t, lvl in bullets(col.get("bullets", [])))
                shapes.append(textbox(3 + i, key.capitalize(), M + i * (half + 300000), top, half, SLIDE_H - top - M, ps))
        elif kind == "table":
            shapes.append(table_shape(3, M, top, bw, slide["rows"], slide.get("header", True)))
        elif kind == "text":
            ps = "".join(para(t, 18) for t in slide.get("paragraphs", []))
            shapes.append(textbox(3, "Body", M, top, bw, SLIDE_H - top - M, ps))
        elif kind == "diagram":
            shapes += diagram_shapes(slide, M, top, bw, SLIDE_H - top - M)
        else:
            raise ValueError(f"unknown slide type {kind!r}")
    return (f'{XML}<p:sld {NS}><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
            f'<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
            f'{"".join(shapes)}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>')

def bullets(items):
    """strings, or {"text":..,"level":1} dicts, or nested lists for sub-bullets"""
    out = []
    def walk(xs, lvl):
        for x in xs:
            if isinstance(x, list): walk(x, lvl + 1)
            elif isinstance(x, dict): out.append((x["text"], x.get("level", lvl)))
            else: out.append((x, lvl))
    walk(items, 0)
    return out

def notes_xml(text):
    return (f'{XML}<p:notes {NS}><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
            '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
            '<p:sp><p:nvSpPr><p:cNvPr id="2" name="Notes"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>'
            f'<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>{para(text, 12)}</p:txBody></p:sp>'
            '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>')

THEME = (f'{XML}<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Plain"><a:themeElements>'
    '<a:clrScheme name="Plain"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F3864"/></a:dk2><a:lt2><a:srgbClr val="EEEEEE"/></a:lt2>'
    '<a:accent1><a:srgbClr val="2E74B5"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>'
    '<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>'
    '<a:fontScheme name="Plain"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>'
    '<a:fmtScheme name="Plain"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>'
    '<a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>'
    '<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>'
    '<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>'
    '</a:themeElements></a:theme>')
EMPTY_TREE = ('<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
              '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>')
CLRMAP = '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>'
TXSTYLES = ('<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="3200"/></a:lvl1pPr></p:titleStyle>'
            '<p:bodyStyle><a:lvl1pPr><a:defRPr sz="2000"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles>')
MASTER = f'{XML}<p:sldMaster {NS}>{EMPTY_TREE}{CLRMAP}<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>{TXSTYLES}</p:sldMaster>'
LAYOUT = f'{XML}<p:sldLayout {NS} type="blank" preserve="1">{EMPTY_TREE}<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>'
NOTES_MASTER = f'{XML}<p:notesMaster {NS}>{EMPTY_TREE}{CLRMAP}</p:notesMaster>'
REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/'
def rels(pairs):
    return (f'{XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            + "".join(f'<Relationship Id="rId{i+1}" Type="{REL}{t}" Target="{tg}"/>' for i, (t, tg) in enumerate(pairs)) + '</Relationships>')

def build(spec, path):
    slides = spec["slides"]; n = len(slides)
    has_notes = any(s.get("notes") for s in slides)
    ct = [f'{XML}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">',
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>',
          '<Default Extension="xml" ContentType="application/xml"/>',
          '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>',
          '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>',
          '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>',
          '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>',
          '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>']
    if has_notes:
        ct.append('<Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>')
    files = {}
    for i, s in enumerate(slides, 1):
        ct.append(f'<Override PartName="/ppt/slides/slide{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>')
        files[f"ppt/slides/slide{i}.xml"] = slide_xml(s)
        srels = [("slideLayout", "../slideLayouts/slideLayout1.xml")]
        if s.get("notes"):
            ct.append(f'<Override PartName="/ppt/notesSlides/notesSlide{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"/>')
            files[f"ppt/notesSlides/notesSlide{i}.xml"] = notes_xml(s["notes"])
            files[f"ppt/notesSlides/_rels/notesSlide{i}.xml.rels"] = rels([("notesMaster", "../notesMasters/notesMaster1.xml"), ("slide", f"../slides/slide{i}.xml")])
            srels.append(("notesSlide", f"../notesSlides/notesSlide{i}.xml"))
        files[f"ppt/slides/_rels/slide{i}.xml.rels"] = rels(srels)
    ct.append('</Types>')
    pres_rels = [("slideMaster", "slideMasters/slideMaster1.xml")] + [("slide", f"slides/slide{i}.xml") for i in range(1, n+1)] + [("theme", "theme/theme1.xml")]
    if has_notes: pres_rels.append(("notesMaster", "notesMasters/notesMaster1.xml"))
    sld_ids = "".join(f'<p:sldId id="{256+i}" r:id="rId{1+i}"/>' for i in range(1, n+1))
    notes_lst = f'<p:notesMasterIdLst><p:notesMasterId r:id="rId{len(pres_rels)}"/></p:notesMasterIdLst>' if has_notes else ""
    files["ppt/presentation.xml"] = (f'{XML}<p:presentation {NS} saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
        f'{notes_lst}<p:sldIdLst>{sld_ids}</p:sldIdLst><p:sldSz cx="{SLIDE_W}" cy="{SLIDE_H}"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>')
    files["ppt/_rels/presentation.xml.rels"] = rels(pres_rels)
    files["ppt/slideMasters/slideMaster1.xml"] = MASTER
    files["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = rels([("slideLayout", "../slideLayouts/slideLayout1.xml"), ("theme", "../theme/theme1.xml")])
    files["ppt/slideLayouts/slideLayout1.xml"] = LAYOUT
    files["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = rels([("slideMaster", "../slideMasters/slideMaster1.xml")])
    files["ppt/theme/theme1.xml"] = THEME
    if has_notes:
        files["ppt/notesMasters/notesMaster1.xml"] = NOTES_MASTER
        files["ppt/notesMasters/_rels/notesMaster1.xml.rels"] = rels([("theme", "../theme/theme1.xml")])
    files["docProps/core.xml"] = (f'{XML}<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
        f'<dc:title>{escape(spec.get("title", ""))}</dc:title><dc:creator>{escape(spec.get("author", ""))}</dc:creator></cp:coreProperties>')
    files["_rels/.rels"] = rels([("officeDocument", "ppt/presentation.xml")]).replace(
        "</Relationships>", '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>')
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", "".join(ct))
        for name, content in files.items(): z.writestr(name, content)
    print(f"wrote {path}: {n} slides")
