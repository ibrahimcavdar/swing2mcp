"""Drives the swing2mcp MCP server by hand, the way an AI client would (Python 3.7+, no dependencies).

Tool calls are read as JSON lines from stdin:
    {"tool": "list_apps"}
    {"tool": "connect_app", "args": {"pid": 1234}}
    {"tool": "type_text", "args": {"ref": "$ref:textfield \\"Full Name\\"", "text": "Ali"}, "grep": "Typed"}

"$ref:<regex>" in a string argument becomes the ref of the first line of the previous result that
matches the regex. "grep" filters the printed output. Example (Windows cmd):

    python scripts\\mcp-steps.py --java "C:\\Program Files\\Java\\jdk-17\\bin\\java.exe" < steps.jsonl

By default the server is mcp/target/swing2mcp.jar of this repository, started with "java" from PATH.
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import time

here = os.path.dirname(os.path.abspath(__file__))
parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--java", default="java", help="java executable of a JDK 17+")
parser.add_argument("--jar", default=os.path.join(here, "..", "mcp", "target", "swing2mcp.jar"), help="MCP server jar")
options = parser.parse_args()

# Windows consoles are not UTF-8 by default; snapshots contain Turkish text.
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
proc = subprocess.Popen([options.java, "-jar", options.jar], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, encoding="utf-8")
next_id = 0


def rpc(method, params=None, notify=False):
    global next_id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        next_id += 1
        msg["id"] = next_id
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()
    if notify:
        return None
    while True:
        line = proc.stdout.readline()
        if not line:
            raise SystemExit("MCP server stopped:\n" + proc.stderr.read())
        reply = json.loads(line)
        if reply.get("id") == next_id:
            return reply


rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "mcp-steps", "version": "0"}})
rpc("notifications/initialized", notify=True)
last = ""
for raw in sys.stdin:
    if not raw.strip():
        continue
    step = json.loads(raw)
    args = step.get("args", {})
    for key, value in list(args.items()):
        if isinstance(value, str) and value.startswith("$ref:"):
            match = re.search(value[5:] + r'.*?\[ref=(e\d+)\]', last)
            if not match:
                raise SystemExit(f"no ref matching {value[5:]!r} in the previous result")
            args[key] = match.group(1)
    started = time.time()
    result = rpc("tools/call", {"name": step["tool"], "arguments": args})["result"]
    last = "\n".join(c["text"] for c in result["content"] if c["type"] == "text")
    images = sum(1 for c in result["content"] if c["type"] == "image")
    flag = " (ERROR)" if result.get("isError") else ""
    print(f"\n=== {step['tool']} {json.dumps(args, ensure_ascii=False)} [{int((time.time() - started) * 1000)} ms]{flag}")
    grep = step.get("grep")
    print("\n".join(l for l in last.splitlines() if re.search(grep, l)) if grep else last)
    if images:
        print(f"({images} image(s) returned)")
proc.stdin.close()
proc.wait(timeout=10)
