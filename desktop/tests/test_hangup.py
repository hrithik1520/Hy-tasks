import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from fakeserver import Fake  # noqa: E402

SRC = os.path.join(os.path.dirname(__file__), "..", "src")


class HangupTest(unittest.TestCase):
    def test_closing_the_terminal_still_saves_the_session_and_exits(self):
        home = tempfile.mkdtemp()
        f = Fake([])
        self.addCleanup(f.close)
        env = dict(os.environ, PYTHONPATH=SRC, HOME=home, XDG_CONFIG_HOME="", XDG_DATA_HOME="")
        env.pop("XDG_CONFIG_HOME"), env.pop("XDG_DATA_HOME")
        # a pseudo-terminal, as in a real terminal window
        import pty
        pid, fd = pty.fork()
        if pid == 0:
            os.execvpe(sys.executable, [sys.executable, "-m", "hy", "chat", "--host", "127.0.0.1", "--port", str(f.port)], env)
        time.sleep(2.5)
        os.kill(pid, signal.SIGHUP)
        end = time.time() + 10
        status = None
        while time.time() < end:
            p, status = os.waitpid(pid, os.WNOHANG)
            if p:
                break
            time.sleep(0.1)
        else:
            os.kill(pid, signal.SIGKILL)
            self.fail("hy did not exit after SIGHUP")
        self.assertTrue(os.WIFEXITED(status), "should leave normally, not be killed by the signal")
        self.assertTrue(os.path.isfile(os.path.join(home, ".local", "share", "hy-desktop", "history")))  # reached the cleanup code


if __name__ == "__main__":
    unittest.main()


class DetachTest(unittest.TestCase):
    def test_plain_hy_opens_the_window_in_the_background_and_hy_stop_closes_it(self):
        import json
        import pty
        home, bindir = tempfile.mkdtemp(), tempfile.mkdtemp()
        record = os.path.join(bindir, "chrome-args")
        fake = os.path.join(bindir, "google-chrome")  # stands in for the browser: records what it was asked to open
        with open(fake, "w") as fh:
            fh.write(f'#!/bin/sh\necho "$@" > {record}\n')
        os.chmod(fake, 0o755)
        f = Fake([{"content": "Ready"}])
        self.addCleanup(f.close)
        env = dict(os.environ, PYTHONPATH=SRC, HOME=home, PATH=bindir)
        env.pop("XDG_CONFIG_HOME", None), env.pop("XDG_DATA_HOME", None)
        pid, fd = pty.fork()
        if pid == 0:
            os.execvpe(sys.executable, [sys.executable, "-m", "hy", "--host", "127.0.0.1", "--port", str(f.port)], env)
        end, status = time.time() + 15, None
        while time.time() < end:
            p, status = os.waitpid(pid, os.WNOHANG)
            if p:
                break
            time.sleep(0.1)
        else:
            os.kill(pid, signal.SIGKILL)
            self.fail("plain `hy` should return the terminal; it kept running in the foreground")
        self.assertTrue(os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0)
        info = json.load(open(os.path.join(home, ".local", "share", "hy-desktop", "ui.json")))  # the background server is up
        time.sleep(0.5)
        self.assertIn(f"--app=http://127.0.0.1:{info['port']}/?t=", open(record).read())
        subprocess.run([sys.executable, "-m", "hy", "stop"], env=env, check=True, capture_output=True, timeout=20)
        for _ in range(50):
            try:
                os.kill(info["pid"], 0)
            except OSError:
                break
            time.sleep(0.1)
        else:
            os.kill(info["pid"], signal.SIGKILL)
            self.fail("`hy stop` did not close the window server")
