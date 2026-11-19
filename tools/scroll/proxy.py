#!/usr/bin/env python3
"""A link that is slower than a local socket, and a record of what crossed it.

Stands between Tono and a Rex server, to make a server on another machine reproducible
(backlog-054). Every chunk of bytes is held for a delay before it is passed on, each way,
in order. With --log, every read and write of the client's socket is written down with
the machine's monotonic clock, which is the clock Tono's scroll telemetry says it started
from (`mono_us` in its `start` line), so the two can be laid on one time line: when bytes
really left Tono and reached it, beside when Tono meant to send them and when it used them.

    proxy.py --listen 127.0.0.1:7801 --to unix:/tmp/me/rex.sock --delay 30 --jitter 10 --log link.jsonl
    proxy.py --listen 127.0.0.1:7802 --to unix:/tmp/me/rex.sock --tls-cert cert.pem --tls-key key.pem
    proxy.py --listen 127.0.0.1:7803 --to work.example.ts.net:443 --tls-upstream     # record a real link

Lines of the log, one JSON object each, `mono_us` being the clock:
  open, close   a connection (`conn`, and `path`, which tells the control connection from a terminal's stream)
  c_read        bytes read from the client: they left it just before  (`bytes`)
  s_write       the same bytes passed on to the server, after the delay
  s_read        bytes read from the server
  c_write       the same bytes written to the client, after the delay: they reach it just after
  ws            a WebSocket frame whose last byte was just read (`dir` "c2s" or "s2c", `op`, `len`, and `chunk`,
                the read it ended in: reads and the writes that pass them on are numbered alike)

No root is needed, and nothing but Python's own library.
"""
import argparse
import asyncio
import json
import random
import socket
import ssl
import time


def now_us():
    return time.monotonic_ns() // 1000


class Log:
    def __init__(self, path):
        self.file = open(path, "a", buffering=1 << 16) if path else None
        self.last_flush = 0.0

    def write(self, event, **fields):
        if not self.file:
            return
        fields = {"mono_us": now_us(), "ev": event, **fields}
        self.file.write(json.dumps(fields, separators=(",", ":")) + "\n")
        if time.monotonic() - self.last_flush > 0.2:
            self.file.flush()
            self.last_flush = time.monotonic()


class Frames:
    """Finds WebSocket frames in one direction of a connection, after the HTTP that opens it.

    A frame is written down when its last byte has been read, with the number of the chunk
    that byte came in, which is the chunk whose passing on delivers it.
    """

    def __init__(self, log, conn, direction):
        self.log, self.conn, self.direction = log, conn, direction
        self.buffer = bytearray()
        self.upgraded = False
        self.left = 0  # bytes of the current frame's payload still to come
        self.current = None
        self.first_line = None

    def feed(self, data, chunk):
        if not self.log.file:
            return
        if self.upgraded and self.current and self.left > len(data) and not self.buffer:
            self.left -= len(data)
            return
        self.buffer += data
        if not self.upgraded:
            end = self.buffer.find(b"\r\n\r\n")
            if end < 0:
                return
            self.first_line = bytes(self.buffer[: self.buffer.find(b"\r\n")]).decode("latin1")
            del self.buffer[: end + 4]
            self.upgraded = True
        while True:
            if self.current:
                drop = min(self.left, len(self.buffer))
                del self.buffer[:drop]
                self.left -= drop
                if self.left:
                    return
                op, length = self.current
                self.current = None
                self.log.write("ws", conn=self.conn, dir=self.direction, op=op, len=length, chunk=chunk)
            if len(self.buffer) < 2:
                return
            op = self.buffer[0] & 0x0F
            masked = self.buffer[1] & 0x80
            length = self.buffer[1] & 0x7F
            header = 2
            if length == 126:
                if len(self.buffer) < 4:
                    return
                length = int.from_bytes(self.buffer[2:4], "big")
                header = 4
            elif length == 127:
                if len(self.buffer) < 10:
                    return
                length = int.from_bytes(self.buffer[2:10], "big")
                header = 10
            if masked:
                header += 4
            if len(self.buffer) < header:
                return
            del self.buffer[:header]
            self.current = (op, length)
            self.left = length


