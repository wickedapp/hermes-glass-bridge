import asyncio
import json
import websockets

async def main():
    uri = 'ws://127.0.0.1:8765/ws/glass'
    async with websockets.connect(uri) as ws:
        print(await ws.recv())
        await ws.send(json.dumps({'type':'shell_command','project':'smoke','command':'pwd && printf "terminal-ok"'}))
        while True:
            msg = json.loads(await ws.recv())
            print(msg)
            if msg.get('type') == 'term_done':
                break
        await ws.send(json.dumps({'type':'agent_prompt','project':'smoke','text':'Reply exactly: glass-agent-ok'}))
        done = False
        while not done:
            msg = json.loads(await ws.recv())
            print(msg)
            done = msg.get('type') in ('agent_done','agent_error')

asyncio.run(main())
