import http.client
import json
import os
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from fakeserver import Fake  # noqa: E402

from hy import webui  # noqa: E402
from hy.client import Client  # noqa: E402

CFG = {"mode": "ask", "ctx": 8192, "max_steps": 5, "temperature": 0.2, "host": "127.0.0.1", "port": 1, "model_name": "m", "keep_server": False}


class Window:
    def __init__(self, replies):
        self.fake = Fake(replies)
        self.dir = tempfile.mkdtemp()
        self.hub = webui.Hub(dict(CFG), cwd=self.dir, client=Client("127.0.0.1", self.fake.port, "m"))
        self.hub.serve()
        threading.Thread(target=self.hub.httpd.serve_forever, daemon=True).start()
        self.port = self.hub.port
        self.cookie = f"hy_t={self.hub.token}"

    def close(self):
        self.hub.stopping.set()
        self.hub.session.close()
        self.hub.httpd.shutdown()
        self.fake.close()

    def req(self, method, path, body=None, headers=None, cookie=True, host=None, post_header=True):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = {"Host": host or f"127.0.0.1:{self.port}"}
        if cookie:
            h["Cookie"] = self.cookie
        if method == "POST":
            h["Content-Type"] = "application/json"
            if post_header:
                h["X-Hy"] = "1"
        h.update(headers or {})
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        data = r.read()
        return r.status, dict(r.getheaders()), data

    def events_until(self, stop_type, since=0, timeout=10, count=1):
        """Read the SSE stream until [count] events of [stop_type] arrived. Returns all events read."""
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        c.request("GET", f"/api/events?since={since}", headers={"Host": f"127.0.0.1:{self.port}", "Cookie": self.cookie})
        r = c.getresponse()
        got, seen = [], 0
        end = time.time() + timeout
        while time.time() < end:
            line = r.readline().decode().strip()
            if line.startswith("data:"):
                ev = json.loads(line[5:])
                got.append(ev)
                if ev["type"] == stop_type:
                    seen += 1
                    if seen >= count:
                        break
        c.close()
        return got


class GuardsTest(unittest.TestCase):
    def setUp(self):
        self.w = Window([])
        self.addCleanup(self.w.close)

    def test_page_and_state_need_the_token(self):
        self.assertEqual(self.w.req("GET", "/", cookie=False)[0], 403)
        self.assertEqual(self.w.req("GET", "/api/state", cookie=False)[0], 403)
        self.assertEqual(self.w.req("GET", "/?t=wrong", cookie=False)[0], 403)
        code, headers, _ = self.w.req("GET", f"/?t={self.w.hub.token}", cookie=False)
        self.assertEqual(code, 302)
        self.assertIn("hy_t=", headers["Set-Cookie"])
        self.assertIn("HttpOnly", headers["Set-Cookie"])
        self.assertIn("SameSite=Strict", headers["Set-Cookie"])
        code, _, body = self.w.req("GET", "/")
        self.assertEqual(code, 200)
        self.assertIn(b"Hey-Alfred", body)
        self.assertEqual(self.w.req("GET", "/doto.ttf")[0], 200)
        self.assertEqual(self.w.req("GET", "/icon.svg")[0], 200)
        self.assertEqual(self.w.req("GET", "/api/state")[0], 200)

    def test_wrong_host_is_refused(self):
        self.assertEqual(self.w.req("GET", "/api/state", host="evil.example:80")[0], 421)

    def test_posts_need_cookie_header_and_same_origin(self):
        body = {"text": "hi"}
        self.assertEqual(self.w.req("POST", "/api/ask", body, cookie=False)[0], 403)
        self.assertEqual(self.w.req("POST", "/api/ask", body, post_header=False)[0], 403)
        self.assertEqual(self.w.req("POST", "/api/ask", body, headers={"Origin": "https://evil.example"})[0], 403)
        self.assertEqual(self.w.req("POST", "/api/mode", {"mode": "nonsense"})[2], b'{"ok": false}')

    def test_ping_without_cookie_only_identifies(self):
        code, _, body = self.w.req("GET", "/api/ping", cookie=False)
        self.assertEqual((code, json.loads(body)), (200, {"hy": True}))


