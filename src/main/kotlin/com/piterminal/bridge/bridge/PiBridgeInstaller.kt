package com.piterminal.bridge.bridge

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Installs/updates the pi-side bridge extension at
 * ~/.pi/agent/extensions/pi-launcher-bridge.ts.
 *
 * The file is managed by this plugin: only this single file is written,
 * other files in that directory (user extensions, herdr, ...) are untouched.
 * The extension is env-gated: without PI_LAUNCHER_PORT/TOKEN/TAB_KEY it does
 * nothing, so pi instances not launched by the plugin are unaffected.
 */
object PiBridgeInstaller {

    private val logger = Logger.getInstance(PiBridgeInstaller::class.java)

    private const val VERSION = 5

    // language=TypeScript
    private val SOURCE = """
// Managed by Pi Terminal Bridge (JetBrains plugin). Do not edit; the plugin rewrites this file.
// Env-gated: active only when pi is launched by the plugin (PI_LAUNCHER_PORT/TOKEN/TAB_KEY).
const PORT = Number(process.env.PI_LAUNCHER_PORT);
const TOKEN = process.env.PI_LAUNCHER_TOKEN;
const TAB_KEY = process.env.PI_LAUNCHER_TAB_KEY;
const VERSION = $VERSION;

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
  if (sessionId) send("session_changed", { sessionId });
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
  pi.on("tool_result", (event, _ctx) => {
    try {
      const data = event?.data ?? event ?? {};
      const tool = String(data.tool ?? event?.tool ?? "");
      if (!/edit|write|patch|apply|create|save|replace/i.test(tool)) return;
      const args = data.args ?? {};
      const details = data.details ?? data.result ?? {};
      const paths = [];
      const push = (p) => { if (typeof p === "string" && p.length > 0) paths.push(p); };
      push(args.filePath); push(args.file_path); push(args.path); push(args.file);
      push(details.path); push(details.filePath); push(details.file_path);
      if (Array.isArray(args.files)) {
        for (const f of args.files) push(typeof f === "string" ? f : f?.path);
      }
      for (const p of paths) send("file_modified", { path: String(p) });
    } catch (_e) {}
  });
  // IDE-side "Fork" button on a message card: fork this session at that
  // message (pi's /fork semantics — history before the message, the message
  // text returns to the IDE panel's draft for editing). The IDE launches pi
  // with --fork <source> (full copy), then sends this command with the
  // message text base64-encoded. We locate the matching user entry on the
  // active branch (newest first) and fork before it; the resulting
  // session_changed report rebinds the IDE conversation to the new session.
  pi.registerCommand("bridge-fork", {
    description: "Internal: fork the current session at a message (Pi Terminal Bridge)",
    handler: async (args, ctx) => {
      if (!enabled()) return;
      try {
        const text = Buffer.from(String(args || "").trim(), "base64").toString("utf8");
        if (!text) return;
        const branch = ctx.sessionManager.getBranch();
        for (let i = branch.length - 1; i >= 0; i--) {
          const e = branch[i];
          if (!e || e.type !== "message" || !e.message || e.message.role !== "user") continue;
          const c = e.message.content;
          const t = typeof c === "string" ? c : Array.isArray(c) ? c.filter(x => x && x.type === "text").map(x => x.text).join("") : "";
          if (t === text) {
            await ctx.fork(e.id);
            return;
          }
        }
        try { ctx.ui.notify("bridge-fork: matching message not found", "warn"); } catch (_e) {}
      } catch (_e) {
        // best-effort: never break pi
      }
    },
  });
  // IDE-side "Rewind" button on a message card: navigate the session tree
  // back to this message (pi's /tree semantics) — the conversation stays in
  // the same session file, and everything after this message (the question
  // and its AI reply included) leaves the active branch, i.e. drops out of
  // the model context. The tree entry itself stays on disk, like /tree.
  // Selecting a user message in /tree puts its text in the editor; here the
  // IDE panel pre-fills the text into its draft instead, then rewinds, so
  // the user can edit and re-send from the panel.
  pi.registerCommand("bridge-rewind", {
    description: "Internal: rewind the session to a message (Pi Terminal Bridge)",
    handler: async (args, ctx) => {
      if (!enabled()) return;
      try {
        const text = Buffer.from(String(args || "").trim(), "base64").toString("utf8");
        if (!text) return;
        const branch = ctx.sessionManager.getBranch();
        for (let i = branch.length - 1; i >= 0; i--) {
          const e = branch[i];
          if (!e || e.type !== "message" || !e.message || e.message.role !== "user") continue;
          const c = e.message.content;
          const t = typeof c === "string" ? c : Array.isArray(c) ? c.filter(x => x && x.type === "text").map(x => x.text).join("") : "";
          if (t === text) {
            await ctx.navigateTree(e.id);
            return;
          }
        }
        try { ctx.ui.notify("bridge-rewind: matching message not found", "warn"); } catch (_e) {}
      } catch (_e) {
        // best-effort: never break pi
      }
    },
  });
}
""".trim() + "\n"

    fun extensionPath(): Path =
        Paths.get(System.getProperty("user.home"), ".pi", "agent", "extensions", "pi-launcher-bridge.ts")

    /** Ensures the extension file exists and is current. Best-effort. */
    fun ensureInstalled() {
        try {
            val path = extensionPath()
            if (Files.exists(path) && isCurrent(path)) return
            Files.createDirectories(path.parent)
            Files.writeString(path, SOURCE)
            logger.info("Installed pi bridge extension at $path (v$VERSION)")
        } catch (e: Exception) {
            logger.warn("Failed to install pi bridge extension: ${e.message}")
        }
    }

    private fun isCurrent(path: Path): Boolean = try {
        val text = Files.readString(path)
        text.contains("const VERSION = $VERSION;")
    } catch (_: Exception) {
        false
    }

    /** Read-only diagnostics for the settings page: [state, version, path]. */
    fun diagnostics(): String {
        val path = extensionPath()
        return try {
            if (!Files.exists(path)) {
                "extension not installed yet — starts automatically with the first Pi terminal"
            } else {
                val current = isCurrent(path)
                val state = if (current) "installed (v$VERSION, up to date)" else "stale — will be rewritten on next Pi launch"
                "$state · $path"
            }
        } catch (e: Exception) {
            "diagnostics unavailable: ${e.message}"
        }
    }
}
