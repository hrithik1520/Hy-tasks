import os
import unittest

PACKAGING = os.path.join(os.path.dirname(__file__), "..", "packaging")


class LauncherTest(unittest.TestCase):
    def test_the_app_icon_asks_for_the_window(self):
        # Run from an icon there is no terminal, and plain `hey-alfred` then only prints help: the icon must say `ui`.
        exec_line = [l for l in open(os.path.join(PACKAGING, "hey-alfred.desktop")) if l.startswith("Exec=")][0].strip()
        self.assertTrue(exec_line.endswith("@BIN@ ui"), exec_line)
        self.assertIn("Terminal=false", open(os.path.join(PACKAGING, "hey-alfred.desktop")).read())


if __name__ == "__main__":
    unittest.main()