class FlowTest(unittest.TestCase):
    def tearDown(self):
        self.w.close()

    def test_ask_streams_events_in_order(self):
        self.w = Window([{"content": "Hello from the model."}])
        self.assertEqual(self.w.req("POST", "/api/ask", {"text": "hi"})[0], 200)
        evs = self.w.events_until("done")
        types = [e["type"] for e in evs]
        self.assertEqual(types[0], "user")
        text = "".join(e["t"] for e in evs if e["type"] == "text")
        self.assertEqual(text, "Hello from the model.")
        self.assertLess(types.index("user"), types.index("text"))
        self.assertLess(types.index("text"), types.index("end_text"))
        self.assertEqual(types[-1], "done")
        # a reloaded page replays the whole conversation
        again = self.w.events_until("done")
        self.assertEqual([e["type"] for e in again], types)

    def test_approval_buttons_run_and_deny(self):
        self.w = Window([{"tool_calls": [("write", {"path": "n.txt", "content": "hi"})]}, {"content": "Written."},
                         {"tool_calls": [("write", {"path": "m.txt", "content": "no"})]}, {"content": "Skipped."}])
        self.w.req("POST", "/api/ask", {"text": "make n"})
        ev = [e for e in self.w.events_until("approval") if e["type"] == "approval"][0]
        self.assertEqual(ev["level"], "normal")
        self.assertIn("+hi", ev["detail"])
        self.assertFalse(os.path.exists(os.path.join(self.w.dir, "n.txt")))  # nothing before the click
        self.w.req("POST", "/api/approve", {"id": ev["id"], "choice": "yes"})
        self.w.events_until("done")
        self.assertEqual(open(os.path.join(self.w.dir, "n.txt")).read(), "hi")
        self.w.req("POST", "/api/ask", {"text": "make m"})
        ev2 = [e for e in self.w.events_until("approval", count=2) if e["type"] == "approval"][-1]
        self.w.req("POST", "/api/approve", {"id": ev2["id"], "choice": "no"})
        evs = self.w.events_until("done", count=2)
        self.assertFalse(os.path.exists(os.path.join(self.w.dir, "m.txt")))
        self.assertIn("no", [e["choice"] for e in evs if e["type"] == "approval_done"])

    def test_busy_then_stop_releases_the_approval(self):
        self.w = Window([{"tool_calls": [("write", {"path": "a.txt", "content": "x"})]}])
        self.w.req("POST", "/api/ask", {"text": "go"})
        self.w.events_until("approval")
        code, _, body = self.w.req("POST", "/api/ask", {"text": "second"})
        self.assertEqual(code, 409)
        self.assertTrue(self.w.hub.state()["busy"])
        self.w.req("POST", "/api/stop")
        evs = self.w.events_until("done")
        self.assertIn("abort", [e["choice"] for e in evs if e["type"] == "approval_done"])
        self.assertFalse(os.path.exists(os.path.join(self.w.dir, "a.txt")))
        deadline = time.time() + 5
        while self.w.hub.busy and time.time() < deadline:
            time.sleep(0.05)
        self.assertFalse(self.w.hub.busy)

    def test_mode_cd_clear(self):
        self.w = Window([{"content": "Ready"}])
        self.assertEqual(self.w.req("POST", "/api/mode", {"mode": "auto"})[2], b'{"ok": true}')
        self.assertEqual(self.w.hub.state()["mode"], "auto")
        sub = os.path.join(self.w.dir, "sub")
        os.mkdir(sub)
        self.assertEqual(self.w.req("POST", "/api/cd", {"path": "sub"})[0], 200)
        self.assertEqual(self.w.hub.state()["cwd"], os.path.realpath(sub))
        self.assertEqual(self.w.req("POST", "/api/cd", {"path": "nowhere"})[0], 400)
        self.w.req("POST", "/api/clear")
        evs = self.w.events_until("cleared")
        self.assertEqual(evs[-1]["type"], "cleared")


class SessionTest(unittest.TestCase):
    def test_old_events_are_dropped_not_the_new(self):
        old = webui.MAX_EVENTS
        webui.MAX_EVENTS = 5
        try:
            s = webui.Session()
            for i in range(8):
                s.emit("text", t=str(i))
            batch, nxt = s.read(0, wait=0)
            self.assertEqual([e["t"] for e in batch], ["3", "4", "5", "6", "7"])
            self.assertEqual(nxt, 8)
            self.assertEqual(s.read(8, wait=0)[0], [])
        finally:
            webui.MAX_EVENTS = old


if __name__ == "__main__":
    unittest.main()


class BootTest(unittest.TestCase):
    def test_a_question_asked_while_the_engine_starts_waits_for_it(self):
        w = Window([{"content": "Hello."}])
        self.addCleanup(w.close)
        w.hub.boot_event.clear()  # the engine is still starting
        self.assertEqual(w.req("POST", "/api/ask", {"text": "hi"})[0], 200)
        time.sleep(1.5)
        self.assertEqual(w.fake.requests, [])  # nothing was sent to an engine that is not up yet
        w.hub.boot_event.set()
        evs = w.events_until("done")
        self.assertEqual("".join(e["t"] for e in evs if e["type"] == "text"), "Hello.")
        self.assertIn("Starting", [e.get("label") for e in evs if e["type"] == "thinking"])

    def test_quit_while_the_engine_is_starting_stops_it(self):
        stopped = []
        saved = {k: getattr(webui.server, k) for k in ("installed", "start", "wait_ready", "stop")}
        webui.server.installed = lambda cfg: {"ready": True}
        webui.server.start = lambda cfg: None
        webui.server.wait_ready = lambda client, tick=None, **kw: (tick(0), True)[1]
        webui.server.stop = lambda: stopped.append(1)
        try:
            hub = webui.Hub(dict(CFG), cwd=tempfile.mkdtemp())  # no engine answers on port 1
            hub.stopping.set()  # Quit arrived while the engine was starting
            hub.boot()
        finally:
            for k, v in saved.items():
                setattr(webui.server, k, v)
        self.assertTrue(stopped)
        self.assertTrue(hub.boot_event.is_set())
