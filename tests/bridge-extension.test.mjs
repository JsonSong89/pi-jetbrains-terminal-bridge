import { readFileSync } from 'node:fs';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// Run the packaged artifact. Only the host-provided version import and build placeholder change.
const source = readFileSync(new URL('../src/main/resources/bridge/pi-launcher-bridge.ts', import.meta.url), 'utf8')
  .replace('import { VERSION as PI_VERSION } from "@earendil-works/pi-coding-agent";', 'const PI_VERSION = "1.5.0";')
  .replace('@EXTENSION_VERSION@', '8').replace('export default function (pi)', 'function install(pi)');
const plain = v => JSON.parse(JSON.stringify(v));
const message = (id, parentId, role, content) => ({ id, parentId, type: 'message', message: { role, content, timestamp: 1 } });
const branch = [message('u1', null, 'user', 'one'), message('a1', 'u1', 'assistant', 'reply'),
  message('u2', 'a1', 'user', 'two'), message('call', 'u2', 'assistant', 'tool call'),
  message('tool', 'call', 'toolResult', 'tool result'), message('a2', 'tool', 'assistant', 'reply two'),
  message('u3', 'a2', 'user', 'three'), message('a3', 'u3', 'assistant', 'reply three')];
function module(env = {}, nativeNet = net, timers = { setTimeout, clearTimeout, setInterval, clearInterval }) {
  const sandbox = vm.createContext({ Buffer, ...timers,
    process: { env: { PI_LAUNCHER_PORT: '1', PI_LAUNCHER_TOKEN: 'test', PI_LAUNCHER_TAB_KEY: 'tab-1', ...env },
      pid: process.pid, platform: process.platform, kill: process.kill },
    require: name => ({ 'node:fs/promises': fs, 'node:path': path, 'node:crypto': crypto, 'node:net': nativeNet })[name],
  });
  return vm.runInContext(source + '\n({PiAdapter, OperationRuntime, OperationJournal, Transport, install, hash, frame});', sandbox);
}
async function harness(t, entries = branch, options = {}) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pi-bridge-contract-'));
  const cleanup = [];
  t.after(async () => { for (const close of cleanup.reverse()) await close(); await fs.rm(directory, { recursive: true, force: true }); });
  const api = module(options.env);
  const commands = new Map(), handlers = new Map(), sent = [], calls = [];
  let active = structuredClone(entries), sessionId = 'session-1', editor = 'unsent TUI draft 🐈', busy = false, modelPrompts = 0;
  const ctx = { mode: 'tui', model: { provider: 'test', id: 'model' },
    sessionManager: { getSessionId: () => sessionId, getHeader: () => ({ version: 3, id: sessionId }),
      getSessionFile: () => '/custom/session.jsonl', getLeafId: () => active.at(-1)?.id ?? null, getBranch: () => structuredClone(active) },
    isIdle: () => !busy, hasPendingMessages: () => false, ui: { getEditorText: () => editor },
    navigateTree: async id => {
      calls.push(id);
      if (options.navigation) return options.navigation({ ctx, active, setBranch: value => { active = value; } });
      const at = active.findIndex(e => e.id === id);
      const target = active[at];
      // Faithful public semantics: selecting user/custom_message moves BEFORE it.
      active = active.slice(0, target?.message?.role === 'user' || target?.type === 'custom_message' ? at : at + 1);
      for (const handler of handlers.get('session_tree') ?? []) await handler({}, ctx);
      return { cancelled: false };
    },
  };
  const pi = { registerCommand: (name, command) => commands.set(name, command),
    on(name, handler) { if (!handlers.has(name)) handlers.set(name, []); handlers.get(name).push(handler); },
    sendUserMessage(text, options) {
      const [name, id] = text.slice(1).split(' ');
      if (options.expandPromptTemplates && commands.has(name)) {
        void commands.get(name).handler(id, { ...ctx });
      } else {
        if (!(handlers.get('input') ?? []).some(h => h({ text })?.action === 'handled')) modelPrompts++;
      }
    },
  };
  const adapter = new api.PiAdapter(pi, 'runtime-1');
  await adapter.probe();
  const journal = new api.OperationJournal(directory, 'runtime-1');
  const runtime = new api.OperationRuntime(adapter, journal, response => sent.push(plain(response)));
  await runtime.open();
  cleanup.push(() => journal.close());
  const request = (id = crypto.randomUUID(), targetEntryId = 'u2') => {
    const snapshot = adapter.capture(ctx);
    return { v: 2, type: 'request', id, method: 'conversation.rewind', methodVersion: 1,
      params: { targetEntryId }, context: { sessionId, observedSnapshotId: snapshot.snapshotId } };
  };
  const waitResult = async id => {
    for (let i = 0; i < 200; i++) {
      const result = sent.findLast(r => r.id === id && ['succeeded', 'failed', 'cancelled'].includes(r.status));
      if (result) return result;
      await new Promise(resolve => setTimeout(resolve, 5));
    }
    throw new Error('No terminal result for ' + id);
  };
  return { api, adapter, journal, runtime, ctx, sent, calls, request, waitResult, commands, pi, directory, cleanup,
    current: () => structuredClone(active), editor: () => editor, modelPrompts: () => modelPrompts,
    setBusy: value => { busy = value; }, setSession: id => { sessionId = id; }, setBranch: value => { active = value; } };
}

