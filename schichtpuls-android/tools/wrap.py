"""Wraps the shared page source (web/schichtpuls.html, also published on claude.ai)
into a full HTML document for the app's assets. Run before every build."""
import pathlib

root = pathlib.Path(__file__).resolve().parent.parent
src = (root / "web" / "schichtpuls.html").read_text(encoding="utf-8")
doc = (
    "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">"
    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
    "<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}"
    "body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>"
    "</head><body>" + src + "</body></html>\n"
)
out = root / "app" / "src" / "main" / "assets" / "index.html"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(doc, encoding="utf-8")
print(f"wrote {out} ({len(doc)} bytes)")
