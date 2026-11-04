// Single-file managed artifact. All session mutations use the public TUI command context.
import { VERSION as PI_VERSION } from "@earendil-works/pi-coding-agent";
const fs = require("node:fs/promises");
const path = require("node:path");
const crypto = require("node:crypto");
const net = require("node:net");
const PORT = Number(process.env.PI_LAUNCHER_PORT);
const TOKEN = process.env.PI_LAUNCHER_TOKEN;
const TAB_KEY = process.env.PI_LAUNCHER_TAB_KEY;
const JOURNAL_DIR = process.env.PI_LAUNCHER_JOURNAL_DIR;
const VERSION = @EXTENSION_VERSION@;
const budget = (name, fallback) => {
  const n = Number(process.env[name]);
  return Number.isSafeInteger(n) && n >= 1024 ? n : fallback;
};
const MAX_FRAME = budget("PI_BRIDGE_MAX_FRAME_BYTES", 32 * 1024 * 1024);
const MAX_QUEUE = budget("PI_BRIDGE_MAX_QUEUE_BYTES", 64 * 1024 * 1024);
const MAX_JOURNAL = budget("PI_BRIDGE_MAX_JOURNAL_BYTES", 512 * 1024 * 1024);
const canonical = value => JSON.stringify(value, function (_key, v) {
  if (v && typeof v === "object" && !Array.isArray(v)) {
    return Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]]));
  }
  return v;
});
const hash = value => crypto.createHash("sha256").update(canonical(value)).digest("hex");
const clone = value => JSON.parse(JSON.stringify(value));
const terminal = r => ["succeeded", "failed", "cancelled"].includes(r?.status);
const frame = (type, fields = {}) => ({ v: 2, type, ...fields });
const error = (code, message, effect = "none") => Object.assign(new Error(message), { code, effect });
const validId = id => typeof id === "string" && /^[A-Za-z0-9-]{1,128}$/.test(id);
const enabled = () => !!PORT && !!TOKEN && !!TAB_KEY;
const textOf = content => typeof content === "string" ? content : Array.isArray(content)
  ? content.filter(p => p?.type === "text").map(p => p.text).join("") : "";

/** Async, bounded, LF-only transport. It never retains requests for replay. */
class Transport {
  constructor(hello, receive, state) {
    this.hello = hello; this.receive = receive; this.state = state;
    this.stopped = false; this.backoff = 500; this.queue = []; this.queuedBytes = 0;
  }
  start() {
    if (this.stopped) return;
    const socket = this.socket = net.connect(PORT, "127.0.0.1");
    let buffer = Buffer.alloc(0), welcomed = false, blocked = false;
    let lastRead = Date.now(), inflight = 0;
    const handshake = setTimeout(() => { if (!welcomed) socket.destroy(); }, 5000);
    handshake.unref?.();
    const heartbeat = setInterval(() => {
      if (Date.now() - lastRead > 15000) socket.destroy();
      else if (welcomed) this.send(frame("ping"));
    }, 5000);
    heartbeat.unref?.();
    this.flush = () => {
      if (blocked || socket.destroyed) return;
      while (this.queue.length) {
        const bytes = this.queue.shift(); this.queuedBytes -= bytes.length;
        if (!socket.write(bytes)) { blocked = true; return; }
      }
    };
    socket.on("drain", () => { blocked = false; this.flush(); });
    socket.on("connect", () => this.send(this.hello()));
    socket.on("data", chunk => {
      lastRead = Date.now();
      // Byte framing retains partial UTF-8 sequences and accepts U+2028/U+2029 as data.
      let start = 0;
      try {
        for (let at = 0; at < chunk.length; at++) {
          if (chunk[at] !== 10) continue;
          if (buffer.length + at - start > MAX_FRAME) throw error("CONTEXT_TOO_LARGE", "Bridge frame exceeds budget");
          const bytes = Buffer.concat([buffer, chunk.subarray(start, at)]);
          buffer = Buffer.alloc(0); start = at + 1;
          const message = JSON.parse(bytes.toString("utf8").replace(/\r$/, ""));
          if (!message || message.v !== 2) throw error("PROTOCOL_ERROR", "Invalid bridge frame");
          if (!welcomed) {
            if (message.type !== "welcome" || message.protocolVersion !== 2) throw error("PROTOCOL_ERROR", "Expected welcome");
            welcomed = true; clearTimeout(handshake); this.backoff = 500; this.state(true);
          } else if (message.type === "ping") this.send(frame("pong"));
          else if (message.type !== "pong") {
            if (++inflight > 128) throw error("CONTEXT_TOO_LARGE", "Bridge receive queue exceeds budget");
            Promise.resolve(this.receive(message)).catch(() => socket.destroy()).finally(() => { inflight--; });
          }
        }
        if (buffer.length + chunk.length - start > MAX_FRAME) throw error("CONTEXT_TOO_LARGE", "Bridge frame exceeds budget");
        if (start < chunk.length) buffer = Buffer.concat([buffer, chunk.subarray(start)]);
      } catch (_e) { socket.destroy(); }
    });
    socket.on("error", () => socket.destroy());
    socket.on("close", () => {
      clearTimeout(handshake); clearInterval(heartbeat);
      this.queue = []; this.queuedBytes = 0; this.state(false);
      if (this.stopped) return;
      const delay = this.backoff * (0.8 + Math.random() * 0.4);
      this.backoff = Math.min(10000, this.backoff * 2);
      this.reconnect = setTimeout(() => this.start(), delay); this.reconnect.unref?.();
    });
  }
  send(message) {
    const bytes = Buffer.from(JSON.stringify(message) + "\n");
    if (bytes.length > MAX_FRAME) throw error("CONTEXT_TOO_LARGE", "Bridge frame exceeds budget");
    if (!this.socket || this.socket.destroyed) return false;
    if (this.queuedBytes + bytes.length > MAX_QUEUE || this.queue.length >= 128) {
      this.socket.destroy(); return false;
    }
    this.queue.push(bytes); this.queuedBytes += bytes.length; this.flush(); return true;
  }
  close() { this.stopped = true; clearTimeout(this.reconnect); this.socket?.destroy(); }
}

