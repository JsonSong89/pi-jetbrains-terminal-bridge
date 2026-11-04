#!/usr/bin/env python3
"""Opt-in real pi 1.5.0 TUI smoke, isolated from user sessions/config/credentials.
Run explicitly: PI_BINARY=/path/to/pi python3 tests/bridge-tui-smoke.py
This uses a PTY + the bridge socket, never RPC mode or a model request.
"""
import json
import fcntl
import struct
import termios
import os
import pathlib
import pty
import select
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import uuid


def main():
    binary = os.environ.get("PI_BINARY") or shutil.which("pi")
    if not binary:
        raise RuntimeError("Set PI_BINARY to the supported pi 1.5.0 CLI")
    resource = pathlib.Path(__file__).resolve().parents[1] / "src/main/resources/bridge/pi-launcher-bridge.ts"
    with tempfile.TemporaryDirectory(prefix="pi-bridge-tui-") as directory:
        root = pathlib.Path(directory)
        agent = root / "agent"
        agent.mkdir()
        (agent / "settings.json").write_text(json.dumps({"quietStartup": True}))
        extension = root / "bridge.ts"
        extension.write_text(resource.read_text().replace("@EXTENSION_VERSION@", "8"))
        evidence = root / "evidence.jsonl"
        guard = root / "guard.ts"
        guard.write_text('''import { appendFileSync } from "node:fs";
export default function(pi) {
  const record = data => appendFileSync(process.env.BRIDGE_SMOKE_EVIDENCE, JSON.stringify(data) + "\\n");
  pi.on("session_start", (_event, ctx) => ctx.ui.setEditorText("unsent TUI draft 🐈"));
  pi.on("session_tree", (_event, ctx) => record({editor: ctx.ui.getEditorText()}));
  pi.on("input", event => { record({unexpectedInput: event.text}); return {action:"handled"}; });
  pi.on("before_agent_start", (_event, ctx) => { record({modelRun: true}); ctx.abort(); });
}
''')
        session_id = str(uuid.uuid4())
        session = root / "fixture.jsonl"
        entries = [{"type": "session", "version": 3, "id": session_id, "cwd": directory, "timestamp": "2026-01-01T00:00:00.000Z"}]
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
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen()
            server.settimeout(20)
            master, slave = pty.openpty()
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
            env = {key: os.environ[key] for key in ["PATH", "LANG", "LD_LIBRARY_PATH"] if key in os.environ}
            env.update(HOME=directory, TERM="xterm-256color", PI_CODING_AGENT_DIR=str(agent),
                       PI_LAUNCHER_PORT=str(server.getsockname()[1]), PI_LAUNCHER_TOKEN="smoke-secret",
                       PI_LAUNCHER_TAB_KEY="smoke-tab", PI_LAUNCHER_JOURNAL_DIR=str(root / "journal"),
                       BRIDGE_SMOKE_EVIDENCE=str(evidence))
            process = subprocess.Popen([binary, "--no-extensions", "-e", str(extension), "-e", str(guard),
                                        "--no-skills", "--no-prompt-templates", "--no-themes", "--no-mcp", "--session", str(session)],
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
            try:
                deadline = time.monotonic() + 20
                while True:
                    if time.monotonic() > deadline:
                        raise RuntimeError("No v2 hello received")
                    client, _ = server.accept()
                    client.settimeout(5)
                    reader = client.makefile("rb")
                    first = reader.readline(32 * 1024 * 1024 + 1)
                    hello = json.loads(first)
                    if hello.get("v") == 2 and hello.get("type") == "hello":
                        break
                    reader.close()
                    client.close()  # compatibility v1 event, not the operation transport
                with client, reader:
                    client.settimeout(15)

                    def send(value):
                        client.sendall((json.dumps({"v": 2, **value}) + "\n").encode())

                    def receive():
                        while True:
                            line = reader.readline(32 * 1024 * 1024 + 1)
                            if not line:
                                raise RuntimeError("Bridge disconnected")
                            assert len(line) <= 32 * 1024 * 1024, "Oversized bridge frame"
                            value = json.loads(line)
                            if value["type"] == "ping":
                                send({"type": "pong"})
                            elif value["type"] != "pong":
                                return value

                    assert hello["type"] == "hello" and hello["piVersion"] == "1.5.0", hello.get("piVersion")
                    assert hello["capabilities"].get("conversation.rewind") == [1], "Public command probe failed"
                    send({"type": "welcome", "protocolVersion": 2, "connectionId": str(uuid.uuid4())})
                    query_id = str(uuid.uuid4())
                    send({"type": "request", "id": query_id, "method": "session.snapshot", "methodVersion": 1, "params": {}, "context": {}})
                    while True:
                        response = receive()
                        if response.get("id") == query_id:
                            snapshot = response["context"]["currentSnapshot"]
                            break
                    operation_id = str(uuid.uuid4())
                    send({"type": "request", "id": operation_id, "method": "conversation.rewind", "methodVersion": 1,
                          "params": {"targetEntryId": "u2"}, "context": {"sessionId": session_id, "observedSnapshotId": snapshot["snapshotId"]},
                          "startBefore": int(time.time() * 1000) + 10000})
                    while True:
                        response = receive()
                        if response.get("id") == operation_id and response.get("status") in ["succeeded", "failed", "cancelled"]:
                            break
                    assert response["status"] == "succeeded", response.get("error")
                    assert response["context"]["afterSnapshot"]["branch"]["leafId"] == "a1"
                    send({"type": "ack", "id": operation_id})
                observed = [json.loads(line) for line in evidence.read_text().splitlines()]
                assert observed and all(item == {"editor": "unsent TUI draft 🐈"} for item in observed), observed
                print("PASS: pi 1.5.0 TUI probe + confirmed rewind; editor preserved, zero prompt/model events")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                stop.set()
                drain.join(timeout=1)
                os.close(master)


if __name__ == "__main__":
    main()
