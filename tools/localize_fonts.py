"""Copy the app's Google Fonts into the Android app, so its type looks right with no internet.

Usage: python3 tools/localize_fonts.py path/to/index.html
Reads the Google Fonts stylesheet link in the page, downloads the font files next to the page
(fonts/), and points the page at the local copy. If anything fails, the page is left as it was
and the app falls back to the phone's own fonts.
"""
import os, re, sys, urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()

def main(page):
    html = open(page, encoding="utf-8").read()
    m = re.search(r'<link rel="stylesheet" href="(https://fonts\.googleapis\.com/[^"]+)">', html)
    if not m:
        print("no Google Fonts link found"); return
    href = m.group(1).replace("&amp;", "&")
    css = get(href).decode("utf-8")
    # Keep only the Latin character sets (the app is in English); skips Cyrillic, Greek and so on.
    blocks = re.findall(r"(?:/\* ([a-z0-9-]+) \*/\s*)?(@font-face\s*\{[^}]*\})", css)
    kept = [("/* %s */\n" % k if k else "") + b for k, b in blocks if not k or k in ("latin", "latin-ext")]
    if kept:
        css = "\n".join(kept) + "\n"
    out_dir = os.path.join(os.path.dirname(page), "fonts")
    os.makedirs(out_dir, exist_ok=True)
    n = 0
    def fetch(mm):
        nonlocal n
        url = mm.group(1)
        name = "f%02d%s" % (n, os.path.splitext(url.split("?")[0])[1] or ".woff2")
        n += 1
        with open(os.path.join(out_dir, name), "wb") as f:
            f.write(get(url))
        return "url(%s)" % name
    css = re.sub(r"url\((https://fonts\.gstatic\.com/[^)]+)\)", fetch, css)
    with open(os.path.join(out_dir, "fonts.css"), "w", encoding="utf-8") as f:
        f.write(css)
    html = html.replace(m.group(0), '<link rel="stylesheet" href="fonts/fonts.css">')
    html = re.sub(r'<link rel="preconnect" href="https://fonts\.(googleapis|gstatic)\.com"( crossorigin)?>\n?', "", html)
    open(page, "w", encoding="utf-8").write(html)
    print("fonts copied:", n, "files")

if __name__ == "__main__":
    main(sys.argv[1])