test('public command probe does not change TUI draft or issue a model prompt', async t => {
  const h = await harness(t);
  assert.equal(h.adapter.probePassed, true);
  assert.equal(h.editor(), 'unsent TUI draft 🐈'); assert.equal(h.modelPrompts(), 0);
});
test('dispatch deadline is cleared at command entry, never while execution awaits user input', async () => {
  const scheduled = new Map(); let nextTimer = 0, command;
  const api = module({}, net, { setTimeout: fn => { const id = ++nextTimer; scheduled.set(id, fn); return id; }, clearTimeout: id => scheduled.delete(id), setInterval, clearInterval });
  const adapter = new api.PiAdapter({ on() {}, registerCommand: (_name, handler) => { command = handler; },
    sendUserMessage: text => { void command.handler(text.split(' ')[1], {}); } }, 'runtime');
  let finish;
  const execution = adapter.dispatch(async () => { await new Promise(resolve => { finish = resolve; }); return 'done'; });
  assert.equal(scheduled.size, 0); // An accepted navigation can now remain running indefinitely.
  finish(); assert.equal(await execution, 'done');
});

test('normal rewind confirms the actual complete branch and preserves editor', async t => {
  const h = await harness(t), req = h.request();
  await h.runtime.enqueue(req);
  assert.equal(h.sent[0].status, 'accepted');
  const result = await h.waitResult(req.id);
  assert.equal(result.status, 'succeeded'); assert.equal(result.effect, 'applied');
  assert.deepEqual(h.calls, ['a1']); assert.deepEqual(h.current().map(e => e.id), ['u1', 'a1']);
  assert.deepEqual(result.context.afterSnapshot.branch.entries.map(e => e.id), ['u1', 'a1']);
  assert.equal(h.editor(), 'unsent TUI draft 🐈'); assert.equal(h.modelPrompts(), 0);
});
test('duplicate id executes once; changed parameters return ID_CONFLICT', async t => {
  const h = await harness(t), req = h.request();
  await h.runtime.enqueue(req); await h.waitResult(req.id);
  await h.runtime.enqueue(req);
  await h.runtime.enqueue({ ...req, params: { targetEntryId: 'u3' } });
  assert.equal(h.calls.length, 1); assert.equal(h.sent.at(-1).error.code, 'ID_CONFLICT');
});
test('lost accepted and terminal responses never change execution or trigger replay', async t => {
  const h = await harness(t), request = h.request('lost-replies'), send = h.runtime.send;
  h.runtime.send = () => { throw Object.assign(new Error('socket lost'), { code: 'DISCONNECTED' }); };
  await h.runtime.enqueue(request);
  for (let n = 0; n < 500 && h.runtime.records.get(request.id)?.response.status !== 'succeeded'; n++) await new Promise(r => setTimeout(r, 2));
  assert.equal((await h.journal.read(request.id)).response.status, 'succeeded');
  assert.equal(h.calls.length, 1);
  h.runtime.send = send;
  await h.runtime.enqueue({ type: 'query', id: request.id });
  assert.equal(h.sent.at(-1).status, 'succeeded');
  await h.runtime.enqueue(request); assert.equal(h.calls.length, 1);
});

