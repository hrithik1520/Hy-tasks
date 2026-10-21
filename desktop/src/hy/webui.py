"""The window: a small web server on this PC (127.0.0.1 only) that serves the Hey-Alfred page and connects it to
the same agent, safety rules and AI engine as the terminal version.

Because this page can make Alfred run commands, it is closed to everything but the user's own window:
a secret token (cookie, SameSite=Strict), a Host check against DNS rebinding, an Origin check, and a
custom header on every POST, which other websites cannot send."""
import hmac
import http.server
import importlib.resources
import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import webbrowser

from . import config, server
from .agent import APPROVE_ABORT, APPROVE_ALWAYS, APPROVE_NO, APPROVE_YES, Agent
from .client import Client
from .tools import Tools

INFO_FILE = os.path.join(config.DATA_DIR, "ui.json")
IDLE_SHUTDOWN_SECONDS = 300  # no window pinging for this long (and nothing running): free the memory
MAX_EVENTS = 20000
CHOICES = {"yes": APPROVE_YES, "no": APPROVE_NO, "always": APPROVE_ALWAYS, "abort": APPROVE_ABORT}


class Session:
    """An append-only event log the page follows (and replays after a reload)."""

    def __init__(self):
        self.cond = threading.Condition()
        self.events = []
        self.base = 0  # index of events[0]
        self.closing = False
        self.pending = {}  # approval id -> [Event, choice]
        self._next_approval = 1

    def emit(self, type_, **data):
        with self.cond:
            self.events.append({"type": type_, **data})
            if len(self.events) > MAX_EVENTS:
                drop = len(self.events) - MAX_EVENTS
                del self.events[:drop]
                self.base += drop
            self.cond.notify_all()

    def read(self, since, wait=15.0):
        """Events from absolute index [since]; waits up to [wait] seconds for news. -> (events, next_index)."""
        with self.cond:
            if since < self.base:
                since = self.base
            if self.base + len(self.events) <= since and not self.closing:
                self.cond.wait(wait)
            batch = self.events[since - self.base:]
            return batch, since + len(batch)

    def close(self):
        with self.cond:
            self.closing = True
            self.cond.notify_all()

    def new_approval(self):
        with self.cond:
            aid = self._next_approval
            self._next_approval += 1
            self.pending[aid] = [threading.Event(), None]
            return aid

    def answer(self, aid, choice):
        with self.cond:
            slot = self.pending.get(aid)
        if not slot:
            return False
        slot[1] = choice
        slot[0].set()
        return True

    def abort_all(self):
        with self.cond:
            slots = list(self.pending.values())
        for ev, _ in slots:
            ev.set()


class WebUI:
    """The interface the Agent talks to (same methods as the terminal UI), turned into events."""

    interactive = True
    show_stats = True

    def __init__(self, session):
        self.s = session
        self._thinking = None

    def thinking(self, on, label="Thinking"):
        key = label if on else None
        if key == self._thinking:
            return
        self._thinking = key
        self.s.emit("thinking", on=bool(on), label=label)

    def stream(self, text):
        self.thinking(False)
        self.s.emit("text", t=text)

    def end_text(self, resp):
        self.s.emit("end_text", tokens=resp.get("tokens", 0), seconds=round(resp.get("seconds", 0), 1))

    def note(self, text):
        self.thinking(False)
        self.s.emit("note", text=text)

    def error(self, text):
        self.thinking(False)
        self.s.emit("error", text=text)

    def tool_call(self, name, detail, level):
        self.s.emit("tool_call", name=name, detail=detail, level=level)

    def running(self, name):
        self.thinking(True, "Running")

    def tool_result(self, text, refused=False):
        self.thinking(False)
        self.s.emit("tool_result", text=text, refused=refused)

    def approve(self, level, title, detail):
        aid = self.s.new_approval()
        self.s.emit("approval", id=aid, level=level, title=title, detail=detail)
        ev, _ = self.s.pending[aid]
        ev.wait()
        choice = CHOICES.get(self.s.pending.pop(aid)[1] or "abort", APPROVE_ABORT)
        self.s.emit("approval_done", id=aid, choice=choice)
        return choice


def _resource(name):
    return importlib.resources.files("hy").joinpath("web").joinpath(name).read_bytes()


class _Stopped(Exception):
    pass


