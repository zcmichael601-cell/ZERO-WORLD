import { test } from "node:test";
import assert from "node:assert/strict";
import { responseError } from "../../main/resources/static/chat-errors.js";

test("shows HTTP status and JSON error details", async () => {
  const error = await responseError(new Response(JSON.stringify({ message: "模型不存在", code: "model_not_found" }), { status: 404 }));
  assert.match(error.message, /HTTP 404/);
  assert.match(error.message, /模型不存在/);
  assert.match(error.message, /model_not_found/);
});

test("preserves plain text responses and redacts common credentials", async () => {
  const error = await responseError(new Response('Authorization: Bearer test-token; api-key=private-key; {"password":"private-password"}', { status: 401 }));
  assert.match(error.message, /HTTP 401/);
  assert.match(error.message, /REDACTED/);
  assert.doesNotMatch(error.message, /test-token|private-key|private-password/);
});

test("handles empty or unreadable error responses", async () => {
  assert.match((await responseError(new Response("", { status: 500 }))).message, /未返回错误详情/);
  assert.match((await responseError({ status: 502, text: async () => { throw new Error("network"); } })).message, /无法读取错误响应/);
});

test("caps long responses", async () => {
  const error = await responseError(new Response("x".repeat(10000), { status: 500 }));
  assert.ok(error.message.length < 8200);
  assert.match(error.message, /详情过长/);
});
