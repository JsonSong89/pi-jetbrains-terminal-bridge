# Pi Terminal Bridge

[English](README.md) · [简体中文](README.zh-CN.md)

[JetBrains plugin] Persistent [Pi coding agent](https://pi.dev) sessions for JetBrains IDEs — launch, track and resume conversations, with a live bridge between the IDE and the pi CLI terminal.

> **Prerequisite:** install the Pi CLI separately.
> `npm i -g @earendil-works/pi-coding-agent` or visit [pi.dev](https://pi.dev)
>
> **Windows:** use **PowerShell 7** (`pwsh.exe`) as the IDE Terminal shell (Settings → Tools → Terminal). cmd.exe cannot run the live bridge.

## Features

- **Persistent conversations** — Pi sessions survive IDE restarts; conversations are listed in the Pi panel and relaunch with `pi --session <id>` on click. Terminals are not auto-revived on startup; you decide when to resume.
- **Live IDEA ↔ pi bridge** — a loopback TCP channel with a per-instance token keeps the panel in sync with the terminal in real time:
  - working ● / idle badges per conversation
  - current model display
  - session rebind on `/new`, `/fork`, `/resume` — the old session is archived as a separate closed entry, still resumable
  - rename a conversation in the panel (duplicates forbidden)
- **One-click launch** — the toolbar button opens a dedicated Pi tab in the Terminal tool window and runs `pi` with your configured model / thinking level / extra args.
- **Send to Pi** — select code in the editor, right-click → *Send to Pi*, and a file reference (`@path/file.go#L10-25`) lands in Pi's input.
- **Notifications (opt-in)**
  - balloon when the agent finishes (including error/abort reason) — only while the IDE is in the background; click to focus that terminal
  - automatically refresh + open files Pi edited
- **Diagnostics** — Settings shows the bridge extension state and server port.

## How the bridge works

```
IDEA (PiBridgeServer, loopback TCP + token)
   ▲                                   │
   │ env PI_LAUNCHER_PORT/TOKEN/TAB_KEY│  pi --session <path> | --session-id <uuid> --name <tab>
   │                                   ▼
Terminal tab  ──  ~/.pi/agent/extensions/pi-launcher-bridge.ts (lazy-installed, hash-versioned)
```

- The extension is installed on first terminal launch and reports `session_changed`, `agent_state`, `model_changed`, `file_modified` (from `tool_result`, when the file is actually written).
- The plugin injects the session id at launch; `/new`-style runtime rebinds arrive as `session_changed` and archive the previous session.
- Protocol: `{v, seq, type, tabKey, token, data}` — one JSON object per line, de-dup per `(tabKey, type)`, unknown types dropped, version mismatches dropped loudly.
- Everything is best-effort: if the bridge dies, conversations still work via manual resume.

## Usage

### Quick start

1. Click the **pi** button in the main toolbar (or press `Ctrl+Shift+P`).
2. A new Pi tab opens in the Terminal window and runs `pi` automatically.
3. Select code in the editor, right-click → **Send to Pi** — a file reference (`@path/file.go#L10-25`) is appended to Pi's input. The panel input also has a context menu (insert current file / selection / open files) and accepts files dropped from the Project view.
4. Close and reopen the IDE — conversations are listed in the Pi panel; clicking one relaunches the terminal (the pi TUI respawns) and resumes the session with `pi --session <id>`.

> Terminals are not batch-revived at IDE startup; each conversation is resumed
> on demand — click it (or Send) and its terminal respawns the pi TUI with the
> session restored.

### Working with the TUI

The Pi tab is a **real terminal running the full pi TUI** — every slash
command works exactly as on the command line:

- `/model`, `/resume`, `/fork`, `/new`, `/compact` … all available directly in the terminal
- Type freely between sends: the plugin **appends** to Pi's input line, never overwrites — anything you typed by hand stays there
- Typical combos:
  - `Send to Pi` a couple of file references, finish the sentence yourself, hit Enter
  - `/fork` an ongoing conversation to explore a branch while the original stays in the panel
  - `/new` after wrapping up — the previous session is archived in the panel, still resumable later

In short: the IDE panel gives you structure (persistent list, working badges,
archived sessions, click-to-resume), the TUI gives you pi's full interactive
power — combine them freely.

## Configuration

**Settings → Tools → Pi Terminal Bridge**

| Setting | Description |
|---|---|
| Model / thinking level / extra args | Passed to the `pi` CLI at launch |
| Notify on agent end | Balloon when a conversation's agent settles (opt-in; only if the IDE is in the background; click to focus that terminal) |
| Open files modified by Pi | Refresh + open in editor after writes (opt-in) |
| Bridge diagnostics | Read-only: extension state + server port |

## Keyboard shortcuts

Defaults — both rebindable in **Settings → Keymap → "Pi Terminal Bridge"**:

- `Ctrl+Shift+P` — open the Pi conversation window
- `Alt+Shift+3` — send selection / file to Pi

## Build

```bash
./gradlew build        # verify
./gradlew runIde       # sandbox IDE
./gradlew buildPlugin  # distributable zip
```

JDK 21 toolchain, IntelliJ Platform Gradle Plugin 2.x, target 2026.1, `sinceBuild=243`.

## Design notes

Architecture and decision records live in [`docs/tasks/`](docs/tasks/). Notably:

- Conversation model: stable `id` (tab key / primary key) + rebindable `piSessionId` — `/new` swaps the pi session, the tab identity stays.
- Pi's jsonl files are the source of truth; the plugin only stores an index.
- Known limitation: on Windows the live bridge injects PowerShell (`$env:…`) or POSIX `env` (Git Bash). **PowerShell 7 (`pwsh.exe`) is recommended** as the IDE Terminal shell (Settings → Tools → Terminal). cmd.exe is detected and skipped, with a warning. `--session` / `--resume` in Extra arguments are dropped at launch — the panel owns the session.

## Acknowledgements

Part of the inspiration for this project came from [pi-agent-launcher](https://github.com/haokanjiang/pi-agent-launcher). Thanks!