class Hub:
    """Everything one running window needs: the engine, the agent, the event log and the HTTP server."""

    def __init__(self, cfg, cwd=None, keep=False, client=None):
        self.cfg = cfg
        self.client = client or Client(cfg["host"], cfg["port"], cfg["model_name"])
        self.session = Session()
        self.ui = WebUI(self.session)
        self.tools = Tools(cwd, backup_dir=config.BACKUP_DIR)
        self.agent = Agent(self.client, self.ui, self.tools, cfg)
        self.keep = keep or cfg.get("keep_server", False)
        self.token = secrets.token_urlsafe(24)
        self.engine = ("starting", "")
        self.started_engine = False
        self.busy = False
        self.boot_event = threading.Event()  # set once the engine answers and the warm-up has begun (or boot failed)
        self.boot_event.set()  # nothing to wait for unless run() starts a boot
        self.last_ping = time.monotonic()
        self.httpd = None
        self.port = 0
        self._lock = threading.Lock()
        self.stopping = threading.Event()

    # -- engine ---------------------------------------------------------------------------------------------
    def set_engine(self, state, message=""):
        self.engine = (state, message)
        self.session.emit("state", **self.state())

    def boot(self):
        """Start the AI engine if it is not running, then warm it up. Runs in the background."""
        try:
            if self.client.alive() is None:
                st = server.installed(self.cfg)
                if not st["ready"]:
                    self.set_engine("error", "The AI engine is not installed yet. Run `hey-alfred setup` in a terminal once (it downloads the model, about 19 GB).")
                    return
                self.set_engine("starting", "Starting the AI engine…")
                self.started_engine = True  # before starting: a Quit that arrives meanwhile must still stop it
                server.start(self.cfg)
                t0 = time.monotonic()

                def tick(_):
                    if self.stopping.is_set():
                        raise _Stopped

                if not server.wait_ready(self.client, tick=tick):
                    self.set_engine("error", "The AI engine did not start.\n" + server.log_tail(8))
                    server.stop()
                    self.started_engine = False
                    return
                self.set_engine("starting", f"Engine started in {time.monotonic() - t0:.0f}s")
            if self.stopping.is_set():
                return
            self.set_engine("warming", "Reading the instructions once. About 4–5 minutes on this CPU; you can already type.")
            self.agent.start_priming()
            self.boot_event.set()  # questions can queue now: ask() waits for the warm-up itself
            while self.agent.warming_up() and not self.stopping.is_set():
                time.sleep(0.5)
            if not self.stopping.is_set():
                self.set_engine("ready", "")
        except _Stopped:
            server.stop()  # Quit arrived while the engine was starting
        except Exception as e:  # noqa: BLE001
            self.set_engine("error", str(e))
        finally:
            self.boot_event.set()
            if self.stopping.is_set() and self.started_engine and not self.keep:
                server.stop()

    def state(self):
        eng, msg = self.engine
        return {"engine": eng, "message": msg, "mode": self.agent.mode, "cwd": self.tools.cwd, "busy": self.busy,
                "version": config.VERSION, "model": "Qwen3-Coder 30B" if "coder" in self.cfg["model_name"] else self.cfg["model_name"],
                "home": os.path.expanduser("~"), "memory_free": round(server.available_memory_bytes() / 2**30, 1)}

    # -- actions ------------------------------------------------------------------------------------------------
    def ask(self, text):
        with self._lock:
            if self.busy:
                return False
            self.busy = True
        self.session.emit("user", text=text)
        self.session.emit("state", **self.state())

        def work():
            try:
                self.agent._stopped = False
                if not self.boot_event.is_set():  # the engine is still starting: wait for it, then ask
                    self.ui.thinking(True, "Starting")
                    while not self.boot_event.wait(0.5):
                        if self.agent._stopped or self.stopping.is_set():
                            return
                    self.ui.thinking(False)
                    if self.engine[0] == "error":
                        self.ui.error(self.engine[1].splitlines()[0])
                        return
                self.agent.ask(text)
            except Exception as e:  # noqa: BLE001
                self.ui.error(f"something went wrong: {e}")
            finally:
                self.busy = False
                self.ui.thinking(False)
                self.session.emit("done")
                self.session.emit("state", **self.state())

        threading.Thread(target=work, daemon=True).start()
        return True

    def stop_request(self):
        self.agent.stop()
        self.session.abort_all()

    def set_mode(self, mode):
        if mode not in ("ask", "auto", "readonly"):
            return False
        self.agent.mode = mode
        self.session.emit("state", **self.state())
        return True

    def chdir(self, path):
        try:
            self.tools.chdir(path or "~")
        except ValueError as e:
            return str(e)
        self.agent.note_cwd_change()
        self.session.emit("state", **self.state())
        return None

    def clear(self):
        if self.busy:
            return False
        self.agent.clear()
        self.session.emit("cleared")
        self.set_engine("warming", "Starting a new conversation: warming up again (about 4–5 minutes).")
        threading.Thread(target=self._after_clear, daemon=True).start()
        return True

    def _after_clear(self):
        while self.agent.warming_up() and not self.stopping.is_set():
            time.sleep(0.5)
        self.set_engine("ready", "")

    def save(self):
        from .cli import save_session
        return save_session(self.agent)

    def shutdown(self):
        if self.stopping.is_set():
            return
        self.stopping.set()
        self.session.abort_all()
        self.session.close()
        try:
            os.remove(INFO_FILE)
        except OSError:
            pass
        if self.started_engine and not self.keep:
            server.stop()
        if self.httpd:
            threading.Thread(target=self.httpd.shutdown, daemon=True).start()

    def idle_watch(self):
        while not self.stopping.wait(5):
            if not self.busy and time.monotonic() - self.last_ping > IDLE_SHUTDOWN_SECONDS:
                self.shutdown()

    # -- http -------------------------------------------------------------------------------------------------------
    def serve(self, port=0):
        hub = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            server_version = "Hy"

            def log_message(self, *a):
                pass

            # --- guards ---
            def _host_ok(self):
                return self.headers.get("Host", "") in (f"127.0.0.1:{hub.port}", f"localhost:{hub.port}")

            def _cookie_ok(self):
                jar = self.headers.get("Cookie", "")
                for part in jar.split(";"):
                    k, _, v = part.strip().partition("=")
                    if k == "hy_t" and hmac.compare_digest(v, hub.token):
                        return True
                return False

            def _send(self, code, body=b"", ctype="application/json", headers=None):
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Referrer-Policy", "no-referrer")
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(body)

            def _json(self, obj, code=200):
                self._send(code, json.dumps(obj).encode())

            def _read_body(self):
                """Always consume the request body (even for refused requests), or its bytes would be read
                as the start of the next request on this connection."""
                n = int(self.headers.get("Content-Length") or 0)
                if n > 200_000:
                    self.close_connection = True
                    return None
                return self.rfile.read(n) if n else b""

            @staticmethod
            def _parse(raw):
                try:
                    return json.loads(raw or b"{}")
                except ValueError:
                    return None

            # --- GET ---
            def do_GET(self):
                if not self._host_ok():
                    return self._send(421, b'{"error":"bad host"}')
                url = urllib.parse.urlparse(self.path)
                q = urllib.parse.parse_qs(url.query)
                if url.path == "/api/ping" and not self.headers.get("Cookie"):
                    return self._json({"hy": True})  # lets `hey-alfred ui` tell that a window server is alive
                if url.path == "/" and "t" in q:
                    if hmac.compare_digest(q["t"][0], hub.token):
                        return self._send(302, b"", headers={"Location": "/", "Set-Cookie": f"hy_t={hub.token}; HttpOnly; SameSite=Strict; Path=/"})
                    return self._send(403, b"forbidden", "text/plain")
                if not self._cookie_ok():
                    return self._send(403, b"Open Hey-Alfred from its app icon or with `hey-alfred`.", "text/plain")
                if url.path == "/":
                    return self._send(200, _resource("index.html"), "text/html; charset=utf-8")
                if url.path == "/doto.ttf":
                    return self._send(200, _resource("doto.ttf"), "font/ttf", {"Cache-Control": "max-age=86400"})
                if url.path == "/icon.svg":
                    return self._send(200, _resource("icon.svg"), "image/svg+xml")
                if url.path == "/api/state":
                    hub.last_ping = time.monotonic()
                    return self._json(hub.state())
                if url.path == "/api/ping":
                    hub.last_ping = time.monotonic()
                    return self._json({"ok": True})
                if url.path == "/api/events":
                    since = int(q.get("since", [self.headers.get("Last-Event-ID") or 0])[0] or 0)
                    return self._events(since)
                self._send(404, b'{"error":"not found"}')

            def _events(self, since):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    while not hub.stopping.is_set():
                        batch, nxt = hub.session.read(since)
                        if not batch:
                            self.wfile.write(b": keepalive\n\n")
                        for i, ev in enumerate(batch):
                            self.wfile.write(f"id: {since + i + 1}\ndata: {json.dumps(ev)}\n\n".encode())
                        self.wfile.flush()
                        since = nxt
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                self.close_connection = True

            # --- POST ---
            def do_POST(self):
                raw = self._read_body()
                if raw is None:
                    return self._send(413, b'{"error":"too large"}')
                if not self._host_ok() or not self._cookie_ok() or self.headers.get("X-Hy") != "1":
                    return self._send(403, b'{"error":"forbidden"}')
                origin = self.headers.get("Origin")
                if origin and origin not in (f"http://127.0.0.1:{hub.port}", f"http://localhost:{hub.port}"):
                    return self._send(403, b'{"error":"forbidden"}')
                body = self._parse(raw)
                if not isinstance(body, dict):
                    return self._send(400, b'{"error":"bad request"}')
                path = urllib.parse.urlparse(self.path).path
                if path == "/api/ask":
                    text = str(body.get("text", "")).strip()[:20000]
                    if not text:
                        return self._json({"error": "empty"}, 400)
                    return self._json({"ok": True}) if hub.ask(text) else self._json({"error": "Alfred is busy with the last request"}, 409)
                if path == "/api/approve":
                    return self._json({"ok": hub.session.answer(int(body.get("id", 0)), str(body.get("choice", "no")))})
                if path == "/api/stop":
                    hub.stop_request()
                    return self._json({"ok": True})
                if path == "/api/mode":
                    return self._json({"ok": hub.set_mode(str(body.get("mode", "")))})
                if path == "/api/cd":
                    err = hub.chdir(str(body.get("path", "")))
                    return self._json({"ok": err is None, "error": err}, 200 if err is None else 400)
                if path == "/api/clear":
                    return self._json({"ok": hub.clear()})
                if path == "/api/save":
                    return self._json({"path": hub.save()})
                if path == "/api/quit":
                    self._json({"ok": True})
                    return hub.shutdown()
                self._send(404, b'{"error":"not found"}')

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        return self.httpd

    def url(self):
        return f"http://127.0.0.1:{self.port}/?t={self.token}"

    def write_info(self):
        os.makedirs(config.DATA_DIR, exist_ok=True)
        fd = os.open(INFO_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"port": self.port, "token": self.token, "pid": os.getpid()}, f)


