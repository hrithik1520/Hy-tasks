"""`hy`: the command line."""
import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import time

from . import config, server, theme
from .agent import Agent
from .client import Client
from .tools import Tools
from .ui import UI, short_path

HELP = """\
Hey-Alfred: your private AI on this PC, with access to your terminal.

  hey-alfred                    open the Hey-Alfred window (same as the app icon)
  hey-alfred chat               the same AI inside this terminal instead
  hey-alfred "list big files"   ask once and exit (also: echo question | hey-alfred)
  hey-alfred setup              install the AI engine and download the model (once, ~19 GB)
  hey-alfred start | stop       start the AI engine, or stop it and the window
  hey-alfred status | logs      see what the engine is doing
  hey-alfred doctor             check that this PC is ready
  (`hy` works as a short name for hey-alfred)

Options:
  --mode ask|auto|readonly   ask: reading runs, changes need your OK (default)
                             auto: ordinary commands run; risky ones still ask
                             readonly: nothing that changes anything runs
  --cwd DIR                  work in this folder
  --keep                     leave the AI engine running when Hey-Alfred exits
  --host H --port P          use an AI engine that is already running there
  --no-open                  print the window's address instead of opening it

In the terminal version (hey-alfred chat):  /help  /mode  /cd DIR  /clear  /status  /save  /engine  /exit
Start a line with ! to run a shell command yourself:  !git status
"""

SLASH_HELP = [
    ("/mode [ask|auto|readonly]", "how much Alfred may do without asking"),
    ("/cd DIR", "change the working folder"),
    ("/clear", "forget this conversation"),
    ("/status", "engine, memory and settings"),
    ("/save", "save this conversation as a file"),
    ("/engine stop|start|logs", "control the AI engine"),
    ("!COMMAND", "run a shell command yourself"),
    ("/exit", "leave (Ctrl+D works too)"),
]
SLASH = ["/help", "/mode", "/cd", "/clear", "/status", "/save", "/engine", "/exit", "/quit"]


def model_label(cfg):
    return "Qwen3-Coder 30B" if "coder" in cfg["model_name"] else cfg["model_name"]


def make_client(cfg):
    return Client(cfg["host"], cfg["port"], cfg["model_name"])


def ensure_engine(cfg, ui, client, start_allowed=True):
    """Make sure the AI engine answers. Returns (ready, started_by_us)."""
    if client.alive() is not None:
        return True, False
    if not start_allowed:
        ui.error(f"no AI engine at {cfg['host']}:{cfg['port']}")
        return False, False
    st = server.installed(cfg)
    if not st["ready"]:
        missing = [k for k in ("colibri", "engine", "model") if not st[k]]
        ui.error("the AI engine is not installed yet (missing: " + ", ".join(missing) + ")")
        ui.line("  Run  " + theme.white("hey-alfred setup") + "  once. It downloads the model (about 19 GB).")
        return False, False
    avail = server.available_memory_bytes() / 2**30
    ui.line("  " + theme.grey(f"Starting the AI engine… ({avail:.1f} GB of memory free)"))
    try:
        server.start(cfg)
    except RuntimeError as e:
        ui.error(str(e))
        return False, False
    frame = [0]

    def tick(elapsed):
        if ui.interactive:
            ui.out.write(f"\r  {theme.orb(frame[0])} {theme.grey('Loading model')} {theme.dim('%.0fs' % elapsed)}\033[K")
            ui.out.flush()
            frame[0] += 1

    ok = server.wait_ready(client, tick=tick)
    if ui.interactive:
        ui.out.write("\r\033[K")
    if not ok:
        ui.error("the AI engine did not start. Last log lines:")
        for ln in server.log_tail(12).splitlines():
            ui.line("    " + theme.dim(ln[:120]))
        server.stop()
        return False, False
    return True, True


def status_chips(cfg, agent, started):
    return [theme.chip("AI ready"), theme.chip(model_label(cfg), True), theme.chip("on this PC · private", True)]


def run_shell(cmd, cwd):
    try:
        subprocess.call(["bash", "-c", cmd], cwd=cwd)
    except KeyboardInterrupt:
        print()


