package com.jd.diviner.ai.rec.odin.demo.app.infrastructure.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class OpenJevClient {

    public interface Transport {
        JsonNode post(URI uri, JsonNode body, Duration timeout);
    }

    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final Transport transport;

    public OpenJevClient(ObjectMapper mapper, URI endpoint, String model, Duration timeout) {
        this(mapper, endpoint, model, timeout, new HttpTransport(mapper));
    }

    public OpenJevClient(ObjectMapper mapper, URI endpoint, String model, Duration timeout, Transport transport) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("OpenJEV model 不能为空");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("OpenJEV 超时需在 (0, 2min]");
        }
        this.model = model;
        this.timeout = timeout;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** One yes/no question per option. The returned value is the affirmative score, higher means more suitable. */
    public Map<String, Double> yesNo(String state, List<String> options) {
        if (state == null || state.isBlank() || state.length() > 8000) {
            throw new IllegalArgumentException("OpenJEV state 需为 1~8000 字符");
        }
        if (options == null || options.isEmpty() || options.size() > 200) {
            throw new IllegalArgumentException("OpenJEV 是否题需为 1~200 个");
        }
        ObjectNode questions = mapper.createObjectNode();
        for (String option : options) {
            if (option == null || option.isBlank() || option.length() > 100) {
                throw new IllegalArgumentException("OpenJEV 候选项无效");
            }
            String name = option.strip();
            if (questions.has(name)) {
                throw new IllegalArgumentException("OpenJEV 候选项重复: " + name);
            }
            questions.set(name, mapper.createObjectNode()
                    .put("type", "noul")
                    .put("instructions", "该用户当前是否适合被推荐「" + name + "」类商品"));
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("state", state);
        body.set("questions", questions);
        JsonNode response = transport.post(endpoint, body, timeout);
        JsonNode answers = response.path("answers");
        Map<String, Double> scores = new LinkedHashMap<>();
        if (answers.isObject()) {
            answers.fields().forEachRemaining(entry -> {
                if (questions.has(entry.getKey()) && entry.getValue().path("noul").isNumber()) {
                    scores.put(entry.getKey(), entry.getValue().path("noul").asDouble());
                }
            });
        }
        if (scores.isEmpty()) {
            throw new IllegalStateException("OpenJEV 未返回是否分");
        }
        return Map.copyOf(scores);
    }

    public ChoiceAnswer choice(String state, String questionName, String instructions, List<String> options) {
        if (state == null || state.isBlank() || state.length() > 8000) {
            throw new IllegalArgumentException("OpenJEV state 需为 1~8000 字符");
        }
        if (questionName == null || questionName.isBlank() || instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("OpenJEV question 参数无效");
        }
        if (options == null || options.isEmpty() || options.size() > 200) {
            throw new IllegalArgumentException("OpenJEV choice 候选需为 1~200 个");
        }
        Map<String, String> keyToOption = new LinkedHashMap<>();
        ObjectNode criteria = mapper.createObjectNode();
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (option == null || option.isBlank() || option.length() > 100) {
                throw new IllegalArgumentException("OpenJEV 候选项无效");
            }
            String key = "i" + i;
            keyToOption.put(key, option);
            criteria.put(key, option.strip());
        }
        ObjectNode question = mapper.createObjectNode();
        question.put("type", "choice");
        question.put("instructions", instructions);
        question.set("criteria", criteria);
        ObjectNode questions = mapper.createObjectNode();
        questions.set(questionName, question);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("state", state);
        body.set("questions", questions);
        JsonNode response = transport.post(endpoint, body, timeout);
        JsonNode answer = response.path("answers").path(questionName);
        if (!"choice".equals(answer.path("type").asText()) || !answer.path("choice").isTextual()) {
            throw new IllegalStateException("OpenJEV 未返回有效 choice 结果");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonNode probs = answer.path("probabilities");
        if (probs.isObject()) {
            probs.fields().forEachRemaining(entry -> {
                String option = keyToOption.get(entry.getKey());
                if (option != null && entry.getValue().isNumber()) {
                    probabilities.put(option, entry.getValue().asDouble());
                }
            });
        }
        String key = answer.path("choice").asText();
        String selected = probabilities.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(keyToOption.get(key));
        if (selected == null) {
            throw new IllegalStateException("OpenJEV 返回了未知 choice: " + key);
        }
        double confidence = answer.path("confidence").isNumber() ? answer.path("confidence").asDouble() : 0.0;
        return new ChoiceAnswer(selected, confidence, Map.copyOf(probabilities));
    }

    public record ChoiceAnswer(String choice, double confidence, Map<String, Double> probabilities) {
    }

    private static final class HttpTransport implements Transport {
        private final ObjectMapper mapper;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        private HttpTransport(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public JsonNode post(URI uri, JsonNode body, Duration timeout) {
            try {
                byte[] payload = mapper.writeValueAsBytes(body);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
                var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<byte[]> response = pending.get(timeout.toMillis() + 1000L, TimeUnit.MILLISECONDS);
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("OpenJEV HTTP " + response.statusCode());
                }
                byte[] bytes = response.body();
                if (bytes.length > 2 * 1024 * 1024) {
                    throw new IllegalStateException("OpenJEV 响应过大");
                }
                return mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenJEV 请求被中断", exception);
            } catch (Exception exception) {
                throw new IllegalStateException("OpenJEV 请求失败: " + exception.getMessage(), exception);
            }
        }
    }
}