/** Intent + terminal tombstones live outside pi's append-only session tree. */
class OperationJournal {
  constructor(directory, runtimeId) { this.directory = directory; this.runtimeId = runtimeId; this.bytes = 0; this.writes = Promise.resolve(); }
  file(id, suffix = ".json") { if (!validId(id)) throw error("INVALID_ARGUMENT", "Invalid operation id"); return path.join(this.directory, id + suffix); }
  async open() {
    if (!this.directory) throw error("UNSUPPORTED", "Missing plugin operation directory");
    await fs.mkdir(this.directory, { recursive: true, mode: 0o700 });
    await fs.chmod(this.directory, 0o700);
    this.leaseFile = path.join(this.directory, "writer.lease");
    try { await this.writeExclusive(this.leaseFile, { pid: process.pid, runtimeId: this.runtimeId }); }
    catch (e) {
      if (e.code !== "EEXIST") throw e;
      // Only one process may inspect/delete a stale lease at a time. Re-read
      // under that lock so concurrent recovery cannot unlink a newly acquired lease.
      const reclaim = path.join(this.directory, "writer.reclaim");
      await fs.mkdir(reclaim, { mode: 0o700 });
      try {
        const owner = JSON.parse(await fs.readFile(this.leaseFile, "utf8"));
        try { process.kill(owner.pid, 0); throw error("BUSY", "Another bridge runtime owns this tab"); }
        catch (probe) { if (probe.code !== "ESRCH") throw probe; }
        await fs.unlink(this.leaseFile);
        await this.writeExclusive(this.leaseFile, { pid: process.pid, runtimeId: this.runtimeId });
      } finally { await fs.rmdir(reclaim); }
    }
    this.ownsLease = true;
    const records = [];
    for (const name of await fs.readdir(this.directory)) {
      if (!name.endsWith(".json")) continue;
      const record = JSON.parse(await fs.readFile(path.join(this.directory, name), "utf8"));
      if (!validId(record.id) || record.tabKey !== TAB_KEY) throw error("INTERNAL_ERROR", "Damaged operation journal");
      records.push(record);
    }
    this.bytes = (await Promise.all((await fs.readdir(this.directory)).map(async name => (await fs.stat(path.join(this.directory, name))).size))).reduce((a, b) => a + b, 0);
    return records;
  }
  async syncDirectory() {
    if (process.platform === "win32") return;
    const dir = await fs.open(this.directory, "r");
    try { await dir.sync(); } finally { await dir.close(); }
  }
  async writeExclusive(file, value) {
    const body = JSON.stringify(value), size = Buffer.byteLength(body);
    if (this.bytes + size > MAX_JOURNAL) throw error("CONTEXT_TOO_LARGE", "Operation journal budget exhausted");
    const handle = await fs.open(file, "wx", 0o600);
    try { await handle.writeFile(body); await handle.sync(); }
    finally { await handle.close(); }
    await this.syncDirectory(); this.bytes += size;
  }
  save(record) {
    const copy = clone(record);
    const write = this.writes.then(() => this.writeRecord(copy));
    this.writes = write.catch(() => {});
    return write;
  }
  async writeRecord(record) {
    if (!this.ownsLease) throw error("INTERNAL_ERROR", "Operation writer lease unavailable");
    const body = JSON.stringify(record);
    const file = this.file(record.id);
    const previous = await fs.stat(file).then(s => s.size, e => { if (e.code === "ENOENT") return 0; throw e; });
    if (this.bytes - previous + Buffer.byteLength(body) > MAX_JOURNAL) throw error("CONTEXT_TOO_LARGE", "Operation journal budget exhausted");
    const temp = file + "." + crypto.randomUUID() + ".tmp";
    const handle = await fs.open(temp, "wx", 0o600);
    try {
      await handle.writeFile(body); await handle.sync(); await handle.close();
      await fs.rename(temp, file); await this.syncDirectory();
      this.bytes += Buffer.byteLength(body) - previous;
    } finally { await handle.close().catch(() => {}); await fs.unlink(temp).catch(() => {}); }
  }
  async read(id) {
    try { return JSON.parse(await fs.readFile(this.file(id), "utf8")); }
    catch (e) { if (e.code === "ENOENT") return undefined; throw e; }
  }
  async markEnded(record) {
    if (record.handoff) return;
    const proof = { id: record.id, digest: record.digest, runtimeId: record.runtimeId };
    try { await this.writeExclusive(this.file(record.id, ".ended"), proof); }
    catch (e) {
      if (e.code !== "EEXIST") throw e;
      const previous = JSON.parse(await fs.readFile(this.file(record.id, ".ended"), "utf8"));
      if (canonical(previous) !== canonical(proof)) throw error("ID_CONFLICT", "Execution-end evidence mismatch");
    }
  }
  async executionEnded(record) {
    if (terminal(record.response)) return true;
    if (record.handoff) return false;
    try {
      const proof = JSON.parse(await fs.readFile(this.file(record.id, ".ended"), "utf8"));
      if (proof.id === record.id && proof.digest === record.digest && proof.runtimeId === record.runtimeId) return true;
    } catch (e) { if (e.code !== "ENOENT") throw e; }
    // Different runtimeId/hello or lease release alone is insufficient: an old
    // asynchronous command may still be unwinding during reload in the same process.
    if (Number.isSafeInteger(record.pid) && record.pid > 0 && record.pid !== process.pid) {
      try { process.kill(record.pid, 0); }
      catch (e) { if (e.code === "ESRCH") return true; }
    }
    return false;
  }
  // Immutable handoff ticket; replacement callback writes a separate one-shot terminal.
  // The old runtime never updates its intent after handing ownership to this ticket.
  async handoff(record, expected) {
    const ticket = { id: record.id, digest: record.digest, tabKey: record.tabKey,
      originRuntimeId: record.runtimeId, nonce: crypto.randomUUID(), expected: clone(expected) };
    await this.writeExclusive(this.file(record.id, ".ticket"), ticket);
    return { directory: this.directory, ticket };
  }
  static async finishHandoff(config, freshContext, verify) {
    const journal = new OperationJournal(config.directory, "replacement-callback");
    const ticket = JSON.parse(await fs.readFile(journal.file(config.ticket.id, ".ticket"), "utf8"));
    if (canonical(ticket) !== canonical(config.ticket)) throw error("ID_CONFLICT", "Handoff ticket mismatch");
    // verify is adapter-owned, uses only freshContext and serialized ticket evidence.
    const result = await verify(freshContext, ticket.expected);
    if (!terminal(result) || result.effect === "unknown") throw error("INTERNAL_ERROR", "Unverified replacement result", "unknown");
    journal.bytes = (await Promise.all((await fs.readdir(journal.directory)).map(async name => (await fs.stat(path.join(journal.directory, name))).size))).reduce((a, b) => a + b, 0);
    await journal.writeExclusive(journal.file(ticket.id, ".terminal"), { digest: ticket.digest, nonce: ticket.nonce, response: result });
  }
  async handoffResult(record) {
    try {
      const ticket = JSON.parse(await fs.readFile(this.file(record.id, ".ticket"), "utf8"));
      const result = JSON.parse(await fs.readFile(this.file(record.id, ".terminal"), "utf8"));
      if (ticket.digest !== record.digest || result.digest !== ticket.digest || result.nonce !== ticket.nonce) throw error("ID_CONFLICT", "Invalid handoff evidence");
      return result.response;
    } catch (e) { if (e.code === "ENOENT") return undefined; throw e; }
  }
  async close() {
    await this.writes;
    if (!this.ownsLease) return;
    this.ownsLease = false;
    const owner = JSON.parse(await fs.readFile(this.leaseFile, "utf8"));
    if (owner.runtimeId === this.runtimeId) await fs.unlink(this.leaseFile);
  }
}

