import http.server
import threading
import unittest

from hy import webinfo

PAGE = b"""<!doctype html><html lang="en"><head><title>Yacht Rentals Phuket</title>
<meta name="description" content="Rent a yacht in Phuket."><link rel="canonical" href="https://x.test/">
<script type="application/ld+json">{}</script><script>var secret = 1;</script></head>
<body><h1>Rent a yacht</h1><p>Best <b>yachts</b> in Phuket.</p><h2>Prices</h2><img src="a.jpg" alt="boat"><img src="b.jpg">
<a href="/about">About</a><a href="https://other.test/x">Other</a></body></html>"""


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/missing":
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"<html><title>Not here</title></html>")
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Server", "testd")
        self.end_headers()
        self.wfile.write(PAGE)


class WebTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def test_report_has_the_facts(self):
        r = webinfo.fetch(self.base + "/")
        for needle in ["Status: 200", "server ", "Title (20 chars): Yacht Rentals Phuket", "Meta description (23 chars): Rent a yacht in Phuket.",
                       "Canonical: https://x.test/", "Language: en", "H1 count: 1", "JSON-LD schema blocks: 1", "1 internal, 1 external",
                       "Images: 2 (1 without alt text)", "h1 Rent a yacht", "  h2 Prices", "Best yachts in Phuket."]:
            self.assertIn(needle, r)
        self.assertNotIn("secret", r)
        self.assertEqual(r.split("Text:\n")[1].splitlines()[0], "Rent a yacht")  # the title is not repeated in the body text
        self.assertIn("About Other", r)

    def test_error_pages_and_bad_urls(self):
        self.assertIn("Status: 404", webinfo.fetch(self.base + "/missing"))
        with self.assertRaises(ValueError):
            webinfo.fetch("ftp://example.com/x")
        with self.assertRaises(ValueError):
            webinfo.fetch("http://127.0.0.1:1/")

    def test_missing_title_is_reported(self):
        r = webinfo.summarize("u", 200, {"Content-Type": "text/html"}, b"<html><body><p>hi</p></body></html>", 0.1, "u")
        self.assertIn("Title (0 chars): (missing)", r)
        self.assertIn("Meta description (0 chars): (missing)", r)


if __name__ == "__main__":
    unittest.main()
