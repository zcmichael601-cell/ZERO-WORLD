package com.jd.diviner.ai.rec.odin.demo.fixtures;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MockRecommendationTools {

    private static final String USER_SCHEMA = """
            {"type":"object","properties":{"username":{"type":"string","minLength":1,"maxLength":64}},
             "required":["username"],"additionalProperties":false}
            """;
    private static final String SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "interests":{"type":"array","items":{"type":"string"},"minItems":1,"description":"由你从画像与行为推断的需求标签，例如跑步、户外、健康、数码、办公、音频、家居、咖啡、厨房"},
              "budget":{"type":"number","exclusiveMinimum":0,"description":"画像中的单件预算上限"},
              "limit":{"type":"integer","minimum":1,"maximum":12}},
             "required":["interests","budget","limit"],"additionalProperties":false}
            """;

    private static final List<MockUser> USERS = List.of(
            new MockUser("xiaoming", "小明", "运动生活派", "上海", 800, List.of("跑步", "户外", "健康"),
                    List.of("自然风光", "户外徒步", "摄影"),
                    List.of("近 7 天浏览跑鞋 8 次", "收藏轻量户外背包", "最近购买运动袜",
                            "近 7 天搜索北京长城徒步路线 4 次", "收藏颐和园湖畔摄影攻略")),
            new MockUser("xiaoyu", "小雨", "数码效率派", "北京", 1500, List.of("数码", "办公", "音频"),
                    List.of("主题乐园", "科技体验", "演出"),
                    List.of("近 7 天浏览降噪耳机 6 次", "搜索机械键盘", "最近购买笔记本支架",
                            "近 7 天浏览北京环球度假区游玩攻略 5 次", "收藏沉浸式演出和科技展览")),
            new MockUser("laowang", "老王", "品质居家派", "杭州", 600, List.of("家居", "咖啡", "厨房"),
                    List.of("历史文化", "园林", "轻松步行"),
                    List.of("近 7 天浏览咖啡器具 5 次", "收藏收纳好物", "最近购买咖啡豆",
                            "近 7 天浏览故宫文物和国家博物馆展览介绍 6 次", "收藏恭王府园林与胡同慢游攻略")));

    private static final List<Product> PRODUCTS = List.of(
            new Product("SKU-001", "轻风缓震跑鞋", "轻量回弹，让日常慢跑更轻松", "运动", 399, List.of("跑步", "健康"), "👟"),
            new Product("SKU-002", "山野轻量双肩包", "18L 轻量容量，装下周末的好天气", "户外", 259, List.of("户外"), "🎒"),
            new Product("SKU-003", "律动运动手环", "运动记录与日常活动提醒", "数码", 199, List.of("健康", "跑步", "数码"), "⌚"),
            new Product("SKU-004", "随行运动水杯", "轻巧易携，陪伴每一次出发", "运动", 89, List.of("跑步", "户外"), "🥤"),
            new Product("SKU-005", "静界无线降噪耳机", "专注工作，也享受自己的音乐时间", "数码", 899, List.of("数码", "办公", "音频"), "🎧"),
            new Product("SKU-006", "方寸无线机械键盘", "紧凑布局，多设备轻松切换", "办公", 459, List.of("数码", "办公"), "⌨️"),
            new Product("SKU-007", "桌面多口充电站", "桌面设备集中补能", "数码", 229, List.of("数码", "办公"), "🔌"),
            new Product("SKU-008", "口袋蓝牙音箱", "把喜欢的旋律带到身边", "音频", 299, List.of("音频", "数码"), "🔊"),
            new Product("SKU-009", "晨光手冲咖啡套装", "从一杯手冲，开始慢一点的早晨", "咖啡", 239, List.of("咖啡", "厨房"), "☕"),
            new Product("SKU-010", "原木桌面收纳盒", "为生活的小物件找到位置", "家居", 129, List.of("家居", "办公"), "🗃️"),
            new Product("SKU-011", "轻食多功能煮锅", "小巧容量，照顾日常的一餐一饭", "厨房", 329, List.of("厨房", "家居"), "🍲"),
            new Product("SKU-012", "柔光阅读台灯", "暖色桌面光，陪你阅读与休息", "家居", 179, List.of("家居", "办公"), "💡"));

    private MockRecommendationTools() {
    }

    public static List<MockUser> users() {
        return USERS;
    }

    public static MockUser requireUser(String username) {
        if (username == null || username.isBlank() || username.length() > 64
                || username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("用户名必须为 1~64 个字符，且不能包含控制字符");
        }
        return USERS.stream().filter(user -> user.username().equalsIgnoreCase(username.strip())
                || user.displayName().equals(username.strip())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("该用户暂无可用数据，请从用户列表中选择"));
    }

    private static MockUser user(JsonNode arguments) {
        if (arguments == null || !arguments.path("username").isTextual()) {
            throw new IllegalArgumentException("username 必须为字符串");
        }
        return requireUser(arguments.path("username").asText());
    }

    public static SkillTool profile(ObjectMapper mapper) {
        return tool("get_user_profile", "查询用户画像：返回兴趣、出游偏好与单件预算，不含需求预测", USER_SCHEMA, arguments -> {
            MockUser user = user(arguments);
            return mapper.valueToTree(Map.of("username", user.username(), "displayName", user.displayName(),
                    "segment", user.segment(), "city", user.city(), "budget", user.budget(), "interests", user.interests(),
                    "travelInterests", user.travelInterests(), "source", "MOCK", "notice", "此画像为预置用户样本，不代表真实用户信息"));
        });
    }

    public static SkillTool behaviors(ObjectMapper mapper) {
        return tool("get_user_behaviors", "查询近期浏览、搜索、收藏和购买行为信号，由模型分析需求", USER_SCHEMA, arguments -> {
            MockUser user = user(arguments);
            return mapper.valueToTree(Map.of("username", user.username(), "source", "MOCK", "signals", user.behaviors()));
        });
    }

    public static SkillTool products(ObjectMapper mapper) {
        return tool("search_products", "按需求标签和预算查询候选商品；返回商品信息，由模型决定推荐排序及理由", SEARCH_SCHEMA, arguments -> {
            JsonNode limitNode = arguments.path("limit");
            if (!limitNode.isIntegralNumber() || !limitNode.canConvertToInt() || limitNode.asInt() < 1 || limitNode.asInt() > 12) {
                throw new IllegalArgumentException("limit 必须为 1~12 的整数");
            }
            JsonNode interests = arguments.path("interests");
            JsonNode budget = arguments.path("budget");
            if (!interests.isArray() || interests.isEmpty() || interests.size() > 12 || !budget.isNumber() || budget.asDouble() <= 0) {
                throw new IllegalArgumentException("需要非空 interests 数组及正数 budget");
            }
            for (JsonNode interest : interests) {
                if (!interest.isTextual() || interest.asText().isBlank()) {
                    throw new IllegalArgumentException("interests 必须是非空字符串数组");
                }
            }
            List<String> tags = mapper.convertValue(interests, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            List<Product> candidates = PRODUCTS.stream()
                    .filter(product -> product.price() <= budget.asDouble())
                    .filter(product -> product.tags().stream().anyMatch(tags::contains))
                    .limit(limitNode.asInt()).toList();
            return mapper.valueToTree(Map.of("source", "MOCK", "products", candidates));
        });
    }

    private static SkillTool tool(String name, String description, String schema, Function<JsonNode, JsonNode> executor) {
        return new SkillTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String inputSchema() {
                return schema;
            }

            @Override
            public JsonNode execute(JsonNode arguments) {
                return executor.apply(arguments);
            }
        };
    }

    public record MockUser(String username, String displayName, String segment, String city, int budget,
                           List<String> interests, List<String> travelInterests, List<String> behaviors) {
    }

    public record Product(String id, String name, String description, String category, int price, List<String> tags, String icon) {
    }

}
