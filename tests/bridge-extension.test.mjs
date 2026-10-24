import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// Execute the actual packaged resource, not a duplicate implementation.
const source = readFileSync(new URL('../src/main/resources/bridge/pi-launcher-bridge.ts', import.meta.url), 'utf8')
  .replace('@EXTENSION_VERSION@', '7')
  .replace('export default function (pi)', 'function install(pi)');
const message = (id, parentId, role, content) => ({ id, parentId, type: 'message', message: { role, content } });
const branch = [
  message('u1', null, 'user', 'pong1'), message('a1', 'u1', 'assistant', 'pong1'),
  message('u2', 'a1', 'user', 'pong2'), message('call2', 'u2', 'assistant', 'tool call'),
  message('tool2', 'call2', 'toolResult', 'tool output'), message('a2', 'tool2', 'assistant', 'pong2'),
  message('u3', 'a2', 'user', 'pong3'), message('a3', 'u3', 'assistant', 'pong3'),
];
function harness(entries = branch, enabled = true) {
  const commands = new Map();
  const handlers = new Map();
  const calls = [], notices = [], sent = [];
  const sandbox = vm.createContext({
    Buffer,
    process: { env: enabled ? {
      PI_LAUNCHER_PORT: '12345', PI_LAUNCHER_TOKEN: 'test', PI_LAUNCHER_TAB_KEY: 'test',
    } : {} },
    // send() is best-effort and opens a real loopback socket. Capture the
    // payloads instead so event-handler tests can assert protocol data.
    require(name) {
      if (name !== 'node:net') throw new Error(`unexpected require: ${name}`);
      return { connect: () => ({
        setTimeout() {},
        on(event, callback) { if (event === 'connect') callback(); },
        write(payload) { sent.push(JSON.parse(payload.trim())); },
        end() {}, destroy() {},
      }) };
    },
    setTimeout: () => ({ unref() {} }),
  });
  vm.runInContext(source + '\ninstall(pi);', Object.assign(sandbox, { pi: {
    on(name, handler) {
      if (!handlers.has(name)) handlers.set(name, []);
      handlers.get(name).push(handler);
    },
    registerCommand(name, command) { commands.set(name, command); },
  } }));
  const ctx = {
    sessionManager: {
      getBranch: () => structuredClone(entries),
      getSessionId: () => 'session-1',
    },
    model: { provider: 'anthropic', id: 'claude-sonnet-4-5', name: 'Claude Sonnet 4.5' },
    ui: { notify: (text, level) => notices.push({ text, level }) },
    fork: async (id, options) => { calls.push(['fork', id, options.position]); },
    navigateTree: async id => { calls.push(['rewind', id]); },
  };
  return { commands, calls, notices, sent, ctx,
    emit: async (name, event = {}, eventCtx = ctx) => {
      for (const handler of handlers.get(name) ?? []) await handler(event, eventCtx);
    },
    run: (name, args) => commands.get(name).handler(args, ctx) };
}
const args = (index, text) => `${index} ${Buffer.from(text).toString('base64')}`;

