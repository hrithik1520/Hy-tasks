"""A small client for colibri's OpenAI-compatible chat endpoint, with streaming and tool calls."""
import http.client
import json
import socket
import time


class ServerError(Exception):
    pass


class Client:
    def __init__(self, host="127.0.0.1", port=8000, model="", timeout=3600):
        self.host, self.port, self.model, self.timeout = host, port, model, timeout
        self._active = None  # the connection of the request in flight, so abort() can end it

    def _connect(self, timeout=None):
        return http.client.HTTPConnection(self.host, self.port, timeout=timeout or self.timeout)

    def alive(self):
        """True when the server answers /v1/models. Returns the model id it serves, or None."""
        try:
            c = self._connect(3)
            c.request("GET", "/v1/models")
            r = c.getresponse()
            data = json.loads(r.read() or b"{}")
            c.close()
            if r.status == 200:
                models = data.get("data") or []
                return (models[0].get("id") if models else "") or ""
        except (OSError, ValueError, http.client.HTTPException):
            pass
        return None

    def chat(self, messages, tools=None, on_text=None, temperature=0.2, max_tokens=1500, cancel=None):
        """Send a chat request and stream the answer. Returns
        {'content': str, 'tool_calls': [{'id','name','arguments'(str)}], 'finish': str, 'tokens': int, 'seconds': float, 'first_token': float}.
        [on_text] receives text chunks. [cancel] is a callable returning True to stop early."""
        body = {"model": self.model, "messages": messages, "stream": True, "temperature": temperature, "max_tokens": max_tokens}
        if tools:
            body["tools"] = tools
        conn = self._connect()
        self._active = conn
        t0 = time.monotonic()
        try:
            conn.request("POST", "/v1/chat/completions", json.dumps(body), {"Content-Type": "application/json"})
            resp = conn.getresponse()
            if resp.status != 200:
                detail = resp.read().decode("utf-8", "replace")[:500]
                raise ServerError(f"the AI engine answered {resp.status}: {detail}")
            return self._read_stream(resp, on_text, t0, cancel)
        except (OSError, http.client.HTTPException) as e:
            raise ServerError(f"lost the AI engine: {e}") from None
        finally:
            self._active = None
            conn.close()

    def abort(self):
        """End the request in flight from another thread (its chat() call then raises ServerError)."""
        conn = self._active
        sock = getattr(conn, "sock", None)
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    @staticmethod
    def _read_stream(resp, on_text, t0, cancel):
        content, calls, finish, tokens, first = [], {}, "", 0, 0.0
        while True:
            if cancel and cancel():
                finish = "cancelled"
                break
            raw = resp.readline()
            if not raw:
                break
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                break
            try:
                ev = json.loads(payload)
            except ValueError:
                continue
            if "error" in ev:
                raise ServerError(str(ev["error"])[:400])
            for ch in ev.get("choices") or []:
                d = ch.get("delta") or {}
                text = d.get("content")
                if text:
                    if not first:
                        first = time.monotonic() - t0
                    content.append(text)
                    tokens += 1
                    if on_text:
                        on_text(text)
                for tc in d.get("tool_calls") or []:
                    i = tc.get("index", len(calls))
                    cur = calls.setdefault(i, {"id": "", "name": "", "arguments": ""})
                    cur["id"] = tc.get("id") or cur["id"]
                    fn = tc.get("function") or {}
                    cur["name"] += fn.get("name") or ""
                    args = fn.get("arguments")
                    cur["arguments"] += args if isinstance(args, str) else (json.dumps(args) if args else "")
                    if not first:
                        first = time.monotonic() - t0
                if ch.get("finish_reason"):
                    finish = ch["finish_reason"]
            usage = ev.get("usage")
            if usage and usage.get("completion_tokens"):
                tokens = usage["completion_tokens"]
        return {
            "content": "".join(content),
            "tool_calls": [calls[i] for i in sorted(calls)],
            "finish": finish, "tokens": tokens, "seconds": time.monotonic() - t0, "first_token": first,
        }