def running_window():
    """URL of an already running Hy window server, or None."""
    import http.client
    try:
        with open(INFO_FILE, encoding="utf-8") as f:
            info = json.load(f)
        c = http.client.HTTPConnection("127.0.0.1", info["port"], timeout=2)
        c.request("GET", "/api/ping")
        ok = json.loads(c.getresponse().read()).get("hy") is True
        c.close()
        return f"http://127.0.0.1:{info['port']}/?t={info['token']}" if ok else None
    except (OSError, ValueError, KeyError):
        return None


def open_window(url):
    """Open Hy in its own app window (Chrome/Chromium 'app mode': no tabs or address bar), else the default browser."""
    for exe in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "brave-browser", "microsoft-edge"):
        path = shutil.which(exe)
        if path:
            subprocess.Popen([path, f"--app={url}", "--window-size=1000,820", "--class=HeyAlfred"], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, start_new_session=True)
            return "app window"
    webbrowser.open(url)
    return "browser"


def run(cfg, cwd=None, keep=False, open_it=True, say=print):
    """Start (or reuse) the window server. Blocks until it is closed."""
    existing = running_window()
    if existing:
        if open_it:
            open_window(existing)
        say("Hey-Alfred is already running: opened its window.")
        return 0
    hub = Hub(cfg, cwd=cwd, keep=keep)
    hub.serve()
    hub.write_info()
    import signal

    def bye(*_):
        hub.shutdown()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, bye)
        except ValueError:
            pass
    hub.boot_event.clear()
    threading.Thread(target=hub.boot, daemon=True).start()
    threading.Thread(target=hub.idle_watch, daemon=True).start()
    say(f"Hey-Alfred is running at http://127.0.0.1:{hub.port}/  (close it with the Quit button or Ctrl+C here)")
    if open_it:
        open_window(hub.url())
    else:
        say("Open this address to use it: " + hub.url())
    try:
        hub.httpd.serve_forever()
    finally:
        hub.shutdown()
        hub.httpd.server_close()
    return 0