test('fork pong2 retains entire tool-assisted turn, excludes pong3, no editor text', async () => {
  const h = harness();
  await h.run('bridge-fork', args(1, 'pong2'));
  assert.deepEqual(h.calls, [['fork', 'a2', 'at']]);
  assert.ok(!h.notices.some(n => n.level === 'error'));
});
test('rewind pong2 navigates before user message without forking', async () => {
  const h = harness();
  await h.run('bridge-rewind', args(1, 'pong2'));
  assert.deepEqual(h.calls, [['rewind', 'a1']]);
  assert.deepEqual(h.notices, []);
});
test('commands remain independent when invoked consecutively', async () => {
  const h = harness();
  await h.run('bridge-fork', args(2, 'pong3'));
  await h.run('bridge-rewind', args(2, 'pong3'));
  assert.deepEqual(h.calls, [['fork', 'a3', 'at'], ['rewind', 'a2']]);
});
test('fork unanswered message includes user entry', async () => {
  const h = harness(branch.slice(0, -1));
  await h.run('bridge-fork', args(2, 'pong3'));
  assert.deepEqual(h.calls, [['fork', 'u3', 'at']]);
});
test('UTF-8 multiline text and text-part content decode correctly', async () => {
  const text = '你好\nsecond line 🐈';
  const h = harness([message('u', null, 'user', [{ type: 'text', text }])]);
  await h.run('bridge-fork', args(0, text));
  assert.deepEqual(h.calls, [['fork', 'u', 'at']]);
});
test('index drift falls back to exact text', async () => {
  const h = harness();
  await h.run('bridge-fork', args(99, 'pong2'));
  assert.deepEqual(h.calls, [['fork', 'a2', 'at']]);
});
test('duplicate prompts prefer matching index', async () => {
  const entries = structuredClone(branch);
  entries[6].message.content = 'pong2';
  const h = harness(entries);
  await h.run('bridge-fork', args(1, 'pong2'));
  assert.deepEqual(h.calls, [['fork', 'a2', 'at']]);
});
test('malformed/concatenated commands never mutate either session', async () => {
  for (const name of ['bridge-fork', 'bridge-rewind']) {
    for (const input of ['', 'bad', '-1 cG9uZzI=', '0 !!!', '0 /w==', '0 cG9uZzI= junk', '9007199254740992 cG9uZzI=']) {
      const h = harness();
      await h.run(name, input);
      assert.deepEqual(h.calls, [], `${name}: ${input}`);
      assert.match(h.notices[0].text, /bad arguments/);
    }
  }
});
test('missing message warns without mutating history', async () => {
  for (const name of ['bridge-fork', 'bridge-rewind']) {
    const h = harness();
    await h.run(name, args(1, 'not present'));
    assert.deepEqual(h.calls, []);
    assert.match(h.notices[0].text, /matching message not found/);
  }
});
test('first-entry rewind fails explicitly, does not fork', async () => {
  const h = harness();
  await h.run('bridge-rewind', args(0, 'pong1'));
  assert.deepEqual(h.calls, []);
  assert.match(h.notices[0].text, /first entry/);
});
test('API failures are visible and never reported as success', async () => {
  for (const name of ['bridge-fork', 'bridge-rewind']) {
    const h = harness();
    const fail = async () => { throw new Error('API failed'); };
    h.ctx.fork = fail;
    h.ctx.navigateTree = fail;
    await h.run(name, args(1, 'pong2'));
    assert.ok(h.notices.some(n => n.level === 'error' && n.text.includes('API failed')));
    assert.ok(!h.notices.some(n => n.text.includes('forked —')));
  }
});
test('session_start reports the session id and the current Model object', async () => {
  const h = harness();
  await h.emit('session_start', { type: 'session_start' });
  assert.deepEqual(h.sent.map(m => m.type), ['session_changed', 'model_changed']);
  assert.equal(h.sent[0].data.sessionId, 'session-1');
  assert.equal(h.sent[1].data.modelId, 'anthropic/claude-sonnet-4-5');
});

test('model_select reads event.model, not a modelId field', async () => {
  const h = harness();
  await h.emit('model_select', {
    type: 'model_select',
    model: { provider: 'openai', id: 'gpt-5', name: 'GPT-5' },
    previousModel: undefined,
    source: 'set',
  });
  assert.deepEqual(h.sent.map(m => m.data), [{ modelId: 'openai/gpt-5' }]);
});

test('agent_settled reports aborted from event.aborted', async () => {
  const h = harness();
  await h.emit('agent_start', { type: 'agent_start' });
  await h.emit('agent_settled', { type: 'agent_settled', aborted: true });
  const states = h.sent.filter(m => m.type === 'agent_state');
  assert.deepEqual(states.at(-1).data, { state: 'idle', stopReason: 'aborted' });
});

test('agent_settled reports error from the final assistant message', async () => {
  const h = harness();
  await h.emit('agent_start', { type: 'agent_start' });
  await h.emit('agent_end', {
    type: 'agent_end',
    messages: [{ role: 'assistant', stopReason: 'error', errorMessage: 'provider failed' }],
  });
  await h.emit('agent_settled', { type: 'agent_settled', aborted: false });
  const states = h.sent.filter(m => m.type === 'agent_state');
  assert.deepEqual(states.at(-1).data, { state: 'idle', stopReason: 'error' });
});

test('successful retry clears a previous error before settlement', async () => {
  const h = harness();
  await h.emit('agent_start', { type: 'agent_start' });
  await h.emit('agent_end', {
    type: 'agent_end',
    messages: [{ role: 'assistant', stopReason: 'error' }],
  });
  await h.emit('agent_start', { type: 'agent_start' });
  await h.emit('agent_end', {
    type: 'agent_end',
    messages: [{ role: 'assistant', stopReason: 'stop' }],
  });
  await h.emit('agent_settled', { type: 'agent_settled', aborted: false });
  const states = h.sent.filter(m => m.type === 'agent_state');
  assert.deepEqual(states.at(-1).data, { state: 'idle' });
});

test('unmanaged pi instances do not register commands', () => {
  assert.equal(harness(branch, false).commands.size, 0);
});
