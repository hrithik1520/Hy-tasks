"""Terminal front end: Nothing-style output, streaming markdown-lite, the dot "orb" spinner,
approval prompts and the status chips. Plain text when not on a terminal."""
import os
import shutil
import sys
import threading
import time

from . import theme
from .agent import APPROVE_ABORT, APPROVE_ALWAYS, APPROVE_NO, APPROVE_YES


def short_path(p):
    home = os.path.expanduser("~")
    return "~" + p[len(home):] if p == home or p.startswith(home + os.sep) else p


class Renderer:
    """Turns streamed model text into styled lines. A line's style is decided from its first characters."""

    def __init__(self, write):
        self.write = write
        self.reset()

    def reset(self):
        self.buf = ""  # characters of the current line not yet printed
        self.style = None  # 'fence' | 'head' | 'bullet' | 'text' once decided for this line
        self.in_code = False
        self.started = False

    def feed(self, text):
        for ch in text:
            if self.style is None:
                self.buf += ch
                if ch == "\n" or len(self.buf) >= 3 or (len(self.buf) >= 1 and self.buf[0] not in "`#-*"):
                    self._decide()
            else:
                self._emit(ch)
                if ch == "\n":
                    self.style = None
                    self.buf = ""

    def finish(self):
        if self.style is None and self.buf:
            self._decide()
        self.style, self.buf = None, ""
        self.in_code = False

    def _decide(self):
        line = self.buf
        self.buf = ""
        if line.startswith("```"):
            self.in_code = not self.in_code
            self.style = "fence"
        elif self.in_code:
            self.style = "code"
        elif line.startswith("#"):
            self.style = "head"
            line = line.lstrip("#").lstrip(" ")
        elif line[:2] in ("- ", "* "):
            self.style = "bullet"
            self._emit(theme.red("•") + " ", raw=True)
            line = line[2:]
        else:
            self.style = "text"
        if line.endswith("\n"):
            self._emit(line[:-1])
            self._emit("\n")
            self.style = None
        else:
            self._emit(line)

    def _emit(self, s, raw=False):
        if raw or s == "\n" or not theme.enabled():
            self.write(s)
        elif self.style == "fence":
            self.write(theme.dim(s))
        elif self.style == "head":
            self.write(theme.bold(s))
        else:
            self.write(s)


