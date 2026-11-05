"""Writes the README's wordmark, buttons and Downloader note as SVGs in the website's look,
light and dark in one file each.

Usage: scripts/make-readme-badges.py (writes docs/badges/*.svg)
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "badges"
FONT = "'Segoe UI', 'Helvetica Neue', Helvetica, Arial, sans-serif"
HAND = "'Segoe Print', 'Bradley Hand', 'Comic Sans MS', cursive"

ICONS = {
    "amazon": '<path d="M5 8h14l-1 12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1L5 8Z"/><path d="M9 8V6a3 3 0 0 1 6 0v2"/>',
    "play": '<path d="M7 4.5v15a1 1 0 0 0 1.5.86l12-7.5a1 1 0 0 0 0-1.72l-12-7.5A1 1 0 0 0 7 4.5Z"/>',
    "github": '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 20h14"/>',
    "kofi": '<path d="M4 9h12v6a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5Z"/><path d="M16 11h1.5a2.5 2.5 0 0 1 0 5H16"/><path d="M8 3c-1 1.5 1 2.5 0 4M12 3c-1 1.5 1 2.5 0 4"/>',
}
# Day: ink on the GitHub page. Evening: Glacier's colours. Each SVG carries both and
# follows the viewer's colour scheme itself, because GitHub's <picture> switch does not
# reach every viewer (the mobile app, a GitHub theme that differs from the system's).
THEMES = {
    "light": {"fill": "#2b2520", "ink": "#f3ece1", "line": "#2b2520", "text": "#2b2520", "paper": "#fbf8f3"},
    "dark": {"fill": "#7dc7d9", "ink": "#06222a", "line": "#eef8fa", "text": "#eef8fa", "paper": "#0e2a33"},
}
BUTTONS = [
    ("amazon", "Amazon Appstore", "Fire TV", 214, True),
    ("play", "Google Play", "Become a tester", 214, False),
    ("github", "Download APK", "From GitHub", 214, False),
    ("kofi", "Buy me a coffee", "on Ko-fi", 196, True),
]
WORDMARK = OUT.parent.parent / "website" / "assets" / "wordmark-light.svg"


def themed(rules):
    """CSS for both colour schemes. rules: {selector: {property: (light, dark)}}, where
    each value is a colour or a key of THEMES."""
    def block(theme, pick):
        out = []
        for sel, props in rules.items():
            decls = " ".join(f"{prop}: {THEMES[theme].get(pick(v), pick(v))};" for prop, v in props.items())
            out.append(f"{sel} {{ {decls} }}")
        return " ".join(out)
    light = block("light", lambda v: v[0])
    dark = block("dark", lambda v: v[1])
    return f"<style>{light} @media (prefers-color-scheme: dark) {{ {dark} }}</style>"


def button(icon, title, sub, width, filled):
    h = 56
    if filled:
        frame = f'<rect class="frame" width="{width}" height="{h}" rx="6"/>'
        style = themed({".frame": {"fill": ("fill", "fill")}, ".ink": {"stroke": ("ink", "ink")}, "text": {"fill": ("ink", "ink")}})
    else:
        # A paper fill keeps the outlined buttons readable on a page of either colour.
        frame = f'<rect class="frame" x="1" y="1" width="{width - 2}" height="{h - 2}" rx="5" stroke-width="2"/>'
        style = themed({".frame": {"fill": ("paper", "paper"), "stroke": ("line", "line")},
                        ".ink": {"stroke": ("text", "text")}, "text": {"fill": ("text", "text")}})
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{h}" viewBox="0 0 {width} {h}" role="img" aria-label="{title}, {sub}">
  {style}
  {frame}
  <g class="ink" transform="translate(16 16)" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{ICONS[icon]}</g>
  <text x="52" y="26" font-family="{FONT}" font-size="16" font-weight="700">{title}</text>
  <text x="52" y="43" font-family="{FONT}" font-size="12" opacity="0.85">{sub}</text>
</svg>
'''


def sticky():
    style = themed({".paper": {"fill": ("#f7e98e", "#cfe9f0")}})
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="300" height="190" viewBox="0 0 300 190" role="img" aria-label="Downloader code 2423112">
  {style}
  <g transform="rotate(-2.5 150 95)">
    <rect x="22" y="22" width="256" height="146" fill="rgba(0,0,0,0.18)"/>
    <rect class="paper" x="16" y="16" width="256" height="146"/>
    <text x="38" y="52" fill="#2b2520" font-family="{HAND}" font-size="19" font-weight="700">Downloader code</text>
    <text x="36" y="112" fill="#2b2520" font-family="{HAND}" font-size="40" font-weight="700" letter-spacing="1">2423112</text>
    <text x="40" y="146" fill="#2b2520" font-family="{HAND}" font-size="16">keep this note!</text>
  </g>
</svg>
'''


def wordmark():
    """The website's wordmark with the colours of wordmark-light.svg and wordmark-dark.svg."""
    svg = WORDMARK.read_text(encoding="utf-8")
    svg = svg.replace('fill="none" stroke="#4E93A8"', 'fill="none" class="mark-line"')
    svg = svg.replace('fill="#4E93A8"', 'class="mark"').replace('fill="#0A1420"', 'class="name"')
    style = themed({".mark-line": {"stroke": ("#4E93A8", "#7DC7D9")}, ".mark": {"fill": ("#4E93A8", "#7DC7D9")},
                    ".name": {"fill": ("#0A1420", "#E8F4F7"), "stroke": ("#E8F4F7", "#0A1420")}})
    # The outline in the other colour keeps the name readable when GitHub's theme and the
    # system's differ; on a page of the matching colour it disappears.
    style = style.replace("<style>", "<style>.name { stroke-width: 3; paint-order: stroke; stroke-linejoin: round; } ", 1)
    head, rest = svg.split(">", 1)
    return head + ">\n  " + style + rest


def write(name, svg):
    (OUT / name).write_text(svg, encoding="utf-8", newline="\n")


for old in [*OUT.glob("*-light.svg"), *OUT.glob("*-dark.svg")]:
    old.unlink()
for icon, title, sub, width, filled in BUTTONS:
    write(f"{icon}.svg", button(icon, title, sub, width, filled))
write("code.svg", sticky())
write("wordmark.svg", wordmark())
print(sorted(p.name for p in OUT.glob("*.svg")))
