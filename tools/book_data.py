"""Put the Aces & Eclipses Core Rulebook into the app.

Usage: python3 tools/book_data.py docs/index.html "Aces & Eclipses Core Rulebook.md"

Reads the rulebook's Markdown and rewrites two things in the page:
- the "ae" book in the rules data (the Rules tab), one section per chapter or appendix;
- the names and effects in the wild magic table and the Oddities, from chapter 14.
The d20 Classic books in the rules data are left as they are.
"""
import json
import re
import sys
from markdown_it import MarkdownIt

PAGE, BOOK = sys.argv[1], sys.argv[2]
md = MarkdownIt("commonmark", {"typographer": False}).enable("table")


def slug(t):
    return re.sub(r"[^a-z0-9]+", "-", t.lower()).strip("-")


def to_json(obj):
    # '<' is escaped so the data can never close its <script> tag early.
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")


# ---- split the book into sections ------------------------------------------------------------
lines = open(BOOK, encoding="utf-8").read().split("\n")
title = lines[0].lstrip("# ").strip()
sections, cur, part, lead = [], None, None, []
for ln in lines[1:]:
    if ln.startswith("# "):
        h = ln[2:].strip()
        m = re.match(r"Part (\w+): (.+)", h)
        part = f"Part {m.group(1)} · {m.group(2)}" if m else h
        cur = None
        continue
    if ln.startswith("## "):
        t = ln[3:].strip()
        cur = {"title": t, "part": part, "md": []}
        part = None
        sections.append(cur)
        continue
    if cur is None:
        # the release line under the title, or a part's italic blurb: kept only before the first section
        if not sections:
            lead.append(ln)
        continue
    cur["md"].append(ln)

out = []
for i, s in enumerate(sections):
    sid = "ae-" + slug(s["title"])
    body = "\n".join((lead if i == 0 else []) + s["md"]).strip() + "\n"
    html = f'<h2 id="{sid}-{slug(s["title"])}">{s["title"].replace("&", "&amp;")}</h2>\n' + md.render(body)

    def hid(m):
        text = re.sub(r"<[^>]+>", "", m.group(2))
        return f'<h{m.group(1)} id="{sid}-{slug(text)}">{m.group(2)}</h{m.group(1)}>'

    html = re.sub(r"<h([34])>(.*?)</h\1>", hid, html)
    html = html.replace("<table>", '<div class="tablewrap"><table>').replace("</table>", "</table></div>")
    if i == 0 and any(x.strip() for x in lead):
        # the release line under the book's title: small, and no drop cap on it
        html = html.replace("</h2>\n<p><em>", '</h2>\n<p class="edition"><em>', 1)
    sec = {"id": sid, "title": s["title"], "html": html}
    if s["part"]:
        sec["part"] = s["part"]
    out.append(sec)

page = open(PAGE, encoding="utf-8").read()
m = re.search(r'(<script type="application/json" id="rules-data">)(.*?)(</script>)', page, re.S)
data = json.loads(m.group(2))
data.pop("paizo", None)
data = {"ae": {"core": {"title": title, "sections": out}}, **{k: v for k, v in data.items() if k != "ae"}}
page = page[: m.start(2)] + to_json(data) + page[m.end(2):]

# ---- wild magic: names and effects from chapter 14 ------------------------------------------
book = "\n".join(lines)
rows = {}
for r in re.finditer(r"^\| (\d+) \| ([^|]+) \| ([^|]+?) \| (?:#(\d+))? ?\| \*\*(.+?)\.\*\* (.+?) \|$", book, re.M):
    rows[int(r.group(1))] = (r.group(3).strip(), int(r.group(4)) if r.group(4) else None, r.group(5), r.group(6).strip())
if len(rows) != 93:
    sys.exit(f"found {len(rows)} wild magic rows, expected 93")
odd = re.findall(r"^\| (A|\d+) \| (.+?) \|$", book.split("### Oddities")[1].split("\n## ")[0], re.M)
if len(odd) != 10:
    sys.exit(f"found {len(odd)} oddities, expected 10")

m = re.search(r"^const WILD = (\{.*\});$", page, re.M)
wild = json.loads(m.group(1))
for row in wild["WM"]:
    res, opp, name, effect = rows[row[0]]
    if res != row[2] or (opp is not None and opp != row[5]):
        sys.exit(f"#{row[0]}: the book says {res} / #{opp}, the app has {row[2]} / #{row[5]}")
    row[6], row[7] = name, effect
wild["ODD"] = [[k, v] for k, v in odd]
page = page[: m.start(1)] + json.dumps(wild, ensure_ascii=False, separators=(",", ":")) + page[m.end(1):]

open(PAGE, "w", encoding="utf-8").write(page)
print(f"{title}: {len(out)} sections; wild magic: 93 effects, 10 oddities")
