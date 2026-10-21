"""What the model may run. Every shell command is classified before anything happens:

  BLOCK  never runs (disk wipes, rm -rf on home or root, power off, fork bombs)
  RISKY  always asks, even in auto mode (sudo, rm, ssh, installs, writes by redirection, git push…)
  SAFE   read-only (ls, cat, grep, git status…): runs without asking in the default mode
  NORMAL anything else: asks in the default mode, runs in auto mode

The classifier is deliberately conservative: when it cannot tell, it asks.
"""
import os
import re
import shlex

SAFE, NORMAL, RISKY, BLOCK = "safe", "normal", "risky", "block"

SAFE_COMMANDS = {
    "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "pwd", "date", "whoami", "uname", "df", "du",
    "free", "ps", "uptime", "stat", "file", "which", "type", "tree", "sort", "uniq", "cut", "tr", "id", "hostname",
    "lsblk", "nproc", "echo", "printf", "basename", "dirname", "realpath", "readlink", "env", "printenv", "lscpu",
    "diff", "cmp", "md5sum", "sha256sum", "sha1sum", "column", "nl", "tac", "rev", "fold", "seq", "true", "false",
    "test", "[", "jq", "ss", "ip", "lspci", "lsusb", "locale", "groups", "w", "who", "top", "htop", "less", "more",
}
SAFE_GIT = {"status", "log", "diff", "show", "branch", "remote", "ls-files", "rev-parse", "describe", "blame", "tag", "config"}
SAFE_SYSTEMCTL = {"status", "is-active", "is-enabled", "list-units", "list-unit-files", "show", "cat"}

ALWAYS_RISKY_WORDS = {
    "sudo", "su", "doas", "rm", "rmdir", "mv", "chmod", "chown", "chgrp", "dd", "kill", "pkill", "killall", "ssh", "scp",
    "sftp", "rsync", "dnf", "yum", "rpm", "apt", "apt-get", "pip", "pip3", "pipx", "npm", "npx", "yarn", "pnpm", "cargo",
    "flatpak", "snap", "systemctl", "service", "crontab", "at", "mount", "umount", "iptables", "nft", "firewall-cmd",
    "useradd", "userdel", "usermod", "passwd", "truncate", "shred", "wipefs", "fdisk", "parted", "mkswap", "swapoff",
    "ln", "install", "tee", "ftp", "nc", "ncat", "socat", "telnet", "docker", "podman", "eval", "exec", "source",
}
BLOCK_WORDS = {"mkfs", "wipefs", "shutdown", "reboot", "poweroff", "halt", "init", "telinit", "fdisk", "sfdisk", "cfdisk"}
BLOCK_PATTERNS = [
    re.compile(r":\(\)\s*\{"),  # fork bomb
    re.compile(r">\s*/dev/(sd|nvme|vd|hd|mmcblk)"),
    re.compile(r"\bdd\b[^|;&]*\bof=/dev/"),
    re.compile(r"\bmkfs(\.\w+)?\b"),
    re.compile(r"\bchmod\s+(-\w+\s+)*[0-7]*777\s+/(\s|$)"),
]
SENSITIVE = re.compile(r"(\.ssh/(id_|.*\.pem)|\.gnupg|/etc/shadow|/etc/sudoers|\.aws/credentials|\.netrc|\.env\b|\bid_(rsa|ed25519|ecdsa))")

_SEP = {"|", "&&", "||", ";", "&", "\n"}


def _tokens(cmd):
    lex = shlex.shlex(cmd, posix=True, punctuation_chars=True)
    lex.whitespace_split = True
    lex.commenters = ""
    return list(lex)


def _segments(tokens):
    seg, out = [], []
    for t in tokens:
        if t in _SEP:
            if seg:
                out.append(seg)
            seg = []
        else:
            seg.append(t)
    if seg:
        out.append(seg)
    return out


WRAPPERS = {"sudo", "doas", "env", "command", "nice", "nohup", "time", "exec", "timeout", "stdbuf", "ionice", "builtin"}


def _unwrap(words):
    """Drop leading wrappers, their flags and VAR=value assignments: 'sudo -u x rm -rf /' -> ['rm', '-rf', '/']."""
    i = 0
    while i < len(words):
        w = words[i]
        if os.path.basename(w) in WRAPPERS or re.fullmatch(r"[A-Za-z_]\w*=.*", w) or (w.startswith("-") and i > 0):
            i += 1
        else:
            break
    return words[i:]


def _is_redirect(tok):
    return bool(re.fullmatch(r"\d*[<>]+&?\d*", tok)) or tok in ("<<", "<<<", ">>", ">&", "&>")


def _write_redirect(seg):
    """True if the segment redirects output anywhere but /dev/null or another fd."""
    for i, t in enumerate(seg):
        if re.fullmatch(r"\d*>>?", t) or t in (">", ">>", "&>", ">|"):
            target = seg[i + 1] if i + 1 < len(seg) else ""
            if target != "/dev/null":
                return True
        elif re.fullmatch(r"\d*>&\d+", t) or t == ">&":
            continue
    return False


