"""Settings and paths. Everything lives under the user's home: nothing is written elsewhere."""
import json
import os

VERSION = "0.1.0"
APP_NAME = "Hey-Alfred"
# The colibri release Hey-Alfred was tested with. `hey-alfred setup` fetches exactly this commit.
COLIBRI_REPO = "https://github.com/JustVugg/colibri"
COLIBRI_COMMIT = "e0db083cdc9e563e0766cc6fc661ef23c32298e0"
MODEL_ID = "qwen3-coder-30b"  # colibri's catalog id

HOME = os.path.expanduser("~")
CONFIG_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME", os.path.join(HOME, ".config")), "hy-desktop")
DATA_DIR = os.path.join(os.environ.get("XDG_DATA_HOME", os.path.join(HOME, ".local", "share")), "hy-desktop")
CONFIG_FILE = os.path.join(CONFIG_DIR, "config.json")
HISTORY_FILE = os.path.join(DATA_DIR, "history")
BACKUP_DIR = os.path.join(DATA_DIR, "backups")
SESSION_DIR = os.path.join(DATA_DIR, "sessions")
LOG_DIR = os.path.join(DATA_DIR, "logs")

DEFAULTS = {
    "host": "127.0.0.1",
    "port": 8000,
    "colibri_dir": os.path.join(DATA_DIR, "colibri"),
    "model_dir": os.path.join(HOME, "colibri-models", MODEL_ID),
    "model_name": "qwen3-coder-30b-a3b-colibri",
    "cap": 32,  # experts cached per layer: 32 keeps ~6.5 GB resident, 128 keeps 15 GB
    "ctx": 8192,  # conversation window in tokens
    "memory_max_percent": 75,  # hard memory cap for the AI engine (a systemd limit); 0 = none
    "mode": "ask",  # ask | auto | readonly
    "max_steps": 12,  # tool calls the agent may chain for one request
    "keep_server": False,  # leave the AI engine running after Hey-Alfred exits
    "temperature": 0.2,
}


def load():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            cfg.update({k: v for k, v in json.load(f).items() if k in DEFAULTS})
    except (OSError, ValueError):
        pass
    return cfg


def save(cfg):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump({k: v for k, v in cfg.items() if v != DEFAULTS.get(k)}, f, indent=2)


def ensure_dirs():
    for d in (CONFIG_DIR, DATA_DIR, BACKUP_DIR, SESSION_DIR, LOG_DIR):
        os.makedirs(d, exist_ok=True)