class PiAdapter {
  constructor(pi, runtimeId) {
    this.pi = pi; this.runtimeId = runtimeId; this.closed = false; this.generation = 0; this.observations = new Map();
    this.pending = new Map(); this.command = "pi-bridge-internal-" + runtimeId;
    this.probePassed = false;
    pi.on("input", (event, ctx) => {
      const text = String(event.text).trimStart();
      if (text.startsWith("/pi-bridge-internal-")) return { action: "handled" };
      if (/^\/bridge-rewind(?:\s|$)/.test(text)) {
        ctx?.ui?.notify?.("Legacy rewind is disabled; update Pi Terminal Bridge and restart the terminal", "warning");
        return { action: "handled" };
      }
    });
    pi.registerCommand(this.command, { description: "Internal Pi Terminal Bridge dispatch", handler: async (id, ctx) => {
      const job = this.pending.get(id);
      if (!job) return; // Never execute arbitrary command arguments.
      this.pending.delete(id);
      job.started(); // Dispatch deadline stops here; it must never time out an executing navigation.
      try { job.resolve(await job.run(ctx)); } catch (e) { job.reject(e); }
    } });
  }
  dispatch(run) {
    if (this.closed) return Promise.reject(error("DISCONNECTED", "Bridge runtime stopped"));
    const id = crypto.randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id); reject(error("UNSUPPORTED", "Public command dispatch probe timed out"));
      }, 3000); timer.unref?.();
      this.pending.set(id, { run, started: () => clearTimeout(timer), resolve: value => { clearTimeout(timer); resolve(value); }, reject: e => { clearTimeout(timer); reject(e); } });
      try { this.pi.sendUserMessage("/" + this.command + " " + id, { expandPromptTemplates: true }); }
      catch (e) { this.pending.delete(id); clearTimeout(timer); reject(e); }
    });
  }
  close() {
    this.closed = true;
    for (const job of this.pending.values()) job.reject(error("DISCONNECTED", "Bridge runtime stopped before command entry"));
    this.pending.clear();
  }
  read(ctx) {
    const sm = ctx.sessionManager;
    if (typeof sm?.getBranch !== "function" || typeof sm?.getLeafId !== "function" || typeof ctx.isIdle !== "function") throw error("UNSUPPORTED", "Missing public snapshot APIs");
    return clone({ session: { id: sm.getSessionId(), header: sm.getHeader?.(), file: sm.getSessionFile?.() },
      branch: { leafId: sm.getLeafId(), entries: sm.getBranch() },
      state: { idle: ctx.isIdle(), pendingMessages: ctx.hasPendingMessages?.() ?? false, model: modelLabel(ctx.model) } });
  }
  capture(ctx) {
    const generation = this.generation;
    const before = this.read(ctx), after = this.read(ctx);
    const stable = generation === this.generation && canonical(before) === canonical(after);
    const snapshot = { snapshotId: hash({ runtimeId: this.runtimeId, generation, ...before }), capturedAt: new Date().toISOString(),
      complete: true, consistency: stable ? "stable" : "changed_during_capture", ...before,
      source: { piVersion: PI_VERSION, runtimeId: this.runtimeId } };
    if (Buffer.byteLength(JSON.stringify(snapshot)) + 8192 > MAX_FRAME) throw error("CONTEXT_TOO_LARGE", "Full session snapshot exceeds frame budget");
    if (stable) {
      this.observations.set(snapshot.snapshotId, hash(before));
      while (this.observations.size > 16) this.observations.delete(this.observations.keys().next().value);
    }
    return snapshot;
  }
  async probe() {
    this.probePassed = await this.dispatch(ctx => {
      const before = this.read(ctx);
      return ctx.mode === "tui" && typeof ctx.navigateTree === "function" &&
        typeof ctx.ui?.getEditorText === "function" && canonical(before) === canonical(this.read(ctx));
    }).catch(() => false);
  }
  preflight(request, ctx) {
    const current = this.capture(ctx);
    if (!this.probePassed || PI_VERSION !== "1.5.0" || current.session.header?.version !== 3) throw error("UNSUPPORTED", "Rewind requires verified pi 1.5.0 TUI command dispatch");
    if (!current.state.idle || current.state.pendingMessages) throw error("BUSY", "Pi is busy; no operation was queued");
    if (request.startBefore !== undefined && (!Number.isFinite(request.startBefore) || Date.now() > request.startBefore)) throw error("STALE_CONTEXT", "Operation start deadline expired");
    if (current.consistency !== "stable" || request.context?.sessionId !== current.session.id ||
        request.context?.observedSnapshotId !== current.snapshotId || !this.observations.has(current.snapshotId)) {
      throw error("STALE_CONTEXT", "Session changed since the observed snapshot");
    }
    return current;
  }
  async rewind(request, ctx, before, markEffect) {
    const entries = before.branch.entries;
    const index = entries.findIndex(e => e.id === request.params.targetEntryId);
    const target = entries[index];
    if (!target || target.type !== "message" || target.message?.role !== "user") throw error("TARGET_NOT_FOUND", "Selected user entry is not on the active branch");
    const parent = entries[index - 1];
    if (!parent || parent.id !== target.parentId || parent.type === "custom_message" ||
        (parent.type === "message" && parent.message?.role === "user")) {
      throw error("UNSUPPORTED", "This target cannot be rewound without changing TUI editor semantics");
    }
    const editor = ctx.ui.getEditorText();
    markEffect(); // From this point an exception cannot claim no side effects.
    const result = await ctx.navigateTree(parent.id, { summarize: false });
    const after = this.capture(ctx);
    if (result?.cancelled === true) {
      const unchanged = hash({ session: before.session, branch: before.branch }) === hash({ session: after.session, branch: after.branch });
      return { status: "cancelled", effect: unchanged ? "none" : "unknown", after,
        error: { code: "CANCELLED", message: "Pi cancelled navigation" } };
    }
    if (result?.cancelled !== false || after.consistency !== "stable" || after.session.id !== before.session.id ||
        canonical(after.branch.entries) !== canonical(entries.slice(0, index)) || after.branch.leafId !== parent.id ||
        ctx.ui.getEditorText() !== editor) throw error("POSTCONDITION_FAILED", "Navigation result or TUI editor changed unexpectedly", "unknown");
    return { status: "succeeded", effect: "applied", data: { targetEntryId: target.id }, after };
  }
}

