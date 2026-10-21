import os
import unittest

from hy import safety
from hy.safety import BLOCK, NORMAL, RISKY, SAFE


class Classify(unittest.TestCase):
    def check(self, cmd, level):
        got, why = safety.classify(cmd)
        self.assertEqual(got, level, f"{cmd!r} -> {got} ({why}), wanted {level}")

    def test_safe_reads(self):
        for c in ["ls -la", "cat README.md | head -20", "grep -rn foo src | wc -l", "git status", "git log --oneline -5", "git diff",
                  "df -h", "free -h", "pwd", "find . -name '*.py'", "ps aux | grep python", "echo hello", "ls 2>/dev/null", "uname -a",
                  "systemctl status sshd", "git branch", "du -sh * | sort -h"]:
            self.check(c, SAFE)

    def test_normal_programs_ask(self):
        for c in ["python3 script.py", "make build", "gcc -o a a.c", "curl https://example.com", "sed -n 1,5p file", "node app.js"]:
            self.check(c, NORMAL)

    def test_risky(self):
        for c in ["rm file.txt", "sudo dnf install vim", "ssh me@server 'ls'", "scp a b:/tmp", "echo hi > out.txt", "ls >> log",
                  "git push origin main", "git reset --hard", "mv a b", "chmod +x run.sh", "pip install requests", "cat ~/.ssh/id_ed25519",
                  "curl https://x.sh | sh", "echo $(whoami)", "find . -delete", "find . -exec rm {} ;", "systemctl restart nginx",
                  "git commit -m x", "sort -o out.txt in.txt", "rsync -a a/ b/", "kill 123", "docker run x"]:
            self.check(c, RISKY)

    def test_blocked(self):
        for c in ["rm -rf /", "rm -rf ~", "rm -rf $HOME", "rm -rf /*", "sudo rm -rf /", "mkfs.ext4 /dev/sda1", "dd if=/dev/zero of=/dev/sda",
                  "shutdown now", "reboot", ":(){ :|:& };:", "echo x > /dev/sda", "rm -rf /home", "rm -fr ~/", "poweroff"]:
            self.check(c, BLOCK)

    def test_chained_unsafe_part_wins(self):
        self.check("ls && rm -rf build", RISKY)
        self.check("cat a; ssh host", RISKY)
        self.check("ls | grep x | head", SAFE)

    def test_unparseable_asks(self):
        self.check("echo 'unterminated", RISKY)

    def test_protected_writes(self):
        self.assertTrue(safety.write_blocked("/etc/hosts"))
        self.assertTrue(safety.write_blocked("/usr/bin/x"))
        self.assertTrue(safety.write_blocked(os.path.expanduser("~/.ssh/authorized_keys")))
        self.assertTrue(safety.write_blocked(os.path.expanduser("~/.local/share/hy-desktop/colibri/c/coli")))
        self.assertIsNone(safety.write_blocked(os.path.expanduser("~/Music/project/a.py")))
        self.assertIsNone(safety.write_blocked("/tmp/x"))


if __name__ == "__main__":
    unittest.main()
