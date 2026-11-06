import { test } from "node:test";
import assert from "node:assert/strict";
import { readEvents } from "../../main/resources/static/chat-stream.js";

function responseFor(text, chunkSize = 1) {
  const bytes = new TextEncoder().encode(text);
  return new Response(new ReadableStream({
    start(controller) {
      for (let offset = 0; offset < bytes.length; offset += chunkSize) {
        controller.enqueue(bytes.slice(offset, offset + chunkSize));
      }
      controller.close();
    }
  }));
}

test("decodes Chinese and emoji across UTF-8 byte boundaries", async () => {
  const events = [];
  await readEvents(responseFor("event:delta\ndata:你好 🌟\n\nevent:done\ndata:[DONE]\n\n"), (event, data) => {
    events.push([event, data]);
    return event === "done";
  });
  assert.deepEqual(events, [["delta", "你好 🌟"], ["done", "[DONE]"]]);
});

test("preserves multiline content, indentation, CRLF and ignores comments", async () => {
  const events = [];
  await readEvents(responseFor(":heartbeat\r\n\r\nevent:delta\r\ndata: first\r\ndata:   second\r\ndata:\r\n\r\nevent:done\r\ndata:[DONE]\r\n\r\n"), (event, data) => {
    events.push([event, data]);
    return event === "done";
  });
  assert.equal(events[0][1], "first\n  second\n");
  assert.equal(events.length, 2);
});

test("reads multiple events within one network chunk", async () => {
  let content = "";
  await readEvents(responseFor("event:delta\ndata:a\n\nevent:delta\ndata:b\n\nevent:done\ndata:[DONE]\n\n", 4096), (event, data) => {
    if (event === "delta") content += data;
    return event === "done";
  });
  assert.equal(content, "ab");
});

test("reports truncated connections instead of claiming completion", async () => {
  await assert.rejects(readEvents(responseFor("event:delta\ndata:partial\n\n"), () => false), /连接已中断/);
});

test("propagates server errors and releases the response stream", async () => {
  const response = responseFor("event:error\ndata:服务不可用\n\n");
  await assert.rejects(readEvents(response, (event, data) => { throw new Error(data); }), /服务不可用/);
  assert.equal(response.body.locked, false);
});

test("handles absent response bodies", async () => {
  await assert.rejects(readEvents({ body: null }, () => false), /不支持流式响应/);
});

test("JSON payload preserves standalone spaces and code indentation", async () => {
  const chunks = ["你好", " ", "🌟", "\n", "    ", "return true;"];
  const frames = chunks.map(content => `event:delta\ndata:${JSON.stringify({ content })}\n\n`).join("") + 'event:done\ndata:{"content":"[DONE]"}\n\n';
  let content = "";
  await readEvents(responseFor(frames), (event, data) => {
    if (event === "delta") content += JSON.parse(data).content;
    return event === "done";
  });
  assert.equal(content, chunks.join(""));
});
