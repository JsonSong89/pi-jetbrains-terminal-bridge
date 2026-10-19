// Managed by Pi Terminal Bridge (JetBrains plugin). Do not edit; the plugin rewrites this file.
// Env-gated: active only when pi is launched by the plugin (PI_LAUNCHER_PORT/TOKEN/TAB_KEY).
const PORT = Number(process.env.PI_LAUNCHER_PORT);
const TOKEN = process.env.PI_LAUNCHER_TOKEN;
const TAB_KEY = process.env.PI_LAUNCHER_TAB_KEY;
const VERSION = @EXTENSION_VERSION@;

let seq = Date.now() * 1000;
let sessionId;

function enabled() {
  return !!PORT && !!TOKEN && !!TAB_KEY;
}

function send(type, data) {
  if (!enabled()) return;
  const payload = JSON.stringify({ v: 1, seq: seq++, type, tabKey: TAB_KEY, token: TOKEN, data }) + "\n";
  const attempt = (timeoutMs) => {
    try {
      const net = require("node:net");
      const socket = net.connect(PORT, "127.0.0.1");
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        try { socket.destroy(); } catch (_e) {}
      };
      socket.setTimeout(timeoutMs, finish);
      socket.on("error", finish);
      socket.on("close", finish);
      socket.on("connect", () => {
        socket.write(payload);
        socket.end();
      });
    } catch (_e) {
      // bridge is best-effort; never break pi
    }
  };
  attempt(500);
  // One retry shortly after covers transient connect failures (e.g. first
  // message right after the server started). Late retry is harmless: the
  // server de-dups by (tabKey, type) seq.
  const retryTimer = setTimeout(() => attempt(1500), 1500);
  if (retryTimer.unref) retryTimer.unref();
}

function refreshSessionId(ctx) {
  try {
    const id = ctx?.sessionManager?.getSessionId?.();
    if (typeof id === "string" && id.length > 0) sessionId = id;
  } catch (_e) {}
}

function reportSessionChanged(ctx) {
  refreshSessionId(ctx);
  if (sessionId) send("session_changed", { sessionId, extensionVersion: VERSION });
}

