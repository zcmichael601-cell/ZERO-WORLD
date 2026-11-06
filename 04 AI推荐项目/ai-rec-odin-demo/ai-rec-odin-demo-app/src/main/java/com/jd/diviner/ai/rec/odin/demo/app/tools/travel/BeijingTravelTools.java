package com.jd.diviner.ai.rec.odin.demo.app.tools.travel;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.travel.TravelTransport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BeijingTravelTools {

    public static final String ATTRACTIONS_URL = "https://m.ctrip.com/webapp/xranking/cranking/crankingCity/100001406540.html";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Pattern NEXT_DATA = Pattern.compile("<script\\b(?=[^>]*\\bid=[\"']__NEXT_DATA__[\"'])[^>]*>(.*?)</script\\s*>", Pattern.DOTALL);
    private static final Map<String, String> UNITS = Map.of("temperature_2m_max", "°C", "temperature_2m_min", "°C",
            "precipitation_probability_max", "%", "precipitation_sum", "mm", "wind_speed_10m_max", "km/h");
    private final ObjectMapper mapper;
    private final TravelTransport transport;
    private final Clock clock;

    public BeijingTravelTools(ObjectMapper mapper, TravelTransport transport, Clock clock) {
        this.mapper = mapper;
        this.transport = transport;
        this.clock = clock;
    }

    public SkillTool weather() {
        return tool("get_beijing_weather", "请求 Open-Meteo，查询北京城区从明天起 1~7 天的天气预报，不是山地预报；接口不可用时报告错误。",
                "{\"type\":\"object\",\"properties\":{\"days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":7}},\"required\":[\"days\"],\"additionalProperties\":false}", this::forecast);
    }

    public SkillTool attractions() {
        return tool("get_beijing_hot_attractions", "读取携程北京必打卡景点榜公开页面首屏；热度为平台榜单，不是实时客流。接口不可用时报告错误。",
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":7}},\"required\":[\"limit\"],\"additionalProperties\":false}", this::hotAttractions);
    }

    private SkillTool tool(String name, String description, String schema, Function<JsonNode, JsonNode> operation) {
        return new SkillTool() {
            public String name() { return name; }
            public String description() { return description; }
            public String inputSchema() { return schema; }
            public String dataMode() { return "LIVE"; }
            public JsonNode execute(JsonNode arguments) { return operation.apply(arguments); }
        };
    }

    private JsonNode forecast(JsonNode arguments) {
        int days = boundedInteger(arguments, "days");
        URI uri = URI.create(FORECAST_URL + "?latitude=39.9042&longitude=116.4074"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max"
                + "&timezone=Asia%2FShanghai&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm&forecast_days=" + (days + 1));
        JsonNode payload = parse(transport.get(uri), "天气");
        JsonNode daily = payload.path("daily");
        if (!"Asia/Shanghai".equals(payload.path("timezone").asText()) || !daily.path("time").isArray()) {
            throw unavailable("天气接口缺少日预报或时区不符");
        }
        for (var unit : UNITS.entrySet()) {
            if (!unit.getValue().equals(payload.path("daily_units").path(unit.getKey()).asText())) {
                throw unavailable("天气接口单位不符: " + unit.getKey());
            }
        }
        for (String field : Set.of("weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max", "precipitation_sum", "wind_speed_10m_max")) {
            if (!daily.path(field).isArray() || daily.path(field).size() != daily.path("time").size()) {
                throw unavailable("天气日预报数组不完整: " + field);
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("city", "北京").put("timezone", "Asia/Shanghai");
        result.set("source", source("Open-Meteo", uri.toString()));
        result.put("scope", "北京市中心网格预报，不代表长城等山区天气；不是气象预警。出行前复查官方预警。");
        result.set("units", mapper.valueToTree(UNITS));
        ArrayNode forecast = result.putArray("forecast");
        LocalDate tomorrow = LocalDate.now(clock.withZone(BEIJING)).plusDays(1);
        for (int offset = 0; offset < days; offset++) {
            String date = tomorrow.plusDays(offset).toString();
            int match = -1;
            for (int index = 0; index < daily.path("time").size(); index++) {
                if (date.equals(daily.path("time").get(index).asText())) {
                    if (match >= 0) throw unavailable("天气接口包含重复日期");
                    match = index;
                }
            }
            if (match < 0) throw unavailable("天气接口缺少未来日期 " + date + "，拒绝使用过期预报");
            ObjectNode day = forecast.addObject().put("date", date);
            double maximum = number(daily, "temperature_2m_max", match, -90, 60);
            double minimum = number(daily, "temperature_2m_min", match, -90, 60);
            if (minimum > maximum) throw unavailable("天气最高温低于最低温");
            JsonNode code = daily.path("weather_code").get(match);
            if (!code.isIntegralNumber() || !code.canConvertToInt()) throw unavailable("天气代码无效");
            day.put("weatherCode", code.asInt()).put("condition", condition(code.asInt()));
            day.put("temperatureMaxC", maximum).put("temperatureMinC", minimum);
            day.put("precipitationProbabilityPercent", number(daily, "precipitation_probability_max", match, 0, 100));
            day.put("precipitationMm", number(daily, "precipitation_sum", match, 0, 3000));
            day.put("windMaxKmh", number(daily, "wind_speed_10m_max", match, 0, 500));
        }
        return result;
    }

    private JsonNode hotAttractions(JsonNode arguments) {
        int limit = boundedInteger(arguments, "limit");
        String html = transport.get(URI.create(ATTRACTIONS_URL));
        Matcher match = NEXT_DATA.matcher(html);
        if (!match.find()) throw unavailable("携程页面结构变化或访问受限，缺少 __NEXT_DATA__");
        JsonNode state = parse(match.group(1), "携程榜单").at("/props/pageProps/initialState");
        JsonNode ranking = state.path("rankingDetail");
        JsonNode items = state.path("rankingItemInfo").path("itemList");
        if (!"100001406540".equals(ranking.path("rankingId").asText())
                || !"sight".equals(ranking.path("businessType").asText()) || !items.isArray() || items.isEmpty()) {
            throw unavailable("携程北京景点榜结构或榜单标识不符");
        }
        ObjectNode result = mapper.createObjectNode().put("city", "北京");
        ObjectNode source = source("携程", ATTRACTIONS_URL);
        source.put("rankingTitle", text(ranking, "title", 100));
        if (ranking.path("changeTime").isIntegralNumber() && ranking.path("changeTime").asLong() > 0) {
            source.put("providerUpdatedAt", Instant.ofEpochMilli(ranking.path("changeTime").asLong()).toString());
        }
        result.set("source", source);
        result.put("popularityMeaning", "携程平台必打卡景点榜的排名与热度分，非实时客流、非全网热度；仅公开页面首屏子集。");
        result.put("availableCount", items.size());
        ArrayNode attractions = result.putArray("attractions");
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            JsonNode basic = item.path("basicInfo");
            JsonNode extra = item.path("extInfo");
            if (!"北京".equals(extra.path("districtInfo").path("name").asText())) {
                throw unavailable("榜单包含非北京景点");
            }
            String id = text(basic, "businessId", 40);
            if (!seen.add(id) || !basic.path("rank").isIntegralNumber() || basic.path("rank").asInt() < 1) {
                throw unavailable("榜单景点标识或名次无效");
            }
            if (attractions.size() >= limit) continue;
            ObjectNode attraction = attractions.addObject().put("id", id).put("name", text(basic, "businessName", 150));
            attraction.put("rank", basic.path("rank").asInt());
            attraction.put("description", text(extra, "shortFeature", 500));
            attraction.put("location", text(extra, "location", 200));
            attraction.put("hotScore", text(extra, "hotScore", 30));
            attraction.put("rating", optionalText(extra.path("commentInfo"), "commentScore", 30));
            attraction.put("reviewCountDisplay", optionalText(extra.path("commentInfo"), "commentCount", 40));
            String url = text(basic.path("jumpUrl"), "commonUrl", 2048);
            URI link = URI.create(url);
            if (!"https".equals(link.getScheme()) || !"m.ctrip.com".equals(link.getHost())
                    || link.getUserInfo() != null || link.getPort() != -1) throw unavailable("景点来源链接无效");
            attraction.put("url", url);
        }
        return result;
    }

    private ObjectNode source(String provider, String url) {
        return mapper.createObjectNode().put("provider", provider).put("url", url)
                .put("dataMode", "LIVE").put("fetchedAt", clock.instant().toString());
    }

    private JsonNode parse(String body, String provider) {
        try {
            if (body == null || body.length() > 2 * 1024 * 1024) throw unavailable(provider + "响应为空或过大");
            JsonNode result = mapper.readTree(body);
            if (result == null || !result.isObject()) throw unavailable(provider + "响应不是 JSON 对象");
            return result;
        } catch (java.io.IOException exception) {
            throw unavailable(provider + "响应无法解析");
        }
    }

    private static int boundedInteger(JsonNode arguments, String field) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 1
                || !arguments.path(field).isIntegralNumber() || !arguments.path(field).canConvertToInt()
                || arguments.path(field).asInt() < 1 || arguments.path(field).asInt() > 7) {
            throw new IllegalArgumentException(field + " 必须为 1~7 的整数，且不能传入 URL 或额外参数");
        }
        return arguments.path(field).asInt();
    }

    private static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum) {
            throw unavailable("景点数据字段无效: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field, int maximum) {
        return node.path(field).isMissingNode() || node.path(field).isNull() ? null : text(node, field, maximum);
    }

    private static double number(JsonNode node, String field, int index, double minimum, double maximum) {
        JsonNode value = node.path(field).get(index);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())
                || value.asDouble() < minimum || value.asDouble() > maximum) throw unavailable("天气数值缺失或无效: " + field);
        return value.asDouble();
    }

    private static String condition(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大部晴朗";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61, 63, 65 -> "雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75, 77 -> "雪";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95, 96, 99 -> "雷暴（可能伴冰雹）";
            default -> throw unavailable("未知 WMO 天气代码: " + code);
        };
    }

    private static IllegalStateException unavailable(String reason) {
        return new IllegalStateException(reason + "；当前数据不可用");
    }
}
