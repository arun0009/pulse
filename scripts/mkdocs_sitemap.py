"""MkDocs post-build hook: writes sitemap.xml into the site output dir."""

from pathlib import Path
from xml.sax.saxutils import escape as xml_escape


def _skip(rel: str) -> bool:
    first = rel.split("/", 1)[0]
    if first in frozenset(("search", "offline")):
        return True
    if rel == "404.html" or rel.startswith("404/"):
        return True
    return False


def _loc(site_url: str, rel: str) -> str:
    base = site_url.rstrip("/")
    if rel == "index.html":
        return f"{base}/"
    if rel.endswith("/index.html"):
        prefix = rel[: -len("/index.html")].strip("/")
        return f"{base}/{prefix}/" if prefix else f"{base}/"
    return f"{base}/{rel}"


def on_post_build(config) -> None:
    site_url = str(config["site_url"] or "").strip()
    if not site_url:
        return

    site_dir = Path(config["site_dir"])
    locs: set[str] = set()
    for html in site_dir.rglob("*.html"):
        rel = html.relative_to(site_dir).as_posix()
        if _skip(rel):
            continue
        locs.add(_loc(site_url, rel))

    home = _loc(site_url, "index.html")
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ]

    def priority(loc: str) -> str:
        return "1.0" if loc.rstrip("/") == home.rstrip("/") else "0.85"

    for loc in sorted(locs):
        lines.append(
            "  <url>"
            f"<loc>{xml_escape(loc)}</loc>"
            "<changefreq>weekly</changefreq>"
            f"<priority>{priority(loc)}</priority>"
            "</url>"
        )
    lines.append("</urlset>")
    (site_dir / "sitemap.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")