export default function (pi) {
  if (!enabled()) return;

  pi.on("session_start", (_event, ctx) => {
    reportSessionChanged(ctx);
  });

  pi.on("session_shutdown", (_event, ctx) => {
    refreshSessionId(ctx);
    if (sessionId) send("session_stopped", {});
  });

  pi.on("agent_start", (_event, _ctx) => {
    send("agent_state", { state: "working" });
  });

  pi.on("agent_settled", (event, _ctx) => {
    const data = { state: "idle" };
    try {
      const reason = event?.data?.stopReason ?? event?.stopReason;
      if (reason) data.stopReason = String(reason);
    } catch (_e) {}
    send("agent_state", data);
  });

  pi.on("model_select", (event, _ctx) => {
    try {
      const modelId = event?.data?.modelId ?? event?.modelId;
      if (modelId) send("model_changed", { modelId: String(modelId) });
    } catch (_e) {}
  });

  // Report from tool_result: the file is actually written by then; a
  // tool_call report would race the write and refresh nothing.
  // Event shape: { type: "tool_result", toolName, input, details, ... } —
  // the path lives in input.path for edit/write. Report right after the tool
  // finishes, while the agent may still be running, so the IDE refreshes
  // immediately instead of waiting for the run to end.
  pi.on("tool_result", (event, _ctx) => {
    try {
      const data = event?.data ?? event ?? {};
      const tool = String(data.toolName ?? data.tool ?? "");
      if (tool !== "edit" && tool !== "write" && tool !== "patch" && tool !== "apply") return;
      const input = data.input ?? data.args ?? {};
      const paths = [];
      const push = (p) => { if (typeof p === "string" && p.length > 0) paths.push(p); };
      push(input.path); push(input.filePath); push(input.file_path); push(input.file);
      const details = data.details ?? data.result ?? {};
      push(details.path); push(details.filePath); push(details.file_path);
      if (Array.isArray(input.edits)) {
        for (const e of input.edits) push(typeof e === "string" ? e : e?.path);
      }
      for (const p of paths) send("file_modified", { path: String(p) });
    } catch (_e) {}
  });
  // IDE-side "Fork" button on a message card: fork this session at that
  // message (pi's /fork semantics — history before the message, the message
  // text returns to the IDE panel's draft for editing). The IDE launches pi
  // with --fork <source> (full copy), then sends this command once the first
  // bind is reported; the command carries the panel index + message text and
  // the extension forks before that entry. The resulting session_changed
  // report rebinds the IDE conversation to the new session.
  // Shared lookup for bridge-fork / bridge-rewind: user messages on the
  // active branch, oldest first. The IDE panel's message list maps 1:1 onto
  // this order when messages were sent through the panel, so the command
  // carries the panel index plus the message text (both base64-safe): match
  // index AND text first; fall back to newest-first text matching when the
  // panel index drifted (e.g. messages typed directly in the TUI).
  function findUserMessage(ctx, index, text) {
    const users = [];
    const branch = ctx.sessionManager.getBranch();
    for (let i = 0; i < branch.length; i++) {
      const e = branch[i];
      if (!e || e.type !== "message" || !e.message || e.message.role !== "user") continue;
      const c = e.message.content;
      const t = typeof c === "string" ? c : Array.isArray(c) ? c.filter(x => x && x.type === "text").map(x => x.text).join("") : "";
      users.push({ entry: e, text: t });
    }
    const byIndex = Number.isInteger(index) && index >= 0 && index < users.length ? users[index] : undefined;
    if (byIndex && byIndex.text === text) return byIndex;
    for (let i = users.length - 1; i >= 0; i--) {
      if (users[i].text === text) return users[i];
    }
    return undefined;
  }

  // Fork semantics (matches the IDE panel's Fork button): keep history up to
  // and INCLUDING the selected user message and its AI reply; drop everything
  // after it; nothing lands in the editor. Implemented as fork(position:"at")
  // with the leaf set to the AI reply of that turn (or the message itself
  // when no reply followed).
  function leafEntryForTurn(ctx, userEntry) {
    const branch = ctx.sessionManager.getBranch();
    const at = branch.indexOf(userEntry);
    if (at < 0) return userEntry;
    for (let i = at + 1; i < branch.length; i++) {
      const e = branch[i];
      if (!e || e.type !== "message" || !e.message) continue;
      if (e.message.role === "user") break; // next turn started — no reply for this one
      if (e.message.role === "assistant") return e;
    }
    return userEntry;
  }

  pi.registerCommand("bridge-fork", {
    description: "Internal: fork the current session at a message (Pi Terminal Bridge)",
    handler: async (args, ctx) => {
      if (!enabled()) return;
      try {
        const parsed = parseMessageArgs(args);
        if (!parsed) {
          try { ctx.ui.notify("bridge-fork: bad arguments", "warn"); } catch (_e) {}
          return;
        }
        const found = findUserMessage(ctx, parsed.index, parsed.text);
        if (!found) {
          try { ctx.ui.notify("bridge-fork: matching message not found", "warn"); } catch (_e) {}
          return;
        }
        const leaf = leafEntryForTurn(ctx, found.entry);
        try { ctx.ui.notify("bridge-fork: forking at panel message #" + parsed.index, "info"); } catch (_e) {}
        await ctx.fork(leaf.id, { position: "at" });
        try { ctx.ui.notify("bridge-fork: forked — kept history up to this message, nothing after it", "info"); } catch (_e) {}
      } catch (err) {
        try { ctx.ui.notify("bridge-fork failed: " + (err && err.message ? err.message : String(err)), "error"); } catch (_e) {}
      }
    },
  });
  // IDE-side "Rewind" button on a message card: navigate the session tree
  // back to this message (pi's /tree semantics) — the conversation stays in
  // the same session file, and everything after this message (the question
  // and its AI reply included) leaves the active branch, i.e. drops out of
  // the model context. The tree entry itself stays on disk, like /tree.
  // Navigating to the message itself would put its text in the TUI editor
  // (that's /tree's "select a user message" behavior); instead we navigate to
  // the parent entry — "continue after it" with an empty editor — and the
  // IDE panel pre-fills the text into its draft for editing.
  pi.registerCommand("bridge-rewind", {
    description: "Internal: rewind the session to a message (Pi Terminal Bridge)",
    handler: async (args, ctx) => {
      if (!enabled()) return;
      try {
        const parsed = parseMessageArgs(args);
        if (!parsed) {
          try { ctx.ui.notify("bridge-rewind: bad arguments", "warn"); } catch (_e) {}
          return;
        }
        const found = findUserMessage(ctx, parsed.index, parsed.text);
        if (!found) {
          try { ctx.ui.notify("bridge-rewind: matching message not found", "warn"); } catch (_e) {}
          return;
        }
        const entry = found.entry;
        // parentId is the "continue after it" point; skip the first-ever
        // message (no parent to land on, branch would already be empty).
        if (!entry.parentId) {
          try { ctx.ui.notify("bridge-rewind: message is the first entry; nothing before it to rewind to", "warn"); } catch (_e) {}
          return;
        }
        await ctx.navigateTree(entry.parentId);
      } catch (err) {
        try { ctx.ui.notify("bridge-rewind failed: " + (err && err.message ? err.message : String(err)), "error"); } catch (_e) {}
      }
    },
  });
}