def cmd_status(cfg, ui, client):
    alive = client.alive()
    ui.line("  " + theme.chip("engine running" if alive is not None else "engine stopped", alive is not None) + (theme.grey("  " + (alive or "")) if alive else ""))
    st = server.installed(cfg)
    ui.line(f"  model files: {theme.white('yes') if st['model'] else theme.red(st['model_state'])}   memory free: {server.available_memory_bytes() / 2**30:.1f} of {server.total_memory_bytes() / 2**30:.1f} GB")
    if server.has_systemd_user():
        r = subprocess.run(["systemctl", "--user", "show", server.UNIT, "-p", "MemoryCurrent", "-p", "MemoryMax", "-p", "ActiveState"], capture_output=True, text=True)
        props = dict(l.split("=", 1) for l in r.stdout.splitlines() if "=" in l)
        if props.get("ActiveState") == "active":
            def gb(v):
                return f"{int(v) / 2**30:.1f} GB" if v.isdigit() else "no limit"
            ui.line(f"  engine memory: {gb(props.get('MemoryCurrent', ''))} (limit {gb(props.get('MemoryMax', ''))})")
    ui.line(f"  folder: {short_path(os.getcwd())}   mode: {cfg['mode']}   window: {cfg['ctx']} tokens   experts cached per layer: {cfg['cap']}")


def cmd_doctor(cfg, ui):
    ok_all = True

    def check(label, ok, detail="", fix=""):
        nonlocal ok_all
        ok_all = ok_all and ok
        ui.line(f"  {theme.red('✓') if ok else theme.red('✕')} {theme.white(label)}" + (theme.grey("  " + detail) if detail else ""))
        if not ok and fix:
            ui.line("      " + theme.grey(fix))

    py = sys.version_info
    check("Python 3.9+", py >= (3, 9), f"{py.major}.{py.minor}.{py.micro}")
    check("git", bool(shutil.which("git")), fix="sudo dnf install git")
    check("systemd (memory limit for the engine)", server.has_systemd_user(), fix="without it the engine runs with no memory limit")
    tot = server.total_memory_bytes() / 2**30
    check("memory", tot >= 8, f"{tot:.1f} GB total, {server.available_memory_bytes() / 2**30:.1f} GB free now", "the model needs about 8 GB: close other programs")
    free = shutil.disk_usage(os.path.expanduser("~")).free / 2**30
    st = server.installed(cfg)
    check("disk space", free > 25 or st["model"], f"{free:.0f} GB free", "the model needs about 20 GB")
    check("AI engine (colibri)", st["colibri"] and st["engine"], cfg["colibri_dir"], "run: hey-alfred setup")
    if st["model_state"] == "partial" and st["model_want"]:
        detail = f"download unfinished: {st['model_have'] / 2**30:.1f} of {st['model_want'] / 2**30:.1f} GB"
    else:
        detail = cfg["model_dir"]
    check("model files", st["model"], detail, "run: hey-alfred setup (it continues where it stopped)")
    check("engine running", make_client(cfg).alive() is not None, "", "it starts by itself when you run hy")
    ui.line()
    ui.line("  " + (theme.white("All good.") if ok_all else theme.grey("Fix the ✕ items above.")))
    return 0 if ok_all else 1


def cmd_setup(cfg, ui):
    ui.line("  " + theme.white("Setting up the AI engine") + theme.grey("  (safe to stop and run again: it resumes)"))
    ui.line("  " + theme.grey(f"engine → {cfg['colibri_dir']}\n  model  → {cfg['model_dir']}  (about 19 GB)"))
    if shutil.disk_usage(os.path.expanduser("~")).free < 24 * 2**30 and not server.installed(cfg)["model"]:
        ui.error("not enough free disk space for the model (about 24 GB needed)")
        return 1
    try:
        rc = server.run_setup(cfg)
    except Exception as e:  # noqa: BLE001
        ui.error(str(e))
        return 1
    if rc != 0 or not server.installed(cfg)["ready"]:
        ui.error("setup did not finish. Run `hey-alfred setup` again to continue, or `hey-alfred doctor` to see what is missing.")
        return 1
    ui.line()
    ui.line("  " + theme.red("●") + " " + theme.white("Ready.") + "  Run  " + theme.white("hy") + "  to start.")
    return 0


