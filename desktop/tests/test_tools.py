import os
import tempfile
import unittest

from hy import safety
from hy.tools import Tools


class ToolsTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.b = tempfile.mkdtemp()
        self.t = Tools(self.d, backup_dir=self.b)

    def test_run_output_and_exit_code(self):
        self.assertIn("hello", self.t.execute("run", {"command": "echo hello"}))
        out = self.t.execute("run", {"command": "echo oops >&2; exit 3"})
        self.assertIn("oops", out)
        self.assertIn("exit code 3", out)

    def test_run_uses_cwd_and_times_out(self):
        open(os.path.join(self.d, "a.txt"), "w").write("x")
        self.assertIn("a.txt", self.t.execute("run", {"command": "ls"}))
        out = self.t.execute("run", {"command": "sleep 5", "timeout": 1})  # the model cannot set this; Hy's own callers can
        self.assertIn("stopped after 1s", out)

    def test_run_output_is_clipped(self):
        out = self.t.execute("run", {"command": "seq 1 20000"})
        self.assertLess(len(out), 6000)
        self.assertIn("left out", out)

    def test_write_edit_read_with_backup(self):
        self.assertIn("created", self.t.execute("write", {"path": "n.py", "content": "a = 1\nb = 2\n"}))
        self.assertIn("a = 1", self.t.execute("run", {"command": "cat n.py"}))
        self.assertIn("edited", self.t.execute("edit", {"path": "n.py", "old": "b = 2", "new": "b = 3"}))
        with open(os.path.join(self.d, "n.py")) as f:
            self.assertIn("b = 3", f.read())
        backups = [f for r, _, fs in os.walk(self.b) for f in fs]
        self.assertEqual(backups, ["n.py"])
        self.assertIn("overwrote", self.t.execute("write", {"path": "n.py", "content": "z"}))

    def test_edit_errors(self):
        self.t.execute("write", {"path": "n.txt", "content": "x\nx\n"})
        self.assertIn("2 times", self.t.execute("edit", {"path": "n.txt", "old": "x", "new": "y"}))
        self.assertIn("not found", self.t.execute("edit", {"path": "n.txt", "old": "q", "new": "y"}))

    def test_protected_paths_refused_even_if_called(self):
        self.assertIn("not allowed", self.t.execute("write", {"path": "/etc/hy-test", "content": "x"}))
        self.assertEqual(self.t.check("run", {"command": "cat ~/.ssh/id_rsa"})[0], safety.RISKY)

    def test_check_levels_and_diff_preview(self):
        self.assertEqual(self.t.check("run", {"command": "ls"})[0], safety.SAFE)
        self.assertEqual(self.t.check("run", {"command": "rm x"})[0], safety.RISKY)
        self.assertEqual(self.t.check("write", {"path": "/etc/passwd", "content": ""})[0], safety.BLOCK)
        self.t.execute("write", {"path": "f.txt", "content": "one\ntwo\n"})
        level, title, detail = self.t.check("edit", {"path": "f.txt", "old": "two", "new": "three"})
        self.assertEqual(level, safety.NORMAL)
        self.assertIn("-two", detail)
        self.assertIn("+three", detail)

    def test_chdir(self):
        sub = os.path.join(self.d, "sub")
        os.mkdir(sub)
        self.assertEqual(self.t.chdir("sub"), os.path.realpath(sub))
        with self.assertRaises(ValueError):
            self.t.chdir("nope")

    def test_bad_arguments_do_not_crash(self):
        self.assertIn("error", self.t.execute("run", {}))
        self.assertIn("error", self.t.execute("nothing", {}))


if __name__ == "__main__":
    unittest.main()


class ModelStateTest(unittest.TestCase):
    def test_partial_then_complete(self):
        import json
        from hy import server
        d = tempfile.mkdtemp()
        self.assertEqual(server.model_state(d)[0], "missing")
        open(os.path.join(d, "config.json"), "w").write("{}")
        self.assertEqual(server.model_state(d)[0], "partial")  # config.json arrives first, but that is not a model
        manifest = {"complete": True, "files": [{"path": "a.bin", "size": 4}, {"path": "b.bin", "size": 6}]}
        json.dump(manifest, open(os.path.join(d, ".colibri-download.json"), "w"))
        open(os.path.join(d, "a.bin"), "wb").write(b"1234")
        open(os.path.join(d, "b.bin.part"), "wb").write(b"123")
        self.assertEqual(server.model_state(d), ("partial", 7, 10))
        os.rename(os.path.join(d, "b.bin.part"), os.path.join(d, "b.bin"))
        open(os.path.join(d, "b.bin"), "ab").write(b"456")
        self.assertEqual(server.model_state(d), ("complete", 10, 10))
        open(os.path.join(d, "b.bin"), "wb").write(b"12")  # truncated file
        self.assertEqual(server.model_state(d)[0], "partial")
