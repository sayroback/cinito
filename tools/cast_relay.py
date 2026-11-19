#!/usr/bin/env python3
"""Lightweight bidirectional TCP relay for bridging an Android Emulator CastMediaServer to the host LAN."""

import asyncio
import sys

LISTEN_HOST = sys.argv[1] if len(sys.argv) > 1 else "0.0.0.0"
LISTEN_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
TARGET_HOST = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"
TARGET_PORT = int(sys.argv[4]) if len(sys.argv) > 4 else LISTEN_PORT


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def handle(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
    try:
        target_reader, target_writer = await asyncio.open_connection(TARGET_HOST, TARGET_PORT)
    except Exception:
        client_writer.close()
        return
    await asyncio.gather(
        pipe(client_reader, target_writer),
        pipe(target_reader, client_writer),
    )


async def main() -> None:
    server = await asyncio.start_server(handle, LISTEN_HOST, LISTEN_PORT, reuse_address=True)
    print(f"[cast_relay] {LISTEN_HOST}:{LISTEN_PORT} <-> {TARGET_HOST}:{TARGET_PORT}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
