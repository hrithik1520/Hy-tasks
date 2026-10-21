# Hey-Alfred

A private AI assistant for your PC, with its own window **and** a terminal version. It runs a 30-billion-parameter coding model **on your own machine** through [colibri](https://github.com/JustVugg/colibri), and it can use your terminal: run commands, read and edit files, and fetch and analyse web pages. Nothing you type or any file it reads leaves the computer.

Same look as the Hy Assistant Android app (this is its PC version): black, one red accent, dot-matrix details and a dot "orb".

**It has its own window.** Open "Hey-Alfred" from your apps, or type `hey-alfred` (or the short `hy`) in a terminal: it opens the window and gives your terminal back. It opens in its own app window (Chrome or Chromium without tabs or address bar; your default browser if neither is installed), with the dot orb, chat, tool steps, file diffs and **Allow / Deny buttons** for anything that changes your machine. It is a small web page served only to this PC (127.0.0.1), protected by a secret token so websites you visit cannot reach it. The terminal version is still there as `hey-alfred chat`.

```
  ● · · · ●   · ● ● ● ·
  ● · · · ●   ● · · · ●
  ● · · · ●   ● · · · ●     HEY-ALFRED
  ● ● ● ● ●   ● ● ● ● ●     v0.1.0 · your private AI, running on this PC
  ● · · · ●   ● · · · ●
  ● · · · ●   ● · · · ●
  ● · · · ●   ● · · · ●

  ● AI ready   ● Qwen3-Coder 30B   ● on this PC · private
```

Version 0.1 is built and tested for one machine: a Dell Inspiron 15 3511 (Core i5-1135G7, 12 GB RAM, no discrete GPU) on Fedora 44. It should work on other Linux PCs with at least 8 GB of RAM and about 25 GB of free disk, but only this one has been tried.

## The window

| | |
|---|---|
| open | the "Hey-Alfred" icon in your apps, or `hy` in a terminal |
| mode | **Ask** (reading runs, changes need your OK), **Auto** (ordinary commands run; risky ones still ask), **Read-only** |
| folder | click the folder chip to change the working folder |
| stop | the ■ button stops the current answer (the 4–5 minute warm-up keeps going) |
| menu (⋯) | new conversation (warms up again), save conversation, **Quit Hey-Alfred** |

Quit Hey-Alfred frees the 5 GB the AI engine uses. If you simply close the window, Hey-Alfred shuts the engine down by itself after 5 minutes; opening the icon again within that time reuses it. The window needs the AI engine, which starts by itself when you open it: you can type right away, and Alfred answers as soon as it has finished warming up.

## Be realistic about speed

This is a big model on a laptop CPU. Expect it to be **slow**:

- At the start of every conversation the model has to read its instructions and tool list once. Alfred does that in the background right after it starts, and you can type your first question meanwhile.
- After that, a simple task (run a command, read the result, answer) takes about a minute. Longer answers take longer.
- `/clear` starts a new conversation, so it warms up again.

Good for: explaining code, small scripts, reading and summarising files, checking a website, working out a command. Not good for: big multi-file refactors, anything where you need quick back-and-forth, or work where a mistake is expensive. A 30B local model is also clearly weaker than the large hosted models: check what it does.

### Measured speed

On the Dell Inspiron 15 3511 (i5-1135G7, 12 GB RAM, CPU only), with the model's expert cache set to 32 per layer (about 5 GB of RAM):

| | |
|---|---|
| start the AI engine | 7 seconds |
| warm-up, first time in a conversation (about 570 tokens of instructions and tools) | about 4½ minutes |
| a new request that continues the conversation | 94–98% of what the engine already holds is reused |
| writing | about 1.5 tokens a second (roughly one word a second) |
| reading new text | about 4 tokens a second, once warm |
| "which files are in the tests folder?" (one command, one answer) | about 50 seconds after the warm-up |

These come from one machine on one day; your numbers will differ.

## Install

Needs Python 3.9+, git, and about 25 GB of free disk. No sudo.

```bash
tar xzf hey-alfred-0.1.0.tar.gz
cd hey-alfred-0.1.0
./install.sh          # puts `hey-alfred` (and the short `hy`) in ~/.local/bin and adds the app icon
hey-alfred setup              # one time: builds the AI engine and downloads the model (about 19 GB, resumable)
hy                    # open the window (or use the app icon);  `hey-alfred chat` is the terminal version
```

Installing a newer version replaces the old one: `install.sh` stops a running Hey-Alfred and its engine, removes the old program and launchers, and installs the new ones. Your settings, saved conversations and the downloaded model are kept.

`hey-alfred doctor` checks that the PC is ready. `hey-alfred setup` can be stopped and run again; it continues where it stopped.

## Use

```
hey-alfred                      open the window (it runs in the background; your terminal is free)
hey-alfred chat                 the same AI inside the terminal instead
hey-alfred stop                 close the window and stop the AI engine
hy "list big files"     ask once and exit  (also: echo question | hy)
hey-alfred chat --mode auto     (terminal) run ordinary commands without asking
hy --cwd ~/site         work in a folder
hey-alfred setup | doctor | status | start | logs
```

In the terminal version (`hey-alfred chat`):

| | |
|---|---|
| `/mode ask\|auto\|readonly` | how much Alfred may do on its own |
| `/cd DIR` | change the working folder |
| `/clear` | forget this conversation (the model warms up again) |
| `/status` `/engine stop\|start\|logs` | see or control the AI engine |
| `/save` | save the conversation (it is also saved when you leave) |
| `!COMMAND` | run a shell command yourself, e.g. `!git status` |
| `Ctrl+C` | stop the current answer |

The AI engine starts when you open it and stops when you leave, so it does not hold 5 GB of RAM while you work. `hey-alfred --keep` leaves it running.

### Tools the model can use

| tool | what it does |
|---|---|
| `run` | a shell command in your working folder (120 s limit, output trimmed) |
| `write` / `edit` | create a file, or replace exact text in one. You see a diff first, and the old file is copied to `~/.local/share/hy-desktop/backups/` |
| `fetch` | download a web page and report the facts an SEO or technical review needs: status, speed, title, meta description, canonical, headings, images without alt text, links, structured data, and the text |

## Safety: what it may do without asking

Every command is classified before it runs.

| class | examples | what happens |
|---|---|---|
| **safe** (read-only) | `ls`, `cat`, `grep`, `git status`, `df`, `systemctl status` | runs without asking |
| **normal** | `python3 script.py`, `make`, `curl` | asks (runs by itself in `auto` mode) |
| **risky** | `rm`, `sudo`, `ssh`, `scp`, `rsync`, `mv`, `chmod`, installs, `git push/commit/reset`, writing with `>`, reading keys or passwords | **always asks, even in `auto` mode**, and answering `a` (always) never applies to these |
| **blocked** | `rm -rf ~` or `/`, `mkfs`, `dd` to a disk, `shutdown`, `reboot`, fork bombs | never runs |

Writes to system folders (`/etc`, `/usr`, `/boot`…), `~/.ssh`, `~/.gnupg`, and the engine's own folder are refused. In `readonly` mode nothing that changes anything runs. Web page text is marked as data so the model is told not to follow instructions found in it, and any command it then asks for still goes through the same checks.

The classifier is cautious but it is rules, not magic: when unsure it asks. Read what you approve.

**Your website over SSH.** `ssh` is always "risky", so Alfred shows you each exact command and waits. For anything on a live site: use a separate user with read-only rights (never root), try changes on a copy first, and keep a backup. Do not paste passwords or keys into the chat; Alfred uses your normal `ssh` setup and keys.

## Where things are

The app is called Hey-Alfred, but its folders keep the earlier internal name `hy-desktop` so nothing you already installed is lost.

| | |
|---|---|
| program | `~/.local/bin/hey-alfred` (and `hy`), app icons `~/.local/share/applications/hey-alfred*.desktop` |
| settings | `~/.config/hy-desktop/config.json` (all optional; see the keys in `src/hy/config.py`) |
| AI engine (colibri, pinned version) | `~/.local/share/hy-desktop/colibri` |
| model (about 19 GB) | `~/colibri-models/qwen3-coder-30b` |
| conversations, backups, logs | `~/.local/share/hy-desktop/{sessions,backups,logs}` (the window writes `logs/ui.log`) |

The AI engine runs as a systemd user service (`hy-colibri`) with a hard memory limit of 75% of your RAM, so if the model ever needs more, the engine is stopped and your desktop is not.

## Uninstall

```bash
./uninstall.sh            # the program only
./uninstall.sh --data     # also settings, conversations, backups and the engine
./uninstall.sh --model    # also the 19 GB model
```

## Speeding it up (optional, not tested here)

colibri can use an integrated GPU through Vulkan, which its docs measure at about 3x faster at reading text on one AMD machine. On this Intel PC it needs a system package first (needs `sudo`, so Hy does not do it for you):

```bash
sudo dnf install vulkan-loader-devel glslc mesa-vulkan-drivers
rm ~/.local/share/hy-desktop/colibri/c/qwen36 && hey-alfred setup     # rebuilds the engine with the GPU
```

Whether it helps on an Intel Iris Xe is not known. Compare `hy` before and after, and go back by deleting the engine file and running `hey-alfred setup` without the packages.

## Credits and licences

Hey-Alfred calls [colibri](https://github.com/JustVugg/colibri) (Apache-2.0) as a separate program and downloads [Qwen3-Coder-30B-A3B](https://huggingface.co/Qwen/Qwen3-Coder-30B-A3B-Instruct) (Apache-2.0) in colibri's int4 form from its own Hugging Face repository. The dot-matrix font in the window is [Doto](https://github.com/oliverlalan/Doto) (SIL Open Font License 1.1, `Doto-OFL.txt`). Neither colibri nor the model is included in this download. `hey-alfred setup` fetches colibri at the exact commit this version was tested with.

## Develop

```bash
PYTHONPATH=src python3 -m unittest discover -s tests   # 53 tests, no model needed
PYTHONPATH=src python3 -m hy                            # run from source (opens the window)
packaging/build.sh                                      # dist/hey-alfred-<version>.tar.gz (+ .sha256)
```

No dependencies beyond the Python standard library.
