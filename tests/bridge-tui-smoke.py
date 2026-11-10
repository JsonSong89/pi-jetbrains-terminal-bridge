#!/usr/bin/env python3
"""Opt-in isolated pi 1.5.0 PTY smoke for rewind and fork; never RPC or model input.
Run: PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py
"""
import fcntl
import json
import os
import pathlib
import pty
import select
import shutil
import socket
import struct
import subprocess
import tempfile
import termios
import threading
import time
import uuid


class BridgePeer:
    def __init__(self, client):
        self.client = client
        self.reader = client.makefile("rb")

    def close(self):
        self.reader.close()
        self.client.close()

    def send(self, **value):
        self.client.sendall((json.dumps({"v": 2, **value}) + "\n").encode())

    def receive(self):
        while True:
            line = self.reader.readline(32 * 1024 * 1024 + 1)
            if not line:
                raise EOFError("Bridge disconnected")
            assert len(line) <= 32 * 1024 * 1024, "Oversized bridge frame"
            value = json.loads(line)
            assert value.get("v") == 2, "Only authenticated v2 frames are supported"
            assert value.get("event") != "agent.finished", "Fork/Rewind must not emit agent completion"
            if value["type"] == "ping":
                self.send(type="pong")
            elif value["type"] != "pong":
                return value


def accept_bridge(server):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        client, _ = server.accept()
        client.settimeout(15)
        peer = BridgePeer(client)
        hello = peer.receive()
        if hello.get("v") == 2 and hello.get("type") == "hello":
            assert hello["piVersion"] == "1.5.0", hello.get("piVersion")
            peer.send(type="welcome", protocolVersion=2, connectionId=str(uuid.uuid4()))
            return peer, hello
        peer.close()
        raise AssertionError("Expected a v2 hello; legacy connections are unsupported")
    raise RuntimeError("No v2 hello received")