class UI:
    def __init__(self, out=None, interactive=None):
        self.out = out or sys.stdout
        self.interactive = self.out.isatty() if interactive is None else interactive
        theme.setup(self.out)
        self._lock = threading.RLock()
        self._spin = None
        self._spin_stop = threading.Event()
        self._spin_t0 = 0.0
        self._started_text = False
        self._line_open = False
        self.renderer = Renderer(self._write)
        self.show_stats = True

    # -- low level --------------------------------------------------------------------------------
    def _write(self, s):
        with self._lock:
            self.out.write(s)
            self.out.flush()
            if s:
                self._line_open = not s.endswith("\n")

    def width(self):
        return max(40, min(shutil.get_terminal_size((100, 24)).columns, 110))

    def line(self, s=""):
        with self._lock:
            self._write(s + "\n")

    # -- header and status ----------------------------------------------------------------------------
    def banner(self, version, status_chips, hint=""):
        rows = shutil.get_terminal_size((100, 24)).lines
        self.line()
        for r in theme.banner(version, compact=rows < 24):
            self.line(r)
        self.line()
        self.line("  " + "   ".join(status_chips))
        if hint:
            self.line("  " + theme.grey(hint))
        self.line()

    def prompt(self, cwd, mode):
        mark = {"ask": theme.white("ask"), "auto": theme.red("auto"), "readonly": theme.grey("read-only")}[mode]
        return theme.prompt_wrap(f"{theme.grey(short_path(cwd))} {theme.dim('·')} {mark} {theme.red('›', bold=True)} ")

    def note(self, text):
        self.thinking(False)
        self.line("  " + theme.grey(text))

    def error(self, text):
        self.thinking(False)
        self.line("  " + theme.red("✕ ") + text)

    def info(self, text):
        self.line(text)

    # -- orb spinner ----------------------------------------------------------------------------------------
    def thinking(self, on, label="Thinking"):
        if not self.interactive:
            return
        stopped = None
        with self._lock:
            if on and self._spin is None:
                self._spin_stop.clear()
                self._spin_t0 = time.monotonic()
                self._started_text = False
                self._spin = threading.Thread(target=self._spin_loop, args=(label,), daemon=True)
                self._spin.start()
            elif not on and self._spin is not None:
                self._spin_stop.set()
                stopped, self._spin = self._spin, None
        if stopped is not None:
            stopped.join(timeout=1)
            with self._lock:
                self.out.write("\r\033[K")
                self.out.flush()

    def _spin_loop(self, label):
        i = 0
        while not self._spin_stop.is_set():
            secs = time.monotonic() - self._spin_t0
            if label == "Warming up":
                extra = "  " + theme.dim("once per conversation, about 4–5 minutes on this CPU") if secs > 5 else ""
            elif label == "Thinking" and secs > 30:
                extra = "  " + theme.dim("a CPU reads and writes slowly; long answers take a few minutes")
            else:
                extra = ""
            with self._lock:
                if not self._spin_stop.is_set():
                    self.out.write(f"\r  {theme.orb(i)} {theme.grey(label)} {theme.dim(f'{secs:.0f}s')}{extra}\033[K")
                    self.out.flush()
            i += 1
            self._spin_stop.wait(0.09)

    # -- assistant text ----------------------------------------------------------------------------------------
    def stream(self, text):
        if not self._started_text:
            self._started_text = True
            self._write("  " + theme.red("◆") + " ")
        self.renderer.feed(text)

    def end_text(self, resp):
        had_text = self._started_text
        self.renderer.finish()
        if had_text and self._line_open:
            self._write("\n")
        if had_text and self.show_stats and resp.get("tokens"):
            gen = resp["seconds"] - resp.get("first_token", 0)
            rate = f" · {resp['tokens'] / gen:.1f}/s" if gen >= 0.5 and resp["tokens"] >= 8 else ""
            self.line("  " + theme.dim(f"· {resp['seconds']:.1f}s · {resp['tokens']} tokens{rate}"))
        self._started_text = False

    # -- tools ---------------------------------------------------------------------------------------------
    def tool_call(self, name, detail, level):
        dot = theme.red("●") if level in ("risky", "block") else theme.grey("●")
        first, _, rest = detail.partition("\n")
        self.line(f"  {dot} {theme.white(name, bold=True)}  {theme.grey(first)}")
        for extra in rest.splitlines()[:30]:
            if extra.startswith("+") and not extra.startswith("+++"):
                text = theme.white(extra)
            elif extra.startswith("-") and not extra.startswith("---"):
                text = theme.red(extra)
            else:
                text = theme.dim(extra)
            self.line("      " + text)

    def running(self, name):
        self.thinking(True, label="Running")

    def tool_result(self, text, refused=False):
        self.thinking(False)
        lines = text.splitlines() or [""]
        shown = lines[:8]
        for ln in shown:
            self.line("    " + theme.dim("│ ") + (theme.grey(ln[: self.width() - 8]) if not refused else theme.red(ln[: self.width() - 8])))
        if len(lines) > len(shown):
            self.line("    " + theme.dim(f"│ … {len(lines) - len(shown)} more lines (the model sees them)"))

    def approve(self, level, title, detail):
        if not self.interactive or not sys.stdin.isatty():
            self.line("  " + theme.grey("(not interactive: declined)"))
            return APPROVE_NO
        label = {"risky": theme.red("needs your OK · careful"), "normal": theme.white("needs your OK")}.get(level, "needs your OK")
        while True:
            try:
                ans = input(theme.prompt_wrap(f"  {theme.red('▸')} {label}  {theme.grey('[y]es  [n]o  [a]lways' + ('' if level == 'risky' else '') + '  [q]uit')}  ")).strip().lower()
            except (EOFError, KeyboardInterrupt):
                self.line()
                return APPROVE_ABORT
            if ans in ("y", "yes"):
                return APPROVE_YES
            if ans in ("", "n", "no"):
                return APPROVE_NO
            if ans in ("a", "always"):
                if level == "risky":
                    self.line("  " + theme.grey("risky commands always ask; running this one"))
                    return APPROVE_YES
                return APPROVE_ALWAYS
            if ans in ("q", "quit", "stop"):
                return APPROVE_ABORT
