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
- **Navigate long histories** — messages longer than ~480 chars collapse to four lines with an *Expand* link, and a thin rail next to the scroll bar puts one tick per message, spaced evenly by message index (a single huge message can't squeeze the rest out of reach). Click a tick to land on that message's header; `Alt+Up` / `Alt+Down` walk message by message.
- **History mirrors Pi's branch** — cards are rendered from Pi's current native active branch with original entry ids and order, not by matching locally sent text; a card appears once Pi records the entry, and a quiet `user/assistant+tool` count sits above the input.
- **Scroll stays where you left it** — the history list no longer jumps to the bottom on agent state changes; it only follows the tail when you send, or when you were already near the bottom.
- **Stay in sync with Pi's writes** — as soon as Pi finishes a write/edit tool, the IDE refreshes that file so you don't wait on the ~10s VFS poll. Optionally also open the file in the editor.
- **Completion alerts (opt-in)**
  - system sound on normal completion or error, in the foreground and background
  - taskbar / Dock attention while the IDE is in the background; appearance and duration follow the platform
  - manual aborts stay silent; no completion balloon or automatic focus change. Both new options default to off; the old balloon setting is retired.
- **Open edited files (opt-in)** — file refresh itself is always on.
- **Diagnostics** — Settings shows the bridge extension state and server port.

## How the bridge works

```
IDEA (PiBridgeServer, loopback TCP + token)
   ▲                                   │
   │ env PI_LAUNCHER_PORT/TOKEN/TAB_KEY│  pi --session <path> | --session-id <uuid> --name <tab>
   │                                   ▼
Terminal tab  ──  ~/.pi/agent/extensions/pi-launcher-bridge.ts (lazy-installed, content-compared)
```

- The managed extension (VERSION **12**) runs in the same interactive TUI process. A v2 authenticated connection carries requests, results and full native active-branch snapshots; it never starts a second pi process or uses RPC mode.
- Rewind and Fork require a verified TUI command probe and session format **v3+**; there is no exact pi version lock. Capabilities are advertised only after the probe succeeds, and real incompatibilities surface as explicit operation errors instead of disabling the whole plugin on a minor pi update.
- Modification requests are saved and sent once. Disconnects and timeouts preserve the panel and query the original request id; reconnect never replays a modification. The toolbar offers **Sync current session** only when an unresolved operation has ended and can be reconciled.
- All bridge traffic uses the authenticated v2 connection: working/model observations come from snapshots, and file changes/completion alerts use explicit events. Older extensions must be upgraded and their terminals restarted; v1 is no longer accepted. Completion events are best effort and are not replayed after reconnect. System sound and attention respect OS/IDE settings. See the [protocol](docs/bridge-protocol.md) for developer contracts.

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
next to Rewind and Copy): it creates a new conversation `<original>-fork`
that keeps the history **up to and including** that message and its reply —
everything after it is dropped — with an empty input, so you continue from that
exact point in a fresh session. The original conversation stays untouched.
Under the hood the plugin creates a full CLI copy in a separate Terminal, waits
for its bridge capability and complete snapshot, then verifies the selected turn
by native entry identities. A single v2 request forks at the turn's leaf through
the public API. The endpoint includes all tool calls/results and the final reply.
The replacement session confirms its branch through a durable handoff; only
then does the panel trim its copy. The intermediate copy is not archived.
This panel action deliberately differs from native `/fork`'s default edit-before
behavior.

Fork rejects busy sessions, messages absent from the current native branch, labelled sessions and a target
TUI editor containing input. Your source Terminal and IDEA draft stay intact.
During preparation, the toolbar's **Stop fork preparation** icon can stop the
workflow and keep the full copy. After
submission, disconnects only query the original operation id; they never start
another Terminal or repeat the fork. A failed or unconfirmed fork keeps the
copy visible for inspection.

The **Rewind** button (undo icon, left of Fork) uses the bidirectional bridge to
navigate within the same session. It keeps the panel unchanged until pi confirms
the result and the full branch snapshot passes validation. The selected user
message and later turns leave the active branch; the tree entries remain in the
jsonl. No rewind command is pasted into the Terminal editor.

Rewind is rejected while pi is busy, for a root message without a parent, or
when the parent is another user/custom message: those targets could otherwise
move to the wrong point or change the TUI editor. If you edit the IDEA draft
while waiting, it is preserved and the selected text is available through
**Restore text**. The input bar also shows a quiet `user/assistant+tool` count
from the latest subscribed v2 branch snapshot. State updates arrive automatically.
When recovery is possible, **Sync current session** appears as a toolbar icon;
it never repeats the operation. **Restore text** is also a conditional toolbar
icon. Hover over an icon to see its action. The lower-left workspace-files action
uses a compact icon button.

History cards come directly from Pi's current native branch, with original session/entry ids and branch order. Rewind, Fork and Copy sit at the card's lower right. The panel no longer matches locally sent text to session messages. A send appears in history only after Pi records a user entry; `/model` creates no card, while skills/templates show their recorded expanded text.

The first valid snapshot replaces old local cards and ignores obsolete hidden records, so previously hidden messages on the current branch become visible again. Drafts are preserved. Before connection, saved messages are an offline cache; Rewind/Fork require a verified online snapshot. Session parsing and branch traversal stay in Pi's public SessionManager API, exposed through the existing bridge.

Validation entry points: `node --test tests/bridge-extension.test.mjs`, Gradle
`test`, and the opt-in real TUI smoke
`PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py` (rewind and fork, including
runtime replacement). CI can run the smoke
through the `tui_smoke` workflow input; the extension tests and Gradle tests run
on every build. Installation
compares full resource contents, and updated code loads in a new Pi Terminal
or after pi `/reload`.

In short: the IDE panel gives you structure (persistent list, working badges,
archived sessions, click-to-resume), the TUI gives you pi's full interactive
power — combine them freely.

## Configuration

**Settings → Tools → Pi Terminal Bridge**

| Setting | Description |
|---|---|
| Model / thinking level / extra args | Passed to the `pi` CLI at launch |
| Play a system sound | Sound on normal completion or error in foreground/background (opt-in); manual aborts stay silent. System volume and sound settings apply. |
| Request taskbar / Dock attention | Background completion/error attention (opt-in); appearance and duration follow OS/IDE settings. |
| Open files modified by Pi | Also open the file in the editor after a write (opt-in). VFS refresh is always on. |
| Bridge diagnostics | Read-only: extension state + server port |

## Keyboard shortcuts

Defaults — rebindable in **Settings → Keymap → "Pi Terminal Bridge"**:

- `Ctrl+Shift+P` — open the Pi conversation window
- `Alt+Shift+3` — send selection / file to Pi
- `Ctrl+Alt+Shift+T` — focus the Pi terminal (only while the prompt input is focused; does not steal Refactor This from the editor)
- `Ctrl+Alt+Shift+E` — focus the Pi prompt (only while a Pi terminal tab is focused)
- `Alt+Up` / `Alt+Down` — jump to the previous / next message header in the history area (only while the history area is focused)

## Build

```bash
node --test tests/bridge-extension.test.mjs   # packaged extension and fault contracts
./gradlew test         # JVM protocol/projection/commit contracts
PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py  # opt-in real pi 1.5.x PTY smoke
./gradlew build        # verify
./gradlew runIde       # sandbox IDE
./gradlew buildPlugin  # distributable zip
```

JDK 21 toolchain, IntelliJ Platform Gradle Plugin 2.x, compile against 2026.1, `sinceBuild=243`, `untilBuild=263.*` (2026.3).

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