def run_smoke(binary, operation):
    resource = pathlib.Path(__file__).resolve().parents[1] / "src/main/resources/bridge/pi-launcher-bridge.ts"
    with tempfile.TemporaryDirectory(prefix="pi-bridge-tui-") as directory:
        root = pathlib.Path(directory)
        agent = root / "agent"
        agent.mkdir()
        (agent / "settings.json").write_text(json.dumps({"quietStartup": True}))
        extension = root / "bridge.ts"
        extension.write_text(resource.read_text().replace("@EXTENSION_VERSION@", "9"))
        evidence = root / "evidence.jsonl"
        guard = root / "guard.ts"
        guard.write_text('''import { appendFileSync } from "node:fs";
export default function(pi) {
  const record = data => appendFileSync(process.env.BRIDGE_SMOKE_EVIDENCE, JSON.stringify(data) + "\\n");
  pi.on("session_start", (event, ctx) => {
    if (event.reason === "startup") ctx.ui.setEditorText(process.env.BRIDGE_SMOKE_DRAFT);
    record({reason: event.reason, editor: ctx.ui.getEditorText()});
  });
  pi.on("session_tree", (_event, ctx) => record({editor: ctx.ui.getEditorText()}));
  pi.on("input", event => { record({unexpectedInput: event.text}); return {action:"handled"}; });
  pi.on("before_agent_start", (_event, ctx) => { record({modelRun: true}); ctx.abort(); });
}
''')
        source_id, copy_id = str(uuid.uuid4()), str(uuid.uuid4())
        session_id = copy_id if operation == "fork" else source_id
        session = root / "fixture.jsonl"
        entries = [{"type": "session", "version": 3, "id": source_id, "cwd": directory, "timestamp": "2026-01-01T00:00:00.000Z"}]
        parent = None
        for entry_id, role, text in [("u1", "user", "one"), ("a1", "assistant", "reply one"), ("u2", "user", "two"), ("a2", "assistant", "reply two")]:
            message = {"role": role, "content": [{"type": "text", "text": text}], "timestamp": 1}
            if role == "assistant":
                message.update(api="anthropic-messages", provider="anthropic", model="fixture", stopReason="stop",
                               usage={"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "totalTokens": 0,
                                      "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "total": 0}})
            entries.append({"id": entry_id, "parentId": parent, "type": "message", "message": message, "timestamp": "2026-01-01T00:00:00.000Z"})
            parent = entry_id
        session.write_text("".join(json.dumps(entry) + "\n" for entry in entries))
        source_bytes = session.read_bytes()
        draft = "" if operation == "fork" else "unsent TUI draft 🐈"
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(64)
            server.settimeout(20)
            master, slave = pty.openpty()
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
            env = {key: os.environ[key] for key in ["PATH", "LANG", "LD_LIBRARY_PATH"] if key in os.environ}
            env.update(HOME=directory, TERM="xterm-256color", PI_CODING_AGENT_DIR=str(agent),
                       PI_LAUNCHER_PORT=str(server.getsockname()[1]), PI_LAUNCHER_TOKEN="smoke-secret",
                       PI_LAUNCHER_TAB_KEY="smoke-tab", PI_LAUNCHER_JOURNAL_DIR=str(root / "journal"),
                       BRIDGE_SMOKE_EVIDENCE=str(evidence), BRIDGE_SMOKE_DRAFT=draft)
            selection = ["--fork", str(session), "--session-id", copy_id] if operation == "fork" else ["--session", str(session)]
            process = subprocess.Popen([binary, "--no-extensions", "-e", str(extension), "-e", str(guard),
                                        "--no-skills", "--no-prompt-templates", "--no-themes", "--no-mcp", *selection],
                                       cwd=directory, env=env, stdin=slave, stdout=slave, stderr=slave)
            os.close(slave)
            stop = threading.Event()

            def drain_terminal():
                tail = b""
                while not stop.is_set():
                    ready, _, _ = select.select([master], [], [], .1)
                    if ready:
                        try:
                            data = os.read(master, 65536)
                            if not data:
                                return
                            combined = tail + data
                            if b"\x1b[6n" in combined:
                                os.write(master, b"\x1b[1;1R")
                            tail = combined[-3:]
                        except OSError:
                            return

            drain = threading.Thread(target=drain_terminal, daemon=True)
            drain.start()
            peer = None
            try:
                peer, hello = accept_bridge(server)
                method = "conversation.forkAtTurn" if operation == "fork" else "conversation.rewind"
                assert hello["capabilities"].get(method) == [1], "Public command probe/capability failed"
                snapshot_id = str(uuid.uuid4())
                peer.send(type="request", id=snapshot_id, method="session.snapshot", methodVersion=1, params={}, context={})
                while True:
                    response = peer.receive()
                    if response.get("id") == snapshot_id:
                        snapshot = response["context"]["currentSnapshot"]
                        break
                operation_id = str(uuid.uuid4())
                params = {"targetEntryId": "u1", "turnLeafId": "a1"} if operation == "fork" else {"targetEntryId": "u2"}
                peer.send(type="request", id=operation_id, method=method, methodVersion=1, params=params,
                          context={"sessionId": session_id, "observedSnapshotId": snapshot["snapshotId"]},
                          startBefore=int(time.time() * 1000) + 10000)
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    try:
                        response = peer.receive()
                    except (EOFError, ConnectionResetError):
                        assert operation == "fork", "Unexpected rewind disconnection"
                        peer.close()
                        peer, replacement = accept_bridge(server)
                        assert replacement["runtimeId"] != hello["runtimeId"]
                        peer.send(type="query", id=operation_id)  # Never repeat the execution request.
                        continue
                    if response.get("id") == operation_id:
                        if response.get("status") in ["succeeded", "failed", "cancelled"]:
                            break
                        if operation == "fork":
                            peer.send(type="query", id=operation_id)
                else:
                    raise RuntimeError("No confirmed operation result")
                assert response["status"] == "succeeded", response.get("error")
                after = response["context"]["afterSnapshot"]
                after_entries = after["branch"]["entries"]
                assert [entry["id"] for entry in after_entries[:2]] == ["u1", "a1"]
                assert all(entry["type"] in ["session_info", "model_change", "thinking_level_change"] for entry in after_entries[2:])
                if operation == "fork":
                    assert after["session"]["id"] not in [source_id, copy_id]
                    assert session.read_bytes() == source_bytes, "Fork changed the source session"
                    peer.send(type="query", id=operation_id)
                    while True:
                        duplicate = peer.receive()
                        if duplicate.get("id") == operation_id:
                            assert duplicate["status"] == "succeeded"
                            break
                peer.send(type="ack", id=operation_id)
                observed = [json.loads(line) for line in evidence.read_text().splitlines()]
                assert observed and all(item.get("editor") == draft and "unexpectedInput" not in item and "modelRun" not in item for item in observed), observed
                if operation == "fork":
                    assert any(item.get("reason") == "fork" for item in observed), observed
                print(f"PASS: pi 1.5.0 TUI {operation}; confirmed branch, editor preserved, zero prompt/model events")
            finally:
                if peer:
                    peer.close()
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                stop.set()
                drain.join(timeout=1)
                os.close(master)


def main():
    binary = os.environ.get("PI_BINARY") or shutil.which("pi")
    if not binary:
        raise RuntimeError("Set PI_BINARY to the supported pi 1.5.0 CLI")
    for operation in ["rewind", "fork"]:
        run_smoke(binary, operation)


if __name__ == "__main__":
    main()
