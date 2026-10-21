"""Fetch a web page and boil it down to what a small model can use: the facts an SEO or technical
review needs (status, speed, title, meta, headings, links, images, schema, robots) plus the readable text."""
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

MAX_BYTES = 2_000_000
UA = "Mozilla/5.0 (X11; Linux x86_64) Hey-Alfred/0.1"
_SKIP = {"script", "style", "noscript", "svg", "template", "head"}
_BLOCK = {"p", "div", "li", "br", "tr", "section", "article", "header", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "table", "blockquote", "pre", "main", "nav"}


class _Page(HTMLParser):
    def __init__(self, base):
        super().__init__(convert_charrefs=True)
        self.base = base
        self.title = ""
        self.meta = {}
        self.canonical = ""
        self.headings = []  # (level, text)
        self.links = []  # (href, text)
        self.images = []  # (src, alt)
        self.jsonld = 0
        self.text = []
        self.lang = ""
        self._skip = 0
        self._in_head = False
        self._in_title = False
        self._h = None
        self._a = None
        self._cur = []
        self._ld = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "head":
            self._in_head = True
        if tag == "html":
            self.lang = a.get("lang", "") or ""
        elif tag == "title":
            self._in_title = True
        elif tag == "meta":
            key = (a.get("name") or a.get("property") or a.get("http-equiv") or "").lower()
            if key and a.get("content") is not None:
                self.meta.setdefault(key, a["content"])
        elif tag == "link" and (a.get("rel") or "").lower() == "canonical":
            self.canonical = a.get("href", "")
        elif tag == "script" and (a.get("type") or "").lower() == "application/ld+json":
            self.jsonld += 1
        elif tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self._h = (int(tag[1]), [])
        elif tag == "a":
            self._a = (urllib.parse.urljoin(self.base, a.get("href", "")), [])
        elif tag == "img":
            self.images.append((a.get("src", ""), a.get("alt")))
        if tag in _SKIP and tag != "head":
            self._skip += 1
        if tag in _BLOCK:
            self.text.append("\n")

    def handle_endtag(self, tag):
        if tag == "head":
            self._in_head = False
        if tag == "a":
            self.text.append(" ")
        if tag == "title":
            self._in_title = False
        elif tag in ("h1", "h2", "h3", "h4", "h5", "h6") and self._h:
            txt = " ".join("".join(self._h[1]).split())
            if txt:
                self.headings.append((self._h[0], txt))
            self._h = None
        elif tag == "a" and self._a:
            txt = " ".join("".join(self._a[1]).split())
            self.links.append((self._a[0], txt))
            self._a = None
        if tag in _SKIP and tag != "head" and self._skip:
            self._skip -= 1
        if tag in _BLOCK:
            self.text.append("\n")

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        if self._skip or self._in_head:
            return
        if self._h:
            self._h[1].append(data)
        if self._a:
            self._a[1].append(data)
        self.text.append(data)


def readable(parser, limit):
    txt = "".join(parser.text)
    txt = re.sub(r"[ \t\r\f\v]+", " ", txt)
    txt = re.sub(r"\n\s*\n+", "\n", txt).strip()
    return txt[:limit] + ("\n…(text cut)" if len(txt) > limit else "")


def summarize(url, status, headers, body, seconds, final_url, text_limit=3500):
    ctype = headers.get("Content-Type", "")
    out = [f"URL: {final_url}" + ("" if final_url == url else f" (redirected from {url})"),
           f"Status: {status} · {seconds:.2f}s · {len(body):,} bytes · {ctype.split(';')[0]}"]
    srv = headers.get("Server")
    if srv:
        out[-1] += f" · server {srv}"
    if "html" not in ctype.lower() and not body[:200].lstrip().lower().startswith((b"<!doctype html", b"<html")):
        text = body.decode("utf-8", "replace")
        return "\n".join(out) + "\n\n" + text[:text_limit] + ("\n…(cut)" if len(text) > text_limit else "")
    page = _Page(final_url)
    try:
        page.feed(body.decode("utf-8", "replace"))
    except Exception:  # broken markup: use whatever was parsed
        pass
    title = " ".join(page.title.split())
    desc = page.meta.get("description", "").strip()
    host = urllib.parse.urlparse(final_url).netloc
    internal = [l for l in page.links if urllib.parse.urlparse(l[0]).netloc == host]
    external = [l for l in page.links if l[0].startswith("http") and urllib.parse.urlparse(l[0]).netloc != host]
    no_alt = [i for i in page.images if not (i[1] or "").strip()]
    h1 = [h for lv, h in page.headings if lv == 1]
    facts = [
        f"Title ({len(title)} chars): {title or '(missing)'}",
        f"Meta description ({len(desc)} chars): {desc or '(missing)'}",
        f"Canonical: {page.canonical or '(none)'}   Language: {page.lang or '(not set)'}   Robots meta: {page.meta.get('robots', '(none)')}",
        f"H1 count: {len(h1)}   Headings: {len(page.headings)}   JSON-LD schema blocks: {page.jsonld}",
        f"Open Graph: {'og:title' in page.meta and 'yes' or 'no og:title'}, {'og:image' in page.meta and 'og:image yes' or 'no og:image'}   Viewport meta: {'yes' if 'viewport' in page.meta else 'no'}",
        f"Links: {len(internal)} internal, {len(external)} external   Images: {len(page.images)} ({len(no_alt)} without alt text)",
    ]
    out += ["", *facts]
    if page.headings:
        out += ["", "Outline:"] + [f"{'  ' * (lv - 1)}h{lv} {h[:90]}" for lv, h in page.headings[:25]]
    out += ["", "Text:", readable(page, text_limit)]
    return "\n".join(out)


def fetch(url, timeout=20, text_limit=3500):
    """Return a text report for [url]; raises ValueError for bad URLs."""
    p = urllib.parse.urlparse(url if "://" in url else "https://" + url)
    if p.scheme not in ("http", "https") or not p.netloc:
        raise ValueError("only http and https addresses can be fetched")
    url = p.geturl()
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html,*/*;q=0.8", "Accept-Language": "en"})
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read(MAX_BYTES)
            status, headers, final = r.status, r.headers, r.geturl()
    except urllib.error.HTTPError as e:
        body = e.read(MAX_BYTES) if hasattr(e, "read") else b""
        status, headers, final = e.code, e.headers, url
    except urllib.error.URLError as e:
        raise ValueError(f"could not reach {url}: {e.reason}") from None
    return summarize(url, status, headers, body, time.monotonic() - t0, final, text_limit)