def _segment_safe(seg):
    words = [w for w in seg if not _is_redirect(w)]
    if not words:
        return False
    if _write_redirect(seg):
        return False
    name = os.path.basename(words[0])
    args = words[1:]
    if name == "git":
        sub = next((a for a in args if not a.startswith("-")), "")
        if sub == "config":
            return not any(a for a in args if a in ("--add", "--unset", "--replace-all", "--global", "--system") or "=" in a)
        if sub == "branch":
            return not any(a in ("-d", "-D", "-m", "-M", "-c", "-C", "--delete", "--move", "--copy") for a in args)
        if sub == "tag":
            return not any(a in ("-d", "--delete", "-f", "-a", "-m", "-s") for a in args)
        if sub == "remote":
            return not any(a in ("add", "remove", "rm", "rename", "set-url", "prune") for a in args)
        return sub in SAFE_GIT
    if name == "systemctl":
        sub = next((a for a in args if not a.startswith("-")), "")
        return sub in SAFE_SYSTEMCTL
    if name == "find":
        return not any(a in ("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls") for a in args)
    if name == "sort":
        return not any(a == "-o" or a.startswith("--output") or (a.startswith("-") and not a.startswith("--") and "o" in a[1:]) for a in args)
    if name in ("env", "printenv"):
        return name == "printenv" or not args
    if name in ("top", "htop", "less", "more"):
        return False  # interactive: would hang the model's shell
    if name == "ip":
        return not any(a in ("add", "del", "delete", "set", "flush", "replace", "change") for a in args)
    return name in SAFE_COMMANDS and name not in ALWAYS_RISKY_WORDS


def classify(cmd):
    """Return (level, reason)."""
    cmd = cmd.strip()
    if not cmd:
        return NORMAL, "empty command"
    for p in BLOCK_PATTERNS:
        if p.search(cmd):
            return BLOCK, "this could destroy the system"
    try:
        tokens = _tokens(cmd)
    except ValueError:
        return RISKY, "could not parse the command"
    segs = _segments(tokens)
    names = [os.path.basename(s[0]) for s in segs if s]
    for seg in segs:
        words = [w for w in seg if not _is_redirect(w)]
        if not words:
            continue
        inner = _unwrap(words) or words
        name = os.path.basename(inner[0])
        if name in BLOCK_WORDS:
            return BLOCK, f"'{name}' is blocked"
        if name == "rm" and _dangerous_rm(inner[1:]):
            return BLOCK, "this would delete your home folder or the system"
    if re.search(r"(curl|wget)[^|;&]*\|\s*(sudo\s+)?(ba|z|da)?sh\b", cmd):
        return RISKY, "downloads and runs a script"
    if "$(" in cmd or "`" in cmd:
        return RISKY, "contains a nested command"
    if SENSITIVE.search(cmd):
        return RISKY, "touches keys, passwords or secrets"
    for seg in segs:
        w = [x for x in seg if not _is_redirect(x)]
        if w and os.path.basename(w[0]) == "find" and not _segment_safe(seg):
            return RISKY, "find would delete files or run commands"
        if w and os.path.basename(w[0]) == "sort" and not _segment_safe(seg):
            return RISKY, "sort would write to a file"
    for seg, n in zip(segs, names):
        if n in ALWAYS_RISKY_WORDS and not _segment_safe(seg):
            return RISKY, {"ssh": "runs on a remote machine", "scp": "copies to or from a remote machine", "rsync": "copies files, maybe to a remote machine",
                           "sudo": "runs with administrator rights"}.get(n, f"'{n}' changes things")
    if re.search(r"\bgit\s+(push|reset|clean|checkout|restore|rebase|commit|merge|pull|stash|rm|mv)\b", cmd):
        return RISKY, "changes the git repository"
    for seg in segs:
        if _write_redirect(seg):
            return RISKY, "writes to a file"
    if all(_segment_safe(s) for s in segs):
        return SAFE, "read-only"
    return NORMAL, "runs a program"


def _dangerous_rm(args):
    recursive = any(a in ("-r", "-R", "--recursive") or (a.startswith("-") and not a.startswith("--") and ("r" in a or "R" in a)) for a in args)
    targets = [a for a in args if not a.startswith("-")]
    home = os.path.expanduser("~")
    for t in targets:
        t2 = os.path.expandvars(os.path.expanduser(t)).rstrip("/") or "/"
        if t in ("/", "/*", "~", "~/", "~/*", "$HOME", "$HOME/", "$HOME/*", ".", "..", "*") and recursive:
            return True
        if recursive and (t2 in ("/", home, os.path.dirname(home)) or t2 in ("/usr", "/etc", "/var", "/bin", "/boot", "/lib", "/sbin", "/home", "/opt", "/root")):
            return True
    return False


# --- files ------------------------------------------------------------------------------------

PROTECTED_WRITE_PREFIXES = ("/etc", "/usr", "/boot", "/bin", "/sbin", "/lib", "/lib64", "/sys", "/proc", "/dev", "/var/lib", "/var/log", "/root", "/opt", "/srv")


def write_blocked(path):
    """Reason a file may not be written by the model, or None."""
    p = os.path.realpath(os.path.expanduser(path))
    home = os.path.expanduser("~")
    for pre in PROTECTED_WRITE_PREFIXES:
        if p == pre or p.startswith(pre + "/"):
            return f"{pre} is a system folder"
    for sub in (".ssh", ".gnupg", ".local/share/keyrings", ".config/hy-desktop", ".local/share/hy-desktop/colibri"):
        d = os.path.join(home, sub)
        if p == d or p.startswith(d + "/"):
            return f"~/{sub} is protected"
    return None