/** Registry owns validation/execution contracts; wire framing has no method-specific branches. */
class OperationRuntime {
  constructor(adapter, journal, send) {
    this.adapter = adapter; this.journal = journal; this.send = send;
    this.records = new Map(); this.methods = new Map(); this.lock = undefined; this.chain = Promise.resolve();
    this.register("session.snapshot", { version: 1, mutates: false,
      validate: params => params && typeof params === "object" && !Array.isArray(params) && Object.keys(params).length === 0,
      execute: async (_request, ctx) => ({ status: "succeeded", effect: "none", after: adapter.capture(ctx) }) });
    this.register("operation.reconcile", { version: 1, mutates: false, durable: true,
      validate: params => params && Object.keys(params).length === 1 && Array.isArray(params.operationIds) &&
        params.operationIds.length > 0 && params.operationIds.length <= 128 && params.operationIds.every(validId) && new Set(params.operationIds).size === params.operationIds.length,
      preflight: (request, ctx) => {
        const snapshot = adapter.capture(ctx);
        if (snapshot.consistency !== "stable" || snapshot.session.id !== request.context?.sessionId || snapshot.snapshotId !== request.context?.observedSnapshotId) throw error("STALE_CONTEXT", "Current state changed before reconciliation");
        return snapshot;
      },
      execute: async (request, _ctx, snapshot) => {
        const originals = request.params.operationIds.map(id => this.records.get(id));
        if (originals.some(original => !original || original.tabKey !== TAB_KEY || original.id === request.id)) throw error("TARGET_NOT_FOUND", "Original operation record is unavailable");
        for (const original of originals) if (!await this.journal.executionEnded(original)) throw error("BUSY", "Original execution has not been confirmed ended");
        for (const original of originals) {
          await this.journal.save({ ...original, reconciled: true }); original.reconciled = true;
          if (this.lock === original.id) this.lock = undefined;
        }
        return { status: "succeeded", effect: "none", data: { operationIds: request.params.operationIds }, after: snapshot };
      } });
    this.register("conversation.rewind", { version: 1, mutates: true,
      validate: params => params && Object.keys(params).length === 1 && typeof params.targetEntryId === "string" && params.targetEntryId.length > 0,
      preflight: (request, ctx) => adapter.preflight(request, ctx),
      execute: (request, ctx, before, markEffect) => adapter.rewind(request, ctx, before, markEffect) });
  }
  register(method, descriptor) {
    if (!Number.isSafeInteger(descriptor.version) || typeof descriptor.validate !== "function" || typeof descriptor.execute !== "function") throw error("INVALID_ARGUMENT", "Invalid method descriptor");
    this.methods.set(method, { validateResult: result => result?.handoff === true ||
      (terminal(result) && ["none", "applied", "unknown"].includes(result.effect) && (result.status !== "succeeded" || result.effect !== "unknown")),
      verify: (_ctx, _before, result) => result, ...descriptor });
  }
  async open() {
    for (const record of await this.journal.open()) {
      this.records.set(record.id, record);
      if (!terminal(record.response) && !record.reconciled) this.lock = record.id; // Never resume an intent after reload/crash.
    }
  }
  enqueue(message) {
    // Serialize registration/dedup/log I/O, but do not block query/ping behind a navigation dialog.
    const task = this.chain.then(() => this.receive(message));
    this.chain = task.catch(() => {});
    return task;
  }
  response(request, fields) { return frame("response", { id: request.id, method: request.method, methodVersion: request.methodVersion, ...fields }); }
  safeSend(response) {
    try { this.send(response); return true; }
    catch (e) {
      if (e.code === "CONTEXT_TOO_LARGE") {
        // Preserve the terminal; omitted evidence is explicit and cannot commit a view.
        try { this.send({ ...response, context: undefined, contextError: { code: e.code, message: e.message } }); }
        catch { /* A lost observer cannot alter durable execution. */ }
      }
      return false; // Disconnects never abort registration/execution or invent a failure.
    }
  }
  async snapshotContext(record) {
    const current = await this.adapter.dispatch(ctx => this.adapter.capture(ctx)).catch(() => undefined);
    return { ...record.response?.context, currentSnapshot: current };
  }
  async query(id) {
    const record = this.records.get(id) ?? await this.journal.read(id);
    if (!record || record.tabKey !== TAB_KEY) { this.safeSend(frame("response", { id, lookup: "not_found" })); return; }
    const handed = await this.journal.handoffResult(record);
    if (handed && !terminal(record.response)) {
      const response = this.response(record.request, handed);
      await this.journal.save({ ...record, response });
      record.response = response;
      if (this.lock === id) this.lock = undefined;
    }
    this.safeSend({ ...record.response, executionEnded: await this.journal.executionEnded(record), context: await this.snapshotContext(record) });
  }
  async receive(message) {
    if (!["request", "query", "ack"].includes(message.type)) return;
    if (!validId(message.id)) throw error("PROTOCOL_ERROR", "Missing valid operation id");
    if (message.type === "query") { await this.query(message.id); return; }
    if (message.type === "ack") {
      const record = this.records.get(message.id);
      if (record && terminal(record.response)) {
        record.acked = true;
        if (record.response.context) delete record.response.context.afterSnapshot;
        await this.journal.save(record); // retain terminal and digest tombstone, never allow reexecution
      }
      return;
    }
    const descriptor = this.methods.get(message.method);
    if (!descriptor || descriptor.version !== message.methodVersion || !descriptor.validate(message.params) ||
        !message.context || typeof message.context !== "object" || Array.isArray(message.context)) {
      this.safeSend(this.response(message, { status: "failed", effect: "none", error: { code: descriptor && descriptor.version === message.methodVersion ? "INVALID_ARGUMENT" : "UNSUPPORTED", message: "Unsupported method/version or invalid parameters" } })); return;
    }
    const digest = hash({ tabKey: TAB_KEY, method: message.method, methodVersion: message.methodVersion,
      params: message.params, context: message.context, startBefore: message.startBefore });
    const old = this.records.get(message.id);
    if (old) {
      if (old.digest !== digest) this.safeSend(this.response(message, { status: "failed", effect: "none", error: { code: "ID_CONFLICT", message: "Operation id is already bound to another intent" } }));
      else await this.query(message.id);
      return;
    }
    if (!descriptor.mutates && !descriptor.durable) {
      // Read-only query retries have no durable execution identity or modification lock.
      try {
        const result = await this.adapter.dispatch(async ctx => {
          const result = await descriptor.execute(message, ctx);
          if (!descriptor.validateResult(result)) throw error("INTERNAL_ERROR", "Invalid executor result");
          return descriptor.verify(ctx, undefined, result);
        });
        this.safeSend(this.response(message, { status: result.status, effect: result.effect, data: result.data,
          context: { afterSnapshot: result.after, currentSnapshot: result.after } }));
      } catch (e) {
        this.safeSend(this.response(message, { status: "failed", effect: "none", error: { code: e.code ?? "INTERNAL_ERROR", message: e.message } }));
      }
      return;
    }
    const record = { id: message.id, tabKey: TAB_KEY, runtimeId: this.adapter.runtimeId, pid: process.pid, digest, request: clone(message),
      response: this.response(message, { status: "accepted" }) };
    await this.journal.save(record); // intent must be durable before accepted or dispatch
    this.records.set(record.id, record);
    this.safeSend(record.response);
    if (descriptor.mutates && this.lock) {
      const response = this.response(message, { status: "failed", effect: "none", error: { code: "BUSY", message: "A prior operation requires reconciliation" } });
      await this.journal.save({ ...record, response }); record.response = response;
      this.safeSend(record.response); return;
    }
    if (descriptor.mutates) this.lock = record.id;
    // Dispatch starts immediately; no waitForIdle and no delayed execution after timeout.
    void this.execute(record, descriptor).catch(() => {});
  }
  async prepareHandoff(record, expected) {
    const config = await this.journal.handoff(record, expected);
    record.handoff = config.ticket;
    await this.journal.save(record);
    return config;
  }
  async execute(record, descriptor) {
    let effectStarted = false, response;
    try {
      const result = await this.adapter.dispatch(async ctx => {
        const before = descriptor.preflight?.(record.request, ctx);
        record.response = this.response(record.request, { status: "running" });
        await this.journal.save(record); this.safeSend(record.response);
        // Disk I/O yielded: recheck immediately before entering the executor.
        if (descriptor.preflight) descriptor.preflight(record.request, ctx);
        const result = await descriptor.execute(record.request, ctx, before, () => { effectStarted = true; },
          expected => this.prepareHandoff(record, expected));
        if (!descriptor.validateResult(result)) throw error("INTERNAL_ERROR", "Invalid executor result", effectStarted ? "unknown" : "none");
        return descriptor.verify(ctx, before, result);
      });
      if (result.handoff === true && record.handoff) return;
      response = this.response(record.request, { status: result.status, effect: result.effect, data: result.data,
        error: result.error, context: { afterSnapshot: result.after } });
    } catch (e) {
      if (record.handoff) return; // Old runtime relinquished its right to write the outcome.
      response = this.response(record.request, { status: "failed", effect: effectStarted ? "unknown" : (e.effect ?? "none"),
        error: { code: e.code ?? "INTERNAL_ERROR", message: e.message ?? "Operation failed" } });
    }
    // This witness proves the executor settled, even if its outcome cannot be logged.
    // It is separate from success evidence and can only enable explicit reconciliation.
    await this.journal.markEnded(record).catch(() => {});
    try {
      await this.journal.save({ ...record, response }); // terminal before response; queries still see running until fsync
      record.response = response;
    } catch (_e) {
      // Keep an in-memory nonterminal too, so queries cannot report an undurable success.
      record.response = this.response(record.request, { status: "running", effect: "unknown",
        error: { code: "JOURNAL_WRITE_FAILED", message: "Result could not be persisted; reconciliation required" } });
      this.safeSend(record.response); return;
    }
    // A durable terminal proves execution has ended. The IDE keeps its business lock
    // for effect=unknown until explicit reconciliation of a fresh snapshot.
    if (this.lock === record.id) this.lock = undefined;
    this.safeSend({ ...record.response, context: await this.snapshotContext(record) });
  }
}

