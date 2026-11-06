package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.application.skill.SkillEngine;
import com.jd.diviner.ai.rec.odin.demo.app.bootstrap.SkillModelConfiguration;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class SkillModelProtocolTests {

    @org.junit.jupiter.api.Test
    void fourthRoundGatewayMarkupFallsBackToJsonWithoutNativeToolHistoryOnTheWire() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(SkillEngineTests.MAPPER.readTree(exchange.getRequestBody()));
            int round = requests.size();
            ObjectNode message = SkillEngineTests.MAPPER.createObjectNode().put("role", "assistant");
            String finish = "stop";
            if (round <= 3) {
                String name = List.of("Skill", "get_user_profile", "get_user_behaviors").get(round - 1);
                String arguments = round == 1 ? "{\"command\":\"shopping-recommendation\"}" : "{\"username\":\"xiaoming\"}";
                message.set("tool_calls", SkillEngineTests.MAPPER.valueToTree(List.of(Map.of("id", "native-" + round, "type", "function",
                        "function", Map.of("name", name, "arguments", arguments)))));
                finish = "tool_calls";
            } else if (round == 4) {
                message.put("content", "<tool_call><function=search_products>untrusted arguments</function></tool_call>");
            } else if (round == 5) {
                message.put("content", "{\"name\":\"search_products\",\"arguments\":{\"interests\":[\"跑步\"],\"budget\":800,\"limit\":8}}");
            } else if (round == 6) {
                message.put("content", "{\"name\":\"retrieve_product_knowledge\",\"arguments\":{\"productIds\":[\"SKU-001\"],\"question\":\"适用场景\"}}");
            } else {
                message.put("content", SkillEngineTests.FINAL);
            }
            byte[] bytes = SkillEngineTests.MAPPER.writeValueAsBytes(Map.of("id", "response-" + round,
                    "object", "chat.completion", "created", 1, "model", "configured-model",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var run = engine(server.getAddress().getPort(), false).execute("shopping-recommendation", SkillProgressTests.input());
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals(7, requests.size());
            assertEquals(5, run.traces().size());
            for (JsonNode request : requests.subList(4, 6)) {
                assertTrue(request.path("tools").isMissingNode() || request.path("tools").isEmpty());
                assertFalse(request.path("stream").asBoolean());
                assertEquals(2, request.path("messages").size());
                assertEquals("system", request.path("messages").get(0).path("role").asText());
                assertEquals("user", request.path("messages").get(1).path("role").asText());
                assertFalse(request.toString().contains("untrusted arguments"));
            }
            assertEquals("none", requests.get(6).path("tool_choice").asText());
            assertEquals(2, requests.get(6).path("tools").size());
            assertFalse(requests.get(6).path("messages").toString().contains("decision_"));
            assertTrue(requests.get(6).path("messages").toString().contains("FINAL_ANSWER"));
            assertTrue(requests.get(6).path("messages").toString().contains("SKU-001"));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void sendsConfiguredCredentialsAndModelAndRoundTripsNativeToolMessages(boolean streaming, boolean streamToolCalls) throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authorizations = new CopyOnWriteArrayList<>();
        List<SkillEngine.ContentDelta> deltas = new CopyOnWriteArrayList<>();
        CountDownLatch firstContent = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = SkillEngineTests.MAPPER.readTree(exchange.getRequestBody());
            requests.add(request);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int round = requests.size();
            String[] names = {"Skill", "get_user_profile", "get_user_behaviors", "search_products", "retrieve_product_knowledge"};
            String[] arguments = {"{\"command\":\"shopping-recommendation\"}", "{\"username\":\"xiaoming\"}",
                    "{\"username\":\"xiaoming\"}", "{\"interests\":[\"跑步\"],\"budget\":800,\"limit\":8}",
                    "{\"productIds\":[\"SKU-001\"],\"question\":\"慢跑场景优点与限制\"}"};
            if (request.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                if (round <= 5) {
                    writeChunk(exchange, round, Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                            "id", "wire-call-" + round, "type", "function", "function",
                            Map.of("name", names[round - 1], "arguments", arguments[round - 1].substring(0, 10))))), null);
                    writeChunk(exchange, round, Map.of("tool_calls", List.of(Map.of("index", 0, "function",
                            Map.of("arguments", arguments[round - 1].substring(10))))), null);
                } else {
                    writeChunk(exchange, round, Map.of("role", "assistant", "reasoning_content", "private reasoning"), null);
                    writeChunk(exchange, round, Map.of("content", SkillEngineTests.FINAL.substring(0, 10)), null);
                    writeChunk(exchange, round, Map.of("content", SkillEngineTests.FINAL.substring(10, 20)), null);
                    try {
                        if (!releaseFinal.await(10, TimeUnit.SECONDS)) throw new IOException("Final response was not released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                    writeChunk(exchange, round, Map.of("content", SkillEngineTests.FINAL.substring(20)), null);
                }
                writeChunk(exchange, round, Map.of(), round <= 5 ? "tool_calls" : "stop");
                writeEvent(exchange, Map.of("id", "wire-response-" + round, "object", "chat.completion.chunk",
                        "created", 1, "model", "configured-model", "choices", List.of(),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.close();
                return;
            }
            ObjectNode message = SkillEngineTests.MAPPER.createObjectNode().put("role", "assistant");
            if (round <= 5) {
                message.putNull("content");
                message.set("tool_calls", SkillEngineTests.MAPPER.valueToTree(List.of(Map.of("id", "wire-call-" + round,
                        "type", "function", "function", Map.of("name", names[round - 1], "arguments", arguments[round - 1])))));
            } else {
                message.put("content", SkillEngineTests.FINAL);
            }
            byte[] bytes = SkillEngineTests.MAPPER.writeValueAsBytes(Map.of("id", "wire-response-" + round, "object", "chat.completion",
                    "created", 1, "model", "configured-model", "choices", List.of(Map.of("index", 0, "message", message,
                            "finish_reason", round <= 5 ? "tool_calls" : "stop")),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            SkillEngine engine = engine(server.getAddress().getPort(), streamToolCalls);
            JsonNode input = SkillEngineTests.MAPPER.valueToTree(Map.of("username", "xiaoming"));
            SkillEngine.Run run;
            if (streaming) {
                var pending = CompletableFuture.supplyAsync(() -> engine.executeStreaming("shopping-recommendation", input,
                        event -> {}, delta -> { deltas.add(delta); firstContent.countDown(); }, () -> false), executor);
                assertTrue(firstContent.await(10, TimeUnit.SECONDS), () -> pending.isDone()
                        ? pending.join().toString() : "Upstream requests received: " + requests.size());
                if (streamToolCalls) {
                    assertFalse(pending.isDone(), "Content must arrive before upstream completes");
                    assertEquals(SkillEngineTests.FINAL.substring(0, 10), deltas.get(0).text());
                } else {
                    assertEquals(SkillEngineTests.FINAL, deltas.get(0).text());
                }
                releaseFinal.countDown();
                run = pending.get(10, TimeUnit.SECONDS);
                assertEquals(SkillEngineTests.FINAL, deltas.stream().map(SkillEngine.ContentDelta::text).reduce("", String::concat));
                assertTrue(deltas.stream().allMatch(delta -> delta.modelRound() == 6 && delta.runId().equals(run.runId())));
                assertTrue(requests.stream().filter(request -> request.path("stream").asBoolean()).allMatch(request -> request.path("stream").asBoolean()
                        && request.at("/stream_options/include_usage").asBoolean()));
            } else {
                run = engine.execute("shopping-recommendation", input);
            }
            assertEquals("SUCCESS", run.status(), run.error());
            assertEquals(6, requests.size());
            for (int index = 0; index < requests.size(); index++) {
                assertEquals(streaming && streamToolCalls, requests.get(index).path("stream").asBoolean());
            }
            if (!streamToolCalls) {
                assertEquals(2, requests.get(5).path("tools").size());
                assertFalse("none".equals(requests.get(5).path("tool_choice").asText()));
            }
            assertTrue(authorizations.stream().allMatch(value -> value.equals("Bearer fixture-key-not-real")));
            assertTrue(requests.stream().allMatch(request -> request.path("model").asText().equals("configured-model")));
            assertEquals("Skill", requests.get(0).path("tools").get(0).path("function").path("name").asText());
            assertEquals(2, requests.get(1).path("tools").size());
            assertEquals(List.of(1, 2, 1, 1, 2, 2), requests.stream().map(request -> request.path("tools").size()).toList());
            assertFalse(requests.get(0).path("parallel_tool_calls").asBoolean(true));
            assertTrue(requests.stream().noneMatch(request -> request.has("chat_template_kwargs")));
            assertTrue(requests.get(4).path("messages").toString().contains("wire-call-4"));
            assertTrue(requests.get(4).path("messages").toString().contains("SKU-001"));
            assertFalse(requests.get(4).path("messages").toString().contains("MOCK"));
            assertEquals("wire-response-6", run.modelTurns().get(5).responseId());
            assertEquals(10, run.modelTurns().get(0).promptTokens());
            assertEquals(5, run.modelTurns().get(0).completionTokens());
        } finally {
            releaseFinal.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upstreamAuthenticationFailureDoesNotExecuteToolsOrFallBack(boolean streaming) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"fixture upstream denied\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SkillEngine engine = engine(server.getAddress().getPort());
            JsonNode input = SkillEngineTests.MAPPER.valueToTree(Map.of("username", "xiaoming"));
            SkillEngine.Run run = streaming ? engine.executeStreaming("shopping-recommendation", input,
                    event -> {}, delta -> fail("No content on authentication failure"), () -> false)
                    : engine.execute("shopping-recommendation", input);
            assertEquals("FAILED", run.status());
            assertEquals(1, run.modelCalls());
            assertTrue(run.traces().isEmpty());
            assertTrue(run.output().isEmpty());
            assertFalse(SkillEngineTests.MAPPER.valueToTree(run).toString().contains("fixture-key"));
        } finally {
            server.stop(0);
        }
    }

    private void writeChunk(HttpExchange exchange, int round, Map<String, Object> delta, String finishReason) throws IOException {
        ObjectNode choice = SkillEngineTests.MAPPER.createObjectNode().put("index", 0);
        choice.set("delta", SkillEngineTests.MAPPER.valueToTree(delta));
        if (finishReason != null) choice.put("finish_reason", finishReason);
        writeEvent(exchange, Map.of("id", "wire-response-" + round, "object", "chat.completion.chunk",
                "created", 1, "model", "configured-model", "choices", List.of(choice)));
    }

    private void writeEvent(HttpExchange exchange, Object data) throws IOException {
        exchange.getResponseBody().write(("data: " + SkillEngineTests.MAPPER.writeValueAsString(data) + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private SkillEngine engine(int port) {
        return engine(port, true);
    }

    private SkillEngine engine(int port, boolean streamToolCalls) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dong.spring.ai.joyai.chat.base-url", "http://127.0.0.1:" + port)
                .withProperty("dong.spring.ai.joyai.chat.completions-path", "/v1/chat/completions")
                .withProperty("dong.spring.ai.joyai.chat.api-key", "fixture-key-not-real")
                .withProperty("dong.spring.ai.joyai.chat.options.model", "configured-model")
                .withProperty("odin.skills.model.stream-tool-calls", Boolean.toString(streamToolCalls));
        var registry = SkillEngineTests.registry();
        var catalog = new SkillCatalog(SkillEngineTests.MAPPER, SkillEngineTests.agentCatalogResource(), registry);
        return new SkillEngine(SkillEngineTests.MAPPER, catalog, registry, new SkillModelConfiguration().skillModelClient(environment));
    }
}
