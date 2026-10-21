"""A tiny OpenAI-compatible server for tests: replays scripted replies as server-sent events."""
import http.server
import json
import threading


class Fake:
    def __init__(self, replies):
        self.replies = list(replies)  # each: {"content": str} or {"tool_calls": [(name, args_dict)]}
        self.requests = []
        fake = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                body = json.dumps({"data": [{"id": "fake-model"}]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                fake.requests.append(json.loads(self.rfile.read(n)))
                r = fake.replies.pop(0) if fake.replies else {"content": "(no more scripted replies)"}
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()

                def ev(delta, finish=None):
                    self.wfile.write(("data: " + json.dumps({"choices": [{"delta": delta, "finish_reason": finish}]}) + "\n\n").encode())
                    self.wfile.flush()

                if "content" in r:
                    text = r["content"]
                    for i in range(0, len(text), 4):
                        ev({"content": text[i:i + 4]})
                if "tool_calls" in r:
                    for i, (name, args) in enumerate(r["tool_calls"]):
                        a = json.dumps(args)
                        ev({"tool_calls": [{"index": i, "id": f"c{i}", "function": {"name": name, "arguments": a[: len(a) // 2]}}]})
                        ev({"tool_calls": [{"index": i, "function": {"arguments": a[len(a) // 2:]}}]})
                ev({}, "tool_calls" if "tool_calls" in r else "stop")
                self.wfile.write(b"data: [DONE]\n\n")

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
