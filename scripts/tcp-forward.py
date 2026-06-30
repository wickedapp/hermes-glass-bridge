#!/usr/bin/env python3
"""Tiny async TCP forwarder for ADB reverse use.

Example:
  python3 scripts/tcp-forward.py 127.0.0.1 60973 100.76.207.14 60973
"""
from __future__ import annotations
import asyncio
import sys

async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

async def handle(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter, target_host: str, target_port: int) -> None:
    try:
        remote_reader, remote_writer = await asyncio.open_connection(target_host, target_port)
    except Exception as exc:
        client_writer.write(f"HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n\r\nforward error: {exc}\n".encode())
        await client_writer.drain()
        client_writer.close()
        return
    await asyncio.gather(pipe(client_reader, remote_writer), pipe(remote_reader, client_writer))

async def main() -> None:
    if len(sys.argv) != 5:
        print("usage: tcp-forward.py <listen_host> <listen_port> <target_host> <target_port>", file=sys.stderr)
        raise SystemExit(2)
    listen_host, listen_port_s, target_host, target_port_s = sys.argv[1:]
    listen_port, target_port = int(listen_port_s), int(target_port_s)
    server = await asyncio.start_server(lambda r,w: handle(r,w,target_host,target_port), listen_host, listen_port)
    addrs = ", ".join(str(s.getsockname()) for s in server.sockets or [])
    print(f"forwarding {addrs} -> {target_host}:{target_port}", flush=True)
    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    asyncio.run(main())