class Completer:
    def __init__(self):
        self.matches = []

    def __call__(self, text, state):
        import readline
        if state == 0:
            line = readline.get_line_buffer()
            if line.startswith("/") and " " not in line:
                self.matches = [c for c in SLASH if c.startswith(text)]
            else:
                base = os.path.expanduser(text) if text else "."
                d, prefix = os.path.split(base)
                try:
                    names = os.listdir(d or ".")
                except OSError:
                    names = []
                self.matches = [os.path.join(os.path.dirname(text), n) + ("/" if os.path.isdir(os.path.join(d or ".", n)) else "") for n in names if n.startswith(prefix)]
        return self.matches[state] if state < len(self.matches) else None


def setup_readline():
    try:
        import readline
    except ImportError:
        return
    try:
        readline.read_history_file(config.HISTORY_FILE)
    except OSError:
        pass
    readline.set_history_length(1000)
    readline.set_completer(Completer())
    readline.set_completer_delims(" \t\n")
    readline.parse_and_bind("tab: complete")


def save_history():
    try:
        import readline
        readline.write_history_file(config.HISTORY_FILE)
    except (ImportError, OSError):
        pass


def save_session(agent, name=None):
    if len(agent.messages) < 2:
        return None
    path = os.path.join(config.SESSION_DIR, name or time.strftime("%Y%m%d-%H%M%S") + ".json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(agent.messages, f, indent=1, ensure_ascii=False)
    return path


def handle_slash(line, agent, ui, cfg, client):
    """Returns 'exit', or None when handled."""
    cmd, _, arg = line.partition(" ")
    arg = arg.strip()
    if cmd in ("/exit", "/quit", "/q"):
        return "exit"
    if cmd == "/help":
        ui.line()
        for c, d in SLASH_HELP:
            ui.line(f"  {theme.white(c.ljust(28))}{theme.grey(d)}")
        ui.line()
    elif cmd == "/mode":
        if arg in ("ask", "auto", "readonly"):
            agent.mode = arg
            ui.note({"ask": "ask: reading runs by itself; changes need your OK",
                     "auto": "auto: ordinary commands run by themselves; risky ones (rm, sudo, ssh, installs…) still ask",
                     "readonly": "read-only: nothing that changes anything will run"}[arg])
        else:
            ui.note(f"mode is {agent.mode}. Use /mode ask, /mode auto or /mode readonly")
    elif cmd == "/cd":
        try:
            agent.tools.chdir(arg or "~")
            agent.note_cwd_change()
            ui.note(f"now in {short_path(agent.tools.cwd)}")
        except ValueError as e:
            ui.error(str(e))
    elif cmd == "/clear":
        agent.clear()
        ui.note("conversation cleared")
    elif cmd == "/status":
        cmd_status(cfg, ui, client)
    elif cmd == "/save":
        p = save_session(agent)
        ui.note(f"saved to {p}" if p else "nothing to save yet")
    elif cmd == "/engine":
        if arg == "stop":
            server.stop()
            ui.note("engine stopped (it starts again when you ask something, or run hy)")
        elif arg == "logs":
            for ln in server.log_tail(25).splitlines():
                ui.line("  " + theme.dim(ln[:140]))
        elif arg == "start":
            ensure_engine(cfg, ui, client)
        else:
            ui.note("use /engine start, /engine stop or /engine logs")
    else:
        ui.note(f"unknown command {cmd}. Try /help")
    return None


def repl(cfg, ui, client, agent, started):
    setup_readline()
    ui.banner(config.VERSION, status_chips(cfg, agent, started), "Ask anything, or give a task. /help for commands · Ctrl+D to leave")
    ui.line("  " + theme.dim("Warming up the model in the background (a few minutes, once per conversation). You can type already."))
    ui.line()
    agent.start_priming()
    last_interrupt = 0.0
    while True:
        try:
            line = input(ui.prompt(agent.tools.cwd, agent.mode)).strip()
        except EOFError:
            ui.line()
            return
        except KeyboardInterrupt:
            ui.line()
            if time.monotonic() - last_interrupt < 2:
                return
            last_interrupt = time.monotonic()
            ui.note("press Ctrl+C again (or Ctrl+D) to leave")
            continue
        if not line:
            continue
        if line.startswith("!"):
            run_shell(line[1:].strip(), agent.tools.cwd)
            continue
        if line.startswith("/"):
            if handle_slash(line, agent, ui, cfg, client) == "exit":
                return
            continue
        if client.alive() is None:
            ready, started_now = ensure_engine(cfg, ui, client)
            if not ready:
                continue
        agent.ask(line)
        ui.line()


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    try:  # `hey-alfred doctor | head` should end quietly, not with a traceback
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    except (ImportError, AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(prog="hy", add_help=False)
    ap.add_argument("--mode", choices=["ask", "auto", "readonly"])
    ap.add_argument("--cwd")
    ap.add_argument("--host")
    ap.add_argument("--port", type=int)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--no-open", action="store_true")
    ap.add_argument("--terminal", action="store_true")
    ap.add_argument("--foreground", action="store_true")
    ap.add_argument("-p", "--prompt")
    ap.add_argument("-h", "--help", action="store_true")
    ap.add_argument("-v", "--version", action="store_true")
    ap.add_argument("rest", nargs="*")
    args = ap.parse_args(argv)
    config.ensure_dirs()
    cfg = config.load()
    for k in ("mode", "host", "port"):
        if getattr(args, k):
            cfg[k] = getattr(args, k)
    if args.keep:
        cfg["keep_server"] = True
    ui = UI()
    if args.version:
        print(f"{config.APP_NAME} {config.VERSION}")
        return 0
    if args.help or (args.rest and args.rest[0] == "help"):
        print(HELP)
        return 0
    client = make_client(cfg)
    word = args.rest[0] if args.rest else ""
    if word == "setup" and len(args.rest) == 1:
        return cmd_setup(cfg, ui)
    if word == "doctor" and len(args.rest) == 1:
        return cmd_doctor(cfg, ui)
    if word == "status" and len(args.rest) == 1:
        cmd_status(cfg, ui, client)
        return 0
    if word == "stop" and len(args.rest) == 1:
        from . import webui
        if webui.stop_window():
            ui.line("  window closed")
        server.stop()
        ui.line("  engine stopped")
        return 0
    if word == "start" and len(args.rest) == 1:
        ready, _ = ensure_engine(cfg, ui, client)
        if ready:
            ui.line("  " + theme.chip("AI engine running"))
        return 0 if ready else 1
    interactive = sys.stdin.isatty() and sys.stdout.isatty()
    if word == "chat" and len(args.rest) == 1:
        args.rest, args.terminal = [], True
    elif args.foreground or word == "ui" and len(args.rest) == 1 or (not args.rest and not args.prompt and interactive and not args.terminal):
        from . import webui
        if interactive and not args.foreground and not args.no_open:
            return webui.launch_detached(cfg, ui, cwd=args.cwd, argv=argv)
        if not sys.stdout.isatty() and not args.no_open:  # started from the app icon: keep a log instead of a terminal
            log = open(os.path.join(config.LOG_DIR, "ui.log"), "a", buffering=1)
            sys.stdout = sys.stderr = log
        return webui.run(cfg, cwd=args.cwd, keep=cfg["keep_server"], open_it=not args.no_open, say=lambda m: print(m, flush=True))
    if word == "logs" and len(args.rest) == 1:
        print(server.log_tail(60))
        return 0

    prompt = args.prompt or (" ".join(args.rest) if args.rest else "")
    if not prompt and not sys.stdin.isatty():
        prompt = sys.stdin.read().strip()
    if not prompt and not sys.stdout.isatty():
        print(HELP)
        return 0

    remote = bool(args.host or args.port)
    ready, started = ensure_engine(cfg, ui, client, start_allowed=not remote)
    if not ready:
        return 1
    toolbox = Tools(args.cwd, backup_dir=config.BACKUP_DIR)
    if args.cwd and not os.path.isdir(toolbox.cwd):
        ui.error(f"{args.cwd} is not a folder")
        return 1
    agent = Agent(client, ui, toolbox, cfg)

    def hangup(*_):  # the terminal window was closed: leave through the `finally` below so the engine is stopped
        raise SystemExit(0)

    for sig in ("SIGHUP", "SIGTERM"):
        try:
            signal.signal(getattr(signal, sig), hangup)
        except (AttributeError, ValueError):
            pass
    try:
        if prompt:
            ui.show_stats = False
            agent.ask(prompt)
            ui.line()
        else:
            repl(cfg, ui, client, agent, started)
    finally:
        save_history()
        path = save_session(agent)
        if started and not cfg["keep_server"]:
            if ui.interactive:
                ui.line("  " + theme.grey("Stopping the AI engine to free memory…  (hey-alfred --keep leaves it running)"))
            server.stop()
        if path and ui.interactive and not prompt:
            ui.line("  " + theme.dim("conversation saved: " + short_path(path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