let seq = Date.now() * 1000;
let sessionId;
let lastRunStopReason;
// v1 remains for notifications/file refresh and the legacy fork workflow only.
function send(type, data) {
  if (!enabled()) return;
  const payload = JSON.stringify({ v: 1, seq: seq++, type, tabKey: TAB_KEY, token: TOKEN, data }) + "\n";
  const attempt = timeout => {
    const socket = net.connect(PORT, "127.0.0.1");
    socket.setTimeout(timeout, () => socket.destroy());
    socket.on("error", () => socket.destroy());
    socket.on("connect", () => socket.end(payload));
  };
  attempt(500);
  const retry = setTimeout(() => attempt(1500), 1500); retry.unref?.();
}
function modelLabel(model) { return model?.id ? (model.provider ? `${model.provider}/${model.id}` : model.id) : undefined; }
function refreshSessionId(ctx) { sessionId = ctx?.sessionManager?.getSessionId?.() ?? sessionId; }
function reportModel(model) { const modelId = modelLabel(model); if (modelId) send("model_changed", { modelId }); }

export default function (pi) {
  if (!enabled()) return;
  const runtimeId = crypto.randomUUID();
  const adapter = new PiAdapter(pi, runtimeId);
  const journal = new OperationJournal(JOURNAL_DIR, runtimeId);
  let transport, runtime, closed = false, snapshotTimer;
  const snapshot = () => adapter.dispatch(ctx => adapter.capture(ctx));
  const publishSnapshot = () => {
    clearTimeout(snapshotTimer);
    snapshotTimer = setTimeout(() => {
      if (!closed && transport) void snapshot().then(s => transport.send(frame("event", { event: "session.snapshot", snapshot: s }))).catch(e => {
        try { transport.send(frame("event", { event: "context_unavailable", error: { code: e.code ?? "INTERNAL_ERROR", message: "Full snapshot unavailable" } })); } catch (_e) {}
      });
    }, 100); snapshotTimer.unref?.();
  };
  pi.on("session_start", (_event, ctx) => {
    lastRunStopReason = undefined; refreshSessionId(ctx); reportModel(ctx.model);
    // Defer command dispatch until the event boundary has drained.
    const startup = setTimeout(async () => {
      if (closed) return;
      await adapter.probe();
      if (closed) return;
      let writable = false;
      runtime = new OperationRuntime(adapter, journal, response => transport?.send(response));
      try { await runtime.open(); writable = true; }
      catch (_e) { /* Read-only transport remains useful; never execute with an unavailable journal. */ }
      if (closed) { await journal.close().catch(() => {}); return; }
      const capabilities = adapter.probePassed ? { "session.snapshot": [1] } : {};
      if (writable && adapter.probePassed) capabilities["operation.reconcile"] = [1];
      if (writable && adapter.probePassed && PI_VERSION === "1.5.0") capabilities["conversation.rewind"] = [1];
      transport = new Transport(() => frame("hello", { token: TOKEN, tabKey: TAB_KEY, runtimeId,
        piVersion: PI_VERSION, extensionVersion: String(VERSION), capabilities, sessionId }),
        message => {
          if (message.type === "request" && !capabilities[message.method]) {
            runtime.safeSend(runtime.response(message, { status: "failed", effect: "none", error: { code: "UNSUPPORTED", message: "Method capability is unavailable" } })); return;
          }
          return runtime.enqueue(message);
        }, reachable => { if (reachable) publishSnapshot(); });
      transport.start();
    }, 0); startup.unref?.();
    // v1 is retained for old IDE installations. A v2 owner ignores these bindings.
    send("session_changed", { sessionId, extensionVersion: String(VERSION) });
  });
  pi.on("session_shutdown", async () => {
    closed = true; clearTimeout(snapshotTimer); transport?.close(); adapter.close();
    await journal.close().catch(() => {});
    send("session_stopped", {});
  });
  for (const name of ["session_tree", "session_compact", "session_info_changed", "message_end", "model_select"]) {
    pi.on(name, () => { adapter.generation++; publishSnapshot(); });
  }
  pi.on("agent_start", () => { adapter.generation++; lastRunStopReason = undefined; send("agent_state", { state: "working" }); });
  pi.on("agent_end", event => {
    const messages = event?.messages ?? [];
    lastRunStopReason = [...messages].reverse().map(m => m?.message ?? m).find(m => m?.role === "assistant")?.stopReason;
  });
  pi.on("agent_settled", event => {
    adapter.generation++; publishSnapshot();
    const stopReason = event?.aborted ? "aborted" : lastRunStopReason === "error" ? "error" : undefined;
    lastRunStopReason = undefined; send("agent_state", stopReason ? { state: "idle", stopReason } : { state: "idle" });
  });
  pi.on("model_select", event => reportModel(event.model));
  pi.on("tool_result", event => {
    const data = event?.data ?? event ?? {};
    if (!["edit", "write", "patch", "apply"].includes(String(data.toolName ?? data.tool ?? ""))) return;
    const input = data.input ?? data.args ?? {}, details = data.details ?? data.result ?? {};
    const paths = [input.path, input.filePath, input.file_path, input.file, details.path, details.filePath, details.file_path,
      ...(Array.isArray(input.edits) ? input.edits.map(e => typeof e === "string" ? e : e?.path) : [])];
    for (const p of paths) if (typeof p === "string" && p.length) send("file_modified", { path: p });
  });

  // Legacy fork remains a CLI copy + one Terminal command; it is not a v2 capability.
  pi.registerCommand("bridge-fork", {
    description: "Internal legacy fork (Pi Terminal Bridge)",
    handler: async (args, ctx) => {
      try {
        const parts = String(args ?? "").trim().split(/\s+/);
        if (parts.length !== 2 || !/^\d+$/.test(parts[0]) || !Number.isSafeInteger(Number(parts[0])) ||
            !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(parts[1])) throw error("INVALID_ARGUMENT", "bridge-fork: bad arguments");
        const bytes = Buffer.from(parts[1], "base64"), text = bytes.toString("utf8");
        if (!text || bytes.toString("base64") !== parts[1] || !Buffer.from(text).equals(bytes)) throw error("INVALID_ARGUMENT", "bridge-fork: bad arguments");
        const entries = ctx.sessionManager.getBranch();
        const users = entries.filter(e => e.type === "message" && e.message?.role === "user");
        let user = users[Number(parts[0])];
        if (!user || textOf(user.message.content) !== text) {
          const matches = users.filter(e => textOf(e.message.content) === text);
          if (matches.length !== 1) throw error("AMBIGUOUS_TARGET", "bridge-fork: matching message not found or ambiguous");
          user = matches[0];
        }
        let leaf = user;
        for (let i = entries.indexOf(user) + 1; i < entries.length; i++) {
          if (entries[i].type === "message" && entries[i].message?.role === "user") break;
          leaf = entries[i];
        }
        const result = await ctx.fork(leaf.id, { position: "at" });
        if (result?.cancelled) return;
        // Old context is invalid after replacement; no notification through it.
      } catch (e) { try { ctx.ui.notify(e.message ?? "bridge-fork failed", "error"); } catch (_e) {} }
    },
  });
}
