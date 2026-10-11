#!/usr/bin/env python3
"""Spricht mit engine/llm/llm-runner über dessen stdin/stdout-Protokoll.

Zum Ausprobieren von Enhance-Prompts auf dem Mac, ohne App:

    python3 tools/test-llm.py models/llm/gemma-3-1b-it-Q4_K_M.gguf prompts.json

prompts.json: [{"system": "...", "user": "..."}, ...]
Ohne zweite Datei läuft ein eingebauter Satz deutscher Diktate durch.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNNER = ROOT / "engine/llm/build-macos/llm-runner"


class Runner:
    def __init__(self):
        self.p = subprocess.Popen([str(RUNNER)], stdin=subprocess.PIPE, stdout=subprocess.PIPE)

    def _send(self, header: str, *payloads: bytes):
        self.p.stdin.write(header.encode() + b"\n" + b"".join(payloads))
        self.p.stdin.flush()

    def _reply(self):
        while True:
            line = self.p.stdout.readline().decode().strip()
            kind, n = line.split(" ", 1)
            if kind == "PRG":
                continue
            data = self.p.stdout.read(int(n)).decode()
            if kind == "ERR":
                raise RuntimeError(data)
            return data

    def load(self, path, n_ctx=4096, threads=0):
        b = str(path).encode()
        self._send(f"LOAD {n_ctx} {threads} {len(b)}", b)
        return self._reply()

    def gen(self, system, user, max_tokens=512):
        s, u = system.encode(), user.encode()
        self._send(f"GEN {max_tokens} {len(s)} {len(u)}", s, u)
        return self._reply()


def main():
    model = sys.argv[1]
    r = Runner()
    t = time.time()
    print("Modell:", r.load(model), f"({time.time() - t:.1f} s)")
    cases = json.loads(Path(sys.argv[2]).read_text()) if len(sys.argv) > 2 else []
    for c in cases:
        t = time.time()
        out = r.gen(c["system"], c["user"])
        print(f"\n--- {c.get('name', '')} ({time.time() - t:.1f} s)\n{c['user']}\n=>\n{out}")


if __name__ == "__main__":
    main()