test('busy is rejected immediately without waiting for a future idle', async t => {
  const h = await harness(t), req = h.request(); h.setBusy(true);
  await h.runtime.enqueue(req);
  assert.equal((await h.waitResult(req.id)).error.code, 'BUSY');
  h.setBusy(false); await h.runtime.enqueue({ type: 'query', id: req.id });
  assert.deepEqual(h.calls, []);
});
test('cancelled navigation is checked and only unchanged branches get effect none', async t => {
  for (const changed of [false, true]) {
    const h = await harness(t, branch, { navigation: ({ active, setBranch }) => {
      if (changed) setBranch(active.slice(0, 1)); return { cancelled: true };
    } });
    const req = h.request(); await h.runtime.enqueue(req);
    const result = await h.waitResult(req.id);
    assert.equal(result.status, 'cancelled'); assert.equal(result.effect, changed ? 'unknown' : 'none');
  }
});
test('root, consecutive user and custom-message parents reject before navigation', async t => {
  for (const entries of [[message('u2', null, 'user', 'two')],
    [message('u1', null, 'user', 'one'), message('u2', 'u1', 'user', 'two')],
    [{ id: 'custom', parentId: null, type: 'custom_message', content: 'custom' }, message('u2', 'custom', 'user', 'two')]]) {
    const h = await harness(t, entries), req = h.request(); await h.runtime.enqueue(req);
    assert.equal((await h.waitResult(req.id)).error.code, 'UNSUPPORTED'); assert.deepEqual(h.calls, []);
  }
});
test('an unanswered prompt can rewind when its parent has safe navigation semantics', async t => {
  const h = await harness(t, branch.slice(0, 3)), req = h.request(); await h.runtime.enqueue(req);
  assert.equal((await h.waitResult(req.id)).status, 'succeeded');
});
test('stale session, expired deadline and changed native content reject without mutation', async t => {
  for (const change of [h => h.setSession('other'), h => { const entries = h.current(); entries[1].extraNativeField = 1; h.setBranch(entries); }]) {
    const h = await harness(t), req = h.request(); change(h); await h.runtime.enqueue(req);
    assert.equal((await h.waitResult(req.id)).error.code, 'STALE_CONTEXT'); assert.deepEqual(h.calls, []);
  }
  const h = await harness(t), req = { ...h.request(), startBefore: 1 }; await h.runtime.enqueue(req);
  assert.equal((await h.waitResult(req.id)).error.code, 'STALE_CONTEXT');
});
test('snapshots retain unknown entries and fields; fingerprint covers content beyond leaf', async t => {
  const entries = structuredClone(branch); entries[1].futureField = { value: 'kept' };
  const h = await harness(t, entries), before = h.adapter.capture(h.ctx);
  assert.deepEqual(plain(before.branch.entries[1].futureField), { value: 'kept' });
  entries[1].futureField.value = 'changed'; h.setBranch(entries);
  assert.notEqual(h.adapter.capture(h.ctx).snapshotId, before.snapshotId);
});
test('lookup NOT_FOUND is a lookup result, never a fabricated failed execution', async t => {
  const h = await harness(t); await h.runtime.enqueue({ type: 'query', id: 'missing' });
  assert.deepEqual(h.sent.at(-1), { v: 2, type: 'response', id: 'missing', lookup: 'not_found' });
});
test('historical afterSnapshot and newly observed currentSnapshot stay distinct', async t => {
  const h = await harness(t), req = h.request(); await h.runtime.enqueue(req); await h.waitResult(req.id);
  h.setBranch(branch); await h.runtime.enqueue({ type: 'query', id: req.id });
  const result = h.sent.at(-1);
  assert.equal(result.context.afterSnapshot.branch.leafId, 'a1'); assert.equal(result.context.currentSnapshot.branch.leafId, 'a3');
  await h.runtime.enqueue({ type: 'ack', id: req.id });
  await h.runtime.enqueue({ type: 'query', id: req.id });
  assert.equal(h.sent.at(-1).context.afterSnapshot, undefined); assert.equal(h.sent.at(-1).status, 'succeeded');
});
test('reload recovers terminal tombstones but never resumes an incomplete intent', async t => {
  const h = await harness(t), req = h.request(); await h.runtime.enqueue(req); await h.waitResult(req.id);
  const intent = { id: 'interrupted', tabKey: 'tab-1', runtimeId: 'runtime-1', digest: 'proof', request: { ...req, id: 'interrupted' },
    response: { v: 2, type: 'response', id: 'interrupted', method: req.method, methodVersion: 1, status: 'running' } };
  await h.journal.save(intent); await h.journal.markEnded(intent); await h.journal.close();
  const nextJournal = new h.api.OperationJournal(h.directory, 'runtime-2');
  const freshAdapter = new h.api.PiAdapter(h.pi, 'runtime-2'); await freshAdapter.probe();
  const next = new h.api.OperationRuntime(freshAdapter, nextJournal, r => h.sent.push(plain(r)));
  await next.open(); h.cleanup.push(() => nextJournal.close());
  await next.enqueue({ type: 'query', id: req.id }); assert.equal(h.sent.at(-1).status, 'succeeded');
  await next.enqueue({ type: 'query', id: intent.id }); assert.equal(h.sent.at(-1).status, 'running');
  assert.equal(h.sent.at(-1).executionEnded, true);
  assert.equal(next.lock, 'interrupted'); assert.equal(h.calls.length, 1);
  const observed = freshAdapter.capture(h.ctx), reconcileId = crypto.randomUUID();
  await next.enqueue({ type: 'request', id: reconcileId, method: 'operation.reconcile', methodVersion: 1,
    params: { operationIds: [intent.id] }, context: { sessionId: observed.session.id, observedSnapshotId: observed.snapshotId } });
  assert.equal((await h.waitResult(reconcileId)).status, 'succeeded');
  assert.equal(next.lock, undefined); assert.equal(h.calls.length, 1);
  assert.equal(next.records.get(intent.id).response.status, 'running'); // reconciliation never fabricates success
});
test('a new runtime or released lease does not prove an old asynchronous executor ended', async t => {
  const h = await harness(t);
  const record = { id: 'unwinding', digest: 'proof', runtimeId: 'previous-runtime', pid: process.pid,
    tabKey: 'tab-1', response: { status: 'running' } };
  assert.equal(await h.journal.executionEnded(record), false);
  await h.journal.markEnded(record);
  assert.equal(await h.journal.executionEnded(record), true);
});