def stop_window():
    """Stop a running Hy window server (it stops its own engine on the way out). True if one was running."""
    try:
        with open(INFO_FILE, encoding="utf-8") as f:
            pid = int(json.load(f)["pid"])
        os.kill(pid, 15)
    except (OSError, ValueError, KeyError):
        return False
    for _ in range(100):
        try:
            os.kill(pid, 0)
        except OSError:
            return True
        time.sleep(0.1)
    return True


def launch_detached(cfg, ui, cwd=None, argv=None):
    """Open the window from a terminal without tying the terminal up: the server runs in the background."""
    existing = running_window()
    if existing:
        open_window(existing)
        ui.line("  Hey-Alfred is already running: opened its window.")
        return 0
    cmd = list(getattr(sys, "orig_argv", None) or [sys.executable, "-m", "hy", *(argv or [])])
    cmd.append("--foreground")
    log = open(os.path.join(config.LOG_DIR, "ui.log"), "a")
    subprocess.Popen(cmd, stdout=log, stderr=log, stdin=subprocess.DEVNULL, start_new_session=True, cwd=cwd or os.getcwd())
    for _ in range(100):
        if running_window():
            break
        time.sleep(0.1)
    ui.line("  Hey-Alfred is opening in its own window. It runs in the background:")
    ui.line("  quit it from the window's menu, or run  hey-alfred stop")
    return 0
