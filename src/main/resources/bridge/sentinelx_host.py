#!/usr/bin/env python3
"""
SentinelX native-messaging host.

A dumb, stateless relay between a browser extension and the running SentinelX
desktop app. The browser speaks Chrome/Firefox native messaging on stdin/stdout
(4-byte little-endian length prefix + UTF-8 JSON). The app speaks newline-
delimited JSON over a user-private Unix domain socket. This script owns no
secrets and makes no decisions — it just forwards frames in both directions, so
the security review surface is the app and the extension, not this pipe.

If the socket is absent (app closed or bridge off), the host answers the
extension's own messages with a `{"type":"error","reason":"unavailable"}` so the
page can fail quietly instead of hanging.
"""

import json
import os
import socket
import struct
import sys
import threading

# SENTINELX_BRIDGE_SOCKET lets the app (and the test harness) point the host at
# a specific socket; otherwise it derives the same default path the app uses.
SOCKET_PATH = os.environ.get("SENTINELX_BRIDGE_SOCKET") or os.path.join(
    os.environ.get("XDG_RUNTIME_DIR")
    or os.path.join("/tmp", "sentinelx-" + os.environ.get("USER", "user")),
    "sentinelx",
    "bridge.sock",
)


def read_native_message():
    """Read one framed message from the browser on stdin. None at EOF."""
    raw_len = sys.stdin.buffer.read(4)
    if len(raw_len) < 4:
        return None
    (length,) = struct.unpack("<I", raw_len)
    if length == 0 or length > (1 << 20):  # 1 MB guard
        return None
    data = sys.stdin.buffer.read(length)
    if len(data) < length:
        return None
    return data.decode("utf-8")


def write_native_message(text):
    """Frame and write one message back to the browser on stdout."""
    data = text.encode("utf-8")
    sys.stdout.buffer.write(struct.pack("<I", len(data)))
    sys.stdout.buffer.write(data)
    sys.stdout.buffer.flush()


def reply_unavailable(req_id):
    write_native_message(json.dumps({"type": "error", "reqId": req_id, "reason": "unavailable"}))


def pump_socket_to_browser(sock, stop):
    """Forward newline-delimited JSON from the app to the browser."""
    buf = b""
    while not stop.is_set():
        try:
            chunk = sock.recv(65536)
        except OSError:
            break
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            if line.strip():
                try:
                    write_native_message(line.decode("utf-8"))
                except Exception:
                    stop.set()
                    return
    stop.set()


def main():
    try:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.connect(SOCKET_PATH)
    except OSError:
        # App not running or bridge disabled: answer each request as unavailable
        # until the browser closes the port.
        while True:
            msg = read_native_message()
            if msg is None:
                return
            try:
                req_id = json.loads(msg).get("reqId", "")
            except Exception:
                req_id = ""
            reply_unavailable(req_id)
        return

    stop = threading.Event()
    reader = threading.Thread(target=pump_socket_to_browser, args=(sock, stop), daemon=True)
    reader.start()

    try:
        while not stop.is_set():
            msg = read_native_message()
            if msg is None:
                break
            try:
                sock.sendall(msg.encode("utf-8") + b"\n")
            except OSError:
                break
    finally:
        stop.set()
        try:
            sock.close()
        except OSError:
            pass


if __name__ == "__main__":
    main()
