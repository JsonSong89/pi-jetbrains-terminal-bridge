# Pi Terminal Bridge

[English](README.md) · [简体中文](README.zh-CN.md)

This plugin does not try to be an AI IDE. It does **one thing**:

**In the JetBrains IDE you already live in, compose prompts with enough context — then send them to [Pi](https://pi.dev) and watch the result.**

The rest (session list, live badges, drag-and-drop, notifications) is icing.

Many developers still care about architecture and clean code. They want prompts that name the class, the method, the call site — so the agent edits the right place and stays in bounds. JetBrains' index, Go to Declaration, Find Usages, and Go to Implementation are exactly the tools they already use to gather that context. This plugin is the last mile: keep those clues in a prompt, send them to Pi, see the TUI respond.

It is **not** a vibe-coding cockpit. It is for people who want more control over, and participation in, how the agent changes their code.

> **Prerequisite:** install the Pi CLI separately.
> `npm i -g @earendil-works/pi-coding-agent` or visit [pi.dev](https://pi.dev)
>
> **Windows:** use **PowerShell 7** (`pwsh.exe`) as the IDE Terminal shell (Settings → Tools → Terminal). cmd.exe cannot run the live bridge.

## What you get

- **Persistent conversations** — Pi sessions survive IDE restarts; conversations are listed in the Pi panel and relaunch with `pi --session <id>` on click. Terminals are not auto-revived on startup; you decide when to resume.
- **Live IDEA ↔ pi bridge** — a loopback TCP channel with a per-instance token keeps the panel in sync with the terminal in real time:
  - working ● / idle badges per conversation
  - current model display
  - session rebind on `/new`, `/fork`, `/resume` — the old session is archived as a separate closed entry, still resumable
  - rename a conversation in the panel (duplicates forbidden)
- **One-click launch** — the toolbar button opens a dedicated Pi tab in the Terminal tool window and runs `pi` with your configured model / thinking level / extra args.
- **Send to Pi** — select code in the editor, right-click → *Send to Pi*, and a file reference (`@path/file.go#L10-25`) lands in Pi's input.
- **Fork / Rewind on every message** — each user-message card carries both actions, with deliberately different semantics: **fork** creates a new conversation that keeps history **up to and including** that message and its full reply (everything after is dropped, input left empty); **rewind** keeps the same session and moves the branch back to **before** that message (pi's `/tree` semantics — the message and later turns drop out of the model context, text back in the input for editing).
- **Stay in sync with Pi's writes** — as soon as Pi finishes a write/edit tool, the IDE refreshes that file so you don't wait on the ~10s VFS poll. Optionally also open the file in the editor.
- **Notifications (opt-in)**
  - balloon when the agent finishes (including error/abort reason) — only while the IDE is in the background; click to focus that terminal
  - open files Pi edited (still opt-in; refresh itself is always on)
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

Each user-message card in the panel also carries a **Fork** button (branch icon,
next to Rewind, Copy and Delete): it creates a new conversation `<original>-fork`
that keeps the history **up to and including** that message and its reply —
everything after it is dropped — with an empty input, so you continue from that
exact point in a fresh session. The original conversation stays untouched.
Under the hood the plugin launches pi with `--fork <session>` and the bridge
extension's `/bridge-fork` command (fork at the turn's leaf entry) trims the
copy. The endpoint is the last entry before the next user message, including
all tool calls/results and the final reply, not just the first assistant entry.
This panel action deliberately differs from native `/fork`'s default edit-before
behavior.

The **Rewind** button (undo icon, left of Fork) calls `navigateTree` on the
selected user message's **parent** in the same session — no new session, no
terminal restart. The selected message itself and everything after it leave
the active branch and model context. Only the IDEA input is pre-filled with
the selected text for editing; the TUI editor is not pre-filled. Tree entries
remain in the jsonl. Rewinding the first entry without a parent is currently
unsupported and reports a warning.

Bridge command regression tests (no IDE or Gradle build required):
`node --test tests/bridge-extension.test.mjs`. CI runs these against the actual
packaged resource before building. Extension VERSION stays **6** before tagging;
installation compares full file contents, and updated code takes effect in a
newly opened Pi terminal.

In short: the IDE panel gives you structure (persistent list, working badges,
archived sessions, click-to-resume), the TUI gives you pi's full interactive
power — combine them freely.

## Configuration

**Settings → Tools → Pi Terminal Bridge**

| Setting | Description |
|---|---|
| Model / thinking level / extra args | Passed to the `pi` CLI at launch |
| Notify on agent end | Balloon when a conversation's agent settles (opt-in; only if the IDE is in the background; click to focus that terminal) |
| Open files modified by Pi | Also open the file in the editor after a write (opt-in). VFS refresh is always on. |
| Bridge diagnostics | Read-only: extension state + server port |

## Keyboard shortcuts

Defaults — rebindable in **Settings → Keymap → "Pi Terminal Bridge"**:

- `Ctrl+Shift+P` — open the Pi conversation window
- `Alt+Shift+3` — send selection / file to Pi
- `Ctrl+Alt+Shift+T` — focus the Pi terminal (only while the prompt input is focused; does not steal Refactor This from the editor)
- `Ctrl+Alt+Shift+E` — focus the Pi prompt (only while a Pi terminal tab is focused)

## Build

```bash
node --test tests/bridge-extension.test.mjs   # bridge extension command handlers (fast, no Gradle)
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

- Thanks to the [Linux.do](https://linux.do/) community for the support and help along the way.
- Thanks to [pi-agent-launcher](https://github.com/haokanjiang/pi-agent-launcher) — this project was forked from it at commit `72ea264` (v0.1.4) and evolved through 0.2.x. By 0.3.x the context-management approach we wanted had diverged from the original project's underlying philosophy, so we left the fork network and started fresh. Much gratitude for the original work.

## License

[MIT](LICENSE)