async def pump(reader, writer, delay, jitter, log, conn, read_event, write_event, frames):
    """Passes bytes on in order, each chunk no sooner than its delay after it was read."""
    queue = asyncio.Queue()

    async def read():
        last_due = 0.0
        chunk = 0
        try:
            while True:
                data = await reader.read(1 << 16)
                if not data:
                    break
                chunk += 1
                log.write(read_event, conn=conn, bytes=len(data), chunk=chunk)
                if frames:
                    frames.feed(data, chunk)
                due = time.monotonic() + max(0.0, delay + random.uniform(-jitter, jitter)) if delay or jitter else 0.0
                last_due = max(last_due, due)  # never overtakes the chunk before
                queue.put_nowait((last_due, data, chunk))
        except (ConnectionError, ssl.SSLError, OSError):
            pass
        queue.put_nowait((0.0, None, 0))

    async def write():
        try:
            while True:
                due, data, chunk = await queue.get()
                if data is None:
                    break
                wait = due - time.monotonic()
                if wait > 0:
                    await asyncio.sleep(wait)
                writer.write(data)
                log.write(write_event, conn=conn, bytes=len(data), chunk=chunk)
                await writer.drain()
        except (ConnectionError, ssl.SSLError, OSError):
            pass
        try:
            writer.close()
        except Exception:
            pass

    reading = asyncio.create_task(read())
    await write()
    reading.cancel()


def no_delay(writer):
    sock = writer.get_extra_info("socket")
    if sock is not None and sock.family in (socket.AF_INET, socket.AF_INET6):
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)


async def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--listen", required=True, help="HOST:PORT to take Tono's connections on")
    parser.add_argument("--to", required=True, help="the Rex server: unix:/path/to/rex.sock or HOST:PORT")
    parser.add_argument("--delay", type=float, default=0.0, help="milliseconds each chunk is held, each way")
    parser.add_argument("--jitter", type=float, default=0.0, help="milliseconds more or less than that, at random")
    parser.add_argument("--tls-cert", help="serve TLS to Tono with this certificate (and --tls-key)")
    parser.add_argument("--tls-key")
    parser.add_argument("--tls-upstream", action="store_true", help="speak TLS to the server (an https host)")
    parser.add_argument("--host-header", help="replace the Host header of each request (with --tls-upstream, the server's name)")
    parser.add_argument("--log", help="write what crosses to this file, as JSON lines")
    args = parser.parse_args()
    host, _, port = args.listen.rpartition(":")
    delay, jitter = args.delay / 1000.0, args.jitter / 1000.0
    log = Log(args.log)
    log.write("start", delay_ms=args.delay, jitter_ms=args.jitter, tls=bool(args.tls_cert), to=args.to, wall_ms=int(time.time() * 1000))
    count = 0

    async def serve(reader, writer):
        nonlocal count
        count += 1
        conn = count
        no_delay(writer)
        try:
            if args.to.startswith("unix:"):
                path = args.to[5:]
                up_reader, up_writer = await asyncio.open_unix_connection(path[2:] if path.startswith("///") else path)
            else:
                up_host, _, up_port = args.to.rpartition(":")
                context = ssl.create_default_context() if args.tls_upstream else None
                up_reader, up_writer = await asyncio.open_connection(up_host, int(up_port), ssl=context, server_hostname=up_host if context else None)
                no_delay(up_writer)
        except Exception as failure:
            log.write("refused", conn=conn, why=str(failure))
            writer.close()
            return
        up = Frames(log, conn, "c2s")
        down = Frames(log, conn, "s2c")
        log.write("open", conn=conn)

        # The request's first line says which connection this is; it is logged when it is seen.
        original_feed = up.feed

        def feed(data, chunk, named=[False]):
            original_feed(data, chunk)
            if not named[0] and up.first_line:
                named[0] = True
                log.write("path", conn=conn, path=up.first_line.split(" ")[1] if " " in up.first_line else up.first_line)

        up.feed = feed

        if args.host_header:
            # The opening request is small and arrives whole; its Host line is rewritten before anything is passed on.
            head = await reader.readuntil(b"\r\n\r\n")
            lines = head.split(b"\r\n")
            lines = [b"Host: " + args.host_header.encode() if line.lower().startswith(b"host:") else line for line in lines]
            head = b"\r\n".join(lines)
            up.feed(head, 0)
            up_writer.write(head)
        await asyncio.gather(
            pump(reader, up_writer, delay, jitter, log, conn, "c_read", "s_write", up),
            pump(up_reader, writer, delay, jitter, log, conn, "s_read", "c_write", down),
        )
        log.write("close", conn=conn)

    context = None
    if args.tls_cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.tls_cert, args.tls_key)
    server = await asyncio.start_server(serve, host or "127.0.0.1", int(port), ssl=context)
    print(f"listening on {args.listen}, to {args.to}, {args.delay} ms ± {args.jitter} each way" + (", TLS" if context else ""), flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
