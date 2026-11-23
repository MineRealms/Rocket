"""Loopback TCP delay fixture for the disposable Forge server; preserves byte order."""
import argparse
import asyncio
import random
import time


async def forward(reader, writer, delay, jitter, generator):
    queue = asyncio.Queue(maxsize=128)

    async def receive():
        previous = 0.0
        while data := await reader.read(65536):
            deadline = max(previous, time.monotonic() + max(0, delay + generator.uniform(-jitter, jitter)))
            previous = deadline
            await queue.put((deadline, data))
        await queue.put((0, None))

    async def transmit():
        while True:
            deadline, data = await queue.get()
            if data is None:
                if writer.can_write_eof():
                    writer.write_eof()
                return
            await asyncio.sleep(max(0, deadline-time.monotonic()))
            writer.write(data)
            await writer.drain()

    tasks = [asyncio.create_task(receive()), asyncio.create_task(transmit())]
    try:
        await asyncio.gather(*tasks)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


async def main(args):
    connection_number = 0

    async def connected(reader, writer):
        nonlocal connection_number
        connection_number += 1
        number = connection_number
        print(f"connection {number}: open", flush=True)
        target_writer = None
        try:
            target_reader, target_writer = await asyncio.open_connection("127.0.0.1", args.target_port)
            tasks = [asyncio.create_task(forward(reader, target_writer, args.delay_ms/1000, args.jitter_ms/1000, random.Random(number))),
                     asyncio.create_task(forward(target_reader, writer, args.delay_ms/1000, args.jitter_ms/1000, random.Random(number+1000)))]
            try:
                done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        except Exception as error:
            print(f"connection {number}: {error}", flush=True)
        finally:
            writer.close()
            if target_writer:
                target_writer.close()
            print(f"connection {number}: closed", flush=True)

    server = await asyncio.start_server(connected, "127.0.0.1", args.listen_port)
    print(f"127.0.0.1:{args.listen_port} -> 127.0.0.1:{args.target_port}; "
          f"each direction {args.delay_ms}+/-{args.jitter_ms} ms; duration {args.duration}s", flush=True)
    async with server:
        await asyncio.sleep(args.duration)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen-port", type=int, default=25573)
    parser.add_argument("--target-port", type=int, default=25572)
    parser.add_argument("--delay-ms", type=float, default=75)
    parser.add_argument("--jitter-ms", type=float, default=25)
    parser.add_argument("--duration", type=int, default=600)
    asyncio.run(main(parser.parse_args()))