test('effect followed by failed terminal persistence is unknown, including queries', async t => {
  const h = await harness(t), req = h.request(), save = h.journal.save.bind(h.journal);
  h.journal.save = r => r.response?.status === 'succeeded' ? Promise.reject(new Error('disk full')) : save(r);
  await h.runtime.enqueue(req);
  for (let i = 0; i < 200 && !h.sent.some(r => r.error?.code === 'JOURNAL_WRITE_FAILED'); i++) await new Promise(r => setTimeout(r, 5));
  assert.equal(h.calls.length, 1); assert.ok(!h.sent.some(r => r.status === 'succeeded'));
  await h.runtime.enqueue({ type: 'query', id: req.id }); assert.equal(h.sent.at(-1).effect, 'unknown');
});
test('queries cannot see a success while its terminal fsync is blocked', async t => {
  const h = await harness(t), req = h.request(), save = h.journal.save.bind(h.journal);
  let release, saving;
  const reached = new Promise(r => { saving = r; });
  h.journal.save = async r => {
    if (r.response?.status === 'succeeded') { saving(); await new Promise(r => { release = r; }); }
    return save(r);
  };
  await h.runtime.enqueue(req); await reached;
  await h.runtime.enqueue({ type: 'query', id: req.id }); assert.equal(h.sent.at(-1).status, 'running');
  release(); await h.waitResult(req.id);
});
test('a replacement descriptor uses the same request/response and durable one-writer handoff', async t => {
  const h = await harness(t), req = { ...h.request(), method: 'test.replace', params: {} };
  let ticket;
  h.runtime.register('test.replace', { version: 1, mutates: true, validate: () => true,
    execute: async (_req, _ctx, _before, markEffect, handoff) => {
      markEffect(); ticket = await handoff({ expectedSession: 'replacement' }); return { handoff: true };
    } });
  await h.runtime.enqueue(req);
  for (let i = 0; i < 200 && !ticket; i++) await new Promise(r => setTimeout(r, 5));
  assert.ok(ticket); assert.ok(!h.sent.some(r => r.id === req.id && r.status === 'succeeded'));
  await h.journal.close();
  const nextJournal = new h.api.OperationJournal(h.directory, 'runtime-2');
  const next = new h.api.OperationRuntime(h.adapter, nextJournal, r => h.sent.push(plain(r))); await next.open(); h.cleanup.push(() => nextJournal.close());
  await next.enqueue({ type: 'query', id: req.id }); assert.equal(h.sent.at(-1).status, 'running'); // new hello alone proves nothing
  const verify = async (fresh, expected) => {
    assert.equal(fresh.newSession, expected.expectedSession); return { status: 'succeeded', effect: 'applied', data: { sessionId: fresh.newSession } };
  };
  await h.api.OperationJournal.finishHandoff(ticket, { newSession: 'replacement' }, verify);
  await assert.rejects(h.api.OperationJournal.finishHandoff(ticket, { newSession: 'replacement' }, verify), { code: 'EEXIST' });
  await next.enqueue({ type: 'query', id: req.id }); assert.equal(h.sent.at(-1).status, 'succeeded'); assert.equal(next.lock, undefined);
});
test('oversized complete snapshot is rejected explicitly, never silently truncated', async t => {
  const h = await harness(t, [message('u1', null, 'user', 'x'.repeat(20000))], { env: { PI_BRIDGE_MAX_FRAME_BYTES: '16384' } });
  assert.throws(() => h.adapter.capture(h.ctx), { code: 'CONTEXT_TOO_LARGE' });
});
test('corrupt logs fail closed and a live writer lease cannot be stolen', async t => {
  const h = await harness(t), competing = new h.api.OperationJournal(h.directory, 'competitor');
  await assert.rejects(competing.open(), { code: 'BUSY' });
  await h.journal.close(); await fs.writeFile(path.join(h.directory, 'corrupt.json'), '{');
  const corrupt = new h.api.OperationJournal(h.directory, 'replacement');
  await assert.rejects(corrupt.open()); await corrupt.close();
});
test('framing keeps split UTF-8 and Unicode separators; reconnect does not replay request', async t => {
  const server = net.createServer(); await new Promise(r => server.listen(0, '127.0.0.1', r));
  const sockets = []; t.after(() => { for (const socket of sockets) socket.destroy(); server.close(); });
  const api = module({ PI_LAUNCHER_PORT: String(server.address().port) });
  let received, connections = 0, executions = 0;
  const done = new Promise(resolve => { received = resolve; });
  server.on('connection', socket => {
    sockets.push(socket); connections++;
    let buffer = '';
    socket.on('data', bytes => {
      buffer += bytes.toString('utf8');
      while (buffer.includes('\n')) {
        const at = buffer.indexOf('\n'), record = JSON.parse(buffer.slice(0, at)); buffer = buffer.slice(at + 1);
        if (record.type === 'hello') {
          socket.write('{"v":2,"type":"welcome","protocolVersion":2}\r\n');
          const data = Buffer.from('{"v":2,"type":"event","text":"你好\u2028\u2029"}\n');
          socket.write(data.subarray(0, 35)); socket.write(data.subarray(35));
        } else if (record.type === 'request') { executions++; socket.destroy(); }
      }
    });
  });
  const observed = [];
  const transport = new api.Transport(() => ({ v: 2, type: 'hello' }), m => {
    observed.push(plain(m));
    if (connections === 1) transport.send({ v: 2, type: 'request', id: 'execute-once' });
    else received();
  }, () => {});
  t.after(() => transport.close()); transport.start();
  await Promise.race([done, new Promise((_, reject) => setTimeout(() => reject(new Error('Reconnect timeout')), 5000).unref())]);
  assert.equal(observed[0].text, '你好\u2028\u2029'); assert.equal(executions, 1);
});
test('legacy fork retains the complete tool-assisted turn and rejects ambiguous fallback', async () => {
  const sent = [], commands = new Map(), handlers = new Map(), calls = [], notices = [];
  const fakeNet = { connect: () => ({ setTimeout() {}, destroy() {}, end: payload => sent.push(JSON.parse(payload)),
    on(event, callback) { if (event === 'connect') callback(); return this; } }) };
  const api = module({}, fakeNet);
  api.install({ registerCommand: (name, command) => commands.set(name, command),
    on(name, handler) { if (!handlers.has(name)) handlers.set(name, []); handlers.get(name).push(handler); } });
  let entries = structuredClone(branch);
  const ctx = { sessionManager: { getBranch: () => entries },
    fork: async (id, options) => { calls.push([id, options.position]); return { cancelled: false }; },
    ui: { notify: (text, level) => notices.push({ text, level }) } };
  const args = (index, text) => `${index} ${Buffer.from(text).toString('base64')}`;
  await commands.get('bridge-fork').handler(args(1, 'two'), ctx);
  assert.deepEqual(calls, [['a2', 'at']]);
  entries[6].message.content = 'two';
  await commands.get('bridge-fork').handler(args(99, 'two'), ctx);
  assert.equal(calls.length, 1); assert.ok(notices.some(n => n.text.includes('ambiguous')));
  assert.ok(!commands.has('bridge-rewind')); // no Terminal control fallback
  const emit = async (name, event) => { for (const h of handlers.get(name) ?? []) await h(event, ctx); };
  await emit('agent_start', {});
  await emit('agent_end', { messages: [{ role: 'assistant', stopReason: 'error' }] });
  await emit('agent_settled', { aborted: false });
  assert.deepEqual(sent.findLast(m => m.type === 'agent_state').data, { state: 'idle', stopReason: 'error' });
  await emit('agent_start', {});
  await emit('agent_end', { messages: [{ role: 'assistant', stopReason: 'stop' }] });
  await emit('agent_settled', { aborted: false });
  assert.deepEqual(sent.findLast(m => m.type === 'agent_state').data, { state: 'idle' });
  await emit('model_select', { model: { provider: 'test', id: 'model' } });
  assert.equal(sent.findLast(m => m.type === 'model_changed').data.modelId, 'test/model');
  await emit('session_shutdown', {});
});

test('unmanaged pi registers no commands or background resources', () => {
  let registered = 0; module({ PI_LAUNCHER_PORT: '' }).install({ registerCommand: () => registered++, on: () => registered++ });
  assert.equal(registered, 0);
});
