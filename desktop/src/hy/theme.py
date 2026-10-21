"""Nothing-style look for the terminal: true black background (the terminal's own), one red accent,
grey dots, a dot-matrix banner and a dot "orb" spinner. Same palette as the Android app."""
import os
import sys

RED_RGB = (215, 25, 33)  # NothingRed #D71921
GREY_RGB = (136, 136, 136)
DARK_RGB = (85, 85, 85)
WHITE_RGB = (236, 236, 236)

_enabled = False
_truecolor = False


def setup(stream=None):
    """Turn colours on when writing to a real terminal (and NO_COLOR is not set)."""
    global _enabled, _truecolor
    stream = stream or sys.stdout
    _enabled = bool(getattr(stream, "isatty", lambda: False)()) and not os.environ.get("NO_COLOR") and os.environ.get("TERM") != "dumb"
    _truecolor = os.environ.get("COLORTERM", "").lower() in ("truecolor", "24bit")
    return _enabled


def enabled():
    return _enabled


def _fg(rgb, fallback256):
    if _truecolor:
        return "\033[38;2;%d;%d;%dm" % rgb
    return "\033[38;5;%dm" % fallback256


RESET = "\033[0m"


def paint(text, rgb, fallback256, bold=False):
    if not _enabled:
        return text
    return ("\033[1m" if bold else "") + _fg(rgb, fallback256) + text + RESET


def red(s, bold=False):
    return paint(s, RED_RGB, 160, bold)


def grey(s):
    return paint(s, GREY_RGB, 245)


def dim(s):
    return paint(s, DARK_RGB, 240)


def white(s, bold=False):
    return paint(s, WHITE_RGB, 255, bold)


def bold(s):
    return "\033[1m" + s + RESET if _enabled else s


def prompt_wrap(s):
    """Mark colour codes as zero-width for readline, so editing a long line does not garble it."""
    if not _enabled:
        return s
    out, i = [], 0
    while i < len(s):
        if s[i] == "\033":
            j = s.index("m", i) + 1
            out.append("\001" + s[i:j] + "\002")
            i = j
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


# --- dot matrix -------------------------------------------------------------------------------

GLYPHS = {
    "H": ["X...X", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
    "A": [".XXX.", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
}


def dot_text(word, on="●", off="·"):
    """Rows of a word drawn as a dot grid (red dots on, dim dots off)."""
    rows = []
    for r in range(7):
        cells = []
        for ch in word:
            g = GLYPHS[ch][r]
            cells.append(" ".join(red(on) if c == "X" else dim(off) for c in g))
        rows.append("   ".join(cells))
    return rows


def banner(version, compact=False):
    if compact:
        return [red("● ", bold=True) + white("HEY-ALFRED", bold=True) + grey("  v" + version)]
    rows = ["  " + r for r in dot_text("HA")]
    rows[2] += "     " + white("HEY-ALFRED", bold=True)
    rows[3] += "     " + grey("v" + version + " · your private AI, running on this PC")
    return rows


ORB_FRAMES = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]


def orb(i):
    return red(ORB_FRAMES[i % len(ORB_FRAMES)])


def chip(label, ok=True):
    """'● AI ready' status chip."""
    return (red("●") if ok else dim("●")) + " " + (white(label) if ok else grey(label))


def rule(width, label=""):
    """A hairline with an optional dot-matrix style label, like the app's section headers."""
    if label:
        text = " " + label.upper() + " "
        return grey("·" * 2 + text) + dim("·" * max(0, width - len(text) - 2))
    return dim("·" * width)
