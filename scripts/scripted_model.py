#!/usr/bin/env python3
"""A scripted OpenAI-compatible chat endpoint: stands in for the language model, and only for it.

Used to drive the real app, the real omp and the real tools on a device without an API key: the model's
answers come from a script, everything the agent does with them is real. Serves POST /v1/chat/completions
(streaming) on 0.0.0.0, so an emulator reaches it at http://10.0.2.2:<port>/v1.

The script is followed by what the conversation has already seen, not by a counter: the step to play is
the number of tool results in the request. A step may also be a function of the request (see last_tool_text),
for answers that depend on what a tool returned. A request that offers no tools (omp's own housekeeping, such
as naming a session) gets a short plain answer and does not use up a step.

    from scripted_model import ScriptedModel, tool, say, last_tool_text
    model = ScriptedModel([
        tool("bash", {"command": "echo hi"}),
        say("done"),
    ]).start()                       # model.port, model.url_for_emulator
"""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def tool(name, arguments, call_id=None):
    return {"kind": "tool", "name": name, "arguments": arguments, "id": call_id}


def say(text):
    return {"kind": "text", "text": text}


def _chunk(delta, finish=None):
    return {
        "id": "chatcmpl-scripted", "object": "chat.completion.chunk", "created": 0, "model": "scripted-model",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


def _sse(events):
    return "".join("data: %s\n\n" % (e if isinstance(e, str) else json.dumps(e)) for e in events) + "data: [DONE]\n\n"


def tool_results_seen(request):
    return sum(1 for m in request.get("messages", []) if m.get("role") == "tool")


def last_tool_text(request):
    """The text of the most recent tool result in the conversation, or None before the first."""
    for message in reversed(request.get("messages", [])):
        if message.get("role") == "tool":
            content = message.get("content")
            if isinstance(content, list):
                return "".join(part.get("text", "") for part in content)
            return content or ""
    return None


class ScriptedModel:
    def __init__(self, script, port=0):
        self.script = list(script)
        self.requests = []          # every request received
        self.agent_requests = []    # the ones that offered tools: the agent's own turns
        self.lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                if not self.path.endswith("/chat/completions"):
                    self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
                request = json.loads(body or b"{}")
                with outer.lock:
                    outer.requests.append(request)
                    agent_turn = bool(request.get("tools"))
                    if agent_turn:
                        outer.agent_requests.append(request)
                if not agent_turn:
                    step = say("Scripted task")
                else:
                    index = tool_results_seen(request)
                    step = outer.script[index] if index < len(outer.script) else say("(the script has no more steps)")
                    if callable(step):
                        step = step(request)
                if step["kind"] == "tool":
                    arguments = step["arguments"] if isinstance(step["arguments"], str) else json.dumps(step["arguments"])
                    call_id = step.get("id") or "call_%d" % (tool_results_seen(request) + 1)
                    events = [
                        _chunk({"role": "assistant", "content": None, "tool_calls": [{"index": 0, "id": call_id, "type": "function", "function": {"name": step["name"], "arguments": ""}}]}),
                        _chunk({"tool_calls": [{"index": 0, "function": {"arguments": arguments}}]}),
                        _chunk({}, "tool_calls"),
                    ]
                else:
                    events = [_chunk({"role": "assistant", "content": step["text"]}), _chunk({}, "stop")]
                events.append({"id": "chatcmpl-scripted", "object": "chat.completion.chunk", "created": 0, "model": "scripted-model", "choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}})
                payload = _sse(events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self):
                self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()

        self.server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
        self.port = self.server.server_address[1]

    @property
    def url_for_emulator(self):
        return "http://10.0.2.2:%d/v1" % self.port

    def start(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def stop(self):
        self.server.shutdown()
