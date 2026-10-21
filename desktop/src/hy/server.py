"""Install, start, stop and watch the colibri AI engine.

The engine runs as a transient systemd user service with a hard memory limit, so if the model ever
needs more memory than allowed, the engine is stopped, never your desktop. Without systemd it falls
back to a plain background process."""
import json
import os
import shutil
import signal
import subprocess
import sys
import time

from . import config

UNIT = "hy-colibri"


def total_memory_bytes():
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass
    return 0


def available_memory_bytes():
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass
    return 0


def model_state(model_dir):
    """('complete'|'partial'|'missing', bytes_present, bytes_expected) from colibri's download manifest."""
    try:
        with open(os.path.join(model_dir, ".colibri-download.json"), encoding="utf-8") as f:
            manifest = json.load(f)
    except (OSError, ValueError):
        return ("partial" if os.path.isdir(model_dir) and os.listdir(model_dir) else "missing"), 0, 0
    have = want = 0
    whole = True
    for spec in manifest.get("files", []):
        size = int(spec.get("size", 0))
        want += size
        path = os.path.join(model_dir, spec["path"])
        if os.path.isfile(path) and os.path.getsize(path) == size:
            have += size
        else:
            whole = False
            if os.path.isfile(path + ".part"):
                have += min(os.path.getsize(path + ".part"), size)
    return ("complete" if whole and manifest.get("complete") else "partial"), have, want


def installed(cfg):
    coli = os.path.join(cfg["colibri_dir"], "c", "coli")
    engine = os.path.join(cfg["colibri_dir"], "c", "qwen36")
    state, have, want = model_state(cfg["model_dir"])
    model = state == "complete"
    return {"colibri": os.path.isfile(coli), "engine": os.path.isfile(engine), "model": model,
            "model_state": state, "model_have": have, "model_want": want,
            "ready": os.path.isfile(coli) and os.path.isfile(engine) and model}


def has_systemd_user():
    if not shutil.which("systemd-run") or not shutil.which("systemctl"):
        return False
    r = subprocess.run(["systemctl", "--user", "is-system-running"], capture_output=True, text=True)
    return r.stdout.strip() in ("running", "degraded", "starting")


def serve_command(cfg):
    coli = os.path.join(cfg["colibri_dir"], "c", "coli")
    return [sys.executable if sys.executable else "python3", coli, "serve", "--model", cfg["model_dir"], "--cap", str(cfg["cap"]),
            "--ctx", str(cfg["ctx"]), "--host", cfg["host"], "--port", str(cfg["port"]), "--model-id", cfg["model_name"]]


def start(cfg):
    """Start the engine in the background. Returns a short description of how."""
    st = installed(cfg)
    if not st["ready"]:
        raise RuntimeError("the AI engine is not installed yet: run `hey-alfred setup`")
    cmd = serve_command(cfg)
    log = os.path.join(config.LOG_DIR, "engine.log")
    os.makedirs(config.LOG_DIR, exist_ok=True)
    if has_systemd_user():
        subprocess.run(["systemctl", "--user", "reset-failed", UNIT], capture_output=True)
        props = ["-p", "KillMode=control-group", "-p", f"WorkingDirectory={cfg['colibri_dir']}", "-p", f"StandardOutput=append:{log}", "-p", f"StandardError=append:{log}"]
        pct = int(cfg.get("memory_max_percent") or 0)
        if pct > 0:
            limit = total_memory_bytes() * pct // 100
            if limit:
                props += ["-p", f"MemoryMax={limit}", "-p", f"MemoryHigh={limit * 9 // 10}", "-p", "MemorySwapMax=2G"]
        r = subprocess.run(["systemd-run", "--user", "--quiet", f"--unit={UNIT}", *props, *cmd], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("could not start the AI engine: " + (r.stderr.strip() or r.stdout.strip()))
        return "systemd"
    with open(log, "ab") as lf:
        p = subprocess.Popen(cmd, cwd=cfg["colibri_dir"], stdout=lf, stderr=lf, stdin=subprocess.DEVNULL, start_new_session=True)
    with open(os.path.join(config.DATA_DIR, "engine.pid"), "w") as f:
        f.write(str(p.pid))
    return "process"


def stop():
    if has_systemd_user():
        subprocess.run(["systemctl", "--user", "stop", UNIT], capture_output=True)
        subprocess.run(["systemctl", "--user", "reset-failed", UNIT], capture_output=True)
    pidfile = os.path.join(config.DATA_DIR, "engine.pid")
    try:
        pid = int(open(pidfile).read())
        os.killpg(pid, signal.SIGTERM)
        os.remove(pidfile)
    except (OSError, ValueError):
        pass


def unit_state():
    """'active', 'inactive', 'failed' or '' when there is no systemd unit."""
    if not shutil.which("systemctl"):
        return ""
    r = subprocess.run(["systemctl", "--user", "is-active", UNIT], capture_output=True, text=True)
    return r.stdout.strip()


def log_tail(lines=20):
    try:
        with open(os.path.join(config.LOG_DIR, "engine.log"), "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 8000))
            return "\n".join(f.read().decode("utf-8", "replace").splitlines()[-lines:])
    except OSError:
        return ""


def wait_ready(client, timeout=420, tick=None):
    """Wait for the engine to answer. [tick](elapsed) is called about every 0.2 s. Returns True when ready."""
    t0 = time.monotonic()
    last = 0.0
    while time.monotonic() - t0 < timeout:
        if tick:
            tick(time.monotonic() - t0)
        if time.monotonic() - last > 2:
            last = time.monotonic()
            if client.alive() is not None:
                return True
            if unit_state() == "failed":
                return False
        time.sleep(0.2)
    return False


def pinned_checkout(cfg):
    """Clone colibri at the tested commit into colibri_dir (idempotent)."""
    d = cfg["colibri_dir"]
    if not os.path.isdir(os.path.join(d, ".git")):
        os.makedirs(os.path.dirname(d), exist_ok=True)
        subprocess.run(["git", "clone", "--quiet", config.COLIBRI_REPO, d], check=True)
    head = subprocess.run(["git", "-C", d, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if head != config.COLIBRI_COMMIT:
        subprocess.run(["git", "-C", d, "fetch", "--quiet", "origin", config.COLIBRI_COMMIT], check=False)
        subprocess.run(["git", "-C", d, "checkout", "--quiet", config.COLIBRI_COMMIT], check=True)
    return d


def run_setup(cfg):
    """Install the engine and download the model through colibri's own setup (resumable)."""
    pinned_checkout(cfg)
    coli = os.path.join(cfg["colibri_dir"], "c", "coli")
    return subprocess.call([sys.executable or "python3", coli, "setup", "--model", config.MODEL_ID, "--yes", "--no-start", "--no-browser"], cwd=cfg["colibri_dir"])
