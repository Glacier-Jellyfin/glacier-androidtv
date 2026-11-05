"""Writes the README's buttons and Downloader note as SVGs in the website's look, light and dark.

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
# Day: ink on the GitHub page. Evening: Glacier's colours.
THEMES = {
    "light": {"fill": "#2b2520", "ink": "#f3ece1", "line": "#2b2520", "text": "#2b2520"},
    "dark": {"fill": "#7dc7d9", "ink": "#06222a", "line": "#eef8fa", "text": "#eef8fa"},
}
BUTTONS = [
    ("amazon", "Amazon Appstore", "Fire TV", 214, True),
    ("play", "Google Play", "Become a tester", 184, False),
    ("github", "Download APK", "From GitHub", 184, False),
    ("kofi", "Buy me a coffee", "on Ko-fi", 196, True),
]


def button(icon, title, sub, width, filled, theme):
    c = THEMES[theme]
    h = 56
    if filled:
        frame = f'<rect width="{width}" height="{h}" rx="6" fill="{c["fill"]}"/>'
        ink = c["ink"]
    else:
        frame = f'<rect x="1" y="1" width="{width - 2}" height="{h - 2}" rx="5" fill="none" stroke="{c["line"]}" stroke-width="2"/>'
        ink = c["text"]
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{h}" viewBox="0 0 {width} {h}" role="img" aria-label="{title}, {sub}">
  {frame}
  <g transform="translate(16 16)" fill="none" stroke="{ink}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{ICONS[icon]}</g>
  <text x="52" y="26" fill="{ink}" font-family="{FONT}" font-size="16" font-weight="700">{title}</text>
  <text x="52" y="43" fill="{ink}" font-family="{FONT}" font-size="12" opacity="0.85">{sub}</text>
</svg>
'''


def sticky(theme):
    paper = "#f7e98e" if theme == "light" else "#cfe9f0"
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="300" height="190" viewBox="0 0 300 190" role="img" aria-label="Downloader code 2423112">
  <g transform="rotate(-2.5 150 95)">
    <rect x="22" y="22" width="256" height="146" fill="rgba(0,0,0,0.18)"/>
    <rect x="16" y="16" width="256" height="146" fill="{paper}"/>
    <text x="38" y="52" fill="#2b2520" font-family="{HAND}" font-size="19" font-weight="700">Downloader code</text>
    <text x="36" y="112" fill="#2b2520" font-family="{HAND}" font-size="40" font-weight="700" letter-spacing="1">2423112</text>
    <text x="40" y="146" fill="#2b2520" font-family="{HAND}" font-size="16">keep this note!</text>
  </g>
</svg>
'''


for theme in THEMES:
    for icon, title, sub, width, filled in BUTTONS:
        (OUT / f"{icon}-{theme}.svg").write_text(button(icon, title, sub, width, filled, theme), encoding="utf-8", newline="\n")
    (OUT / f"code-{theme}.svg").write_text(sticky(theme), encoding="utf-8", newline="\n")
print(sorted(p.name for p in OUT.glob("*.svg")))
