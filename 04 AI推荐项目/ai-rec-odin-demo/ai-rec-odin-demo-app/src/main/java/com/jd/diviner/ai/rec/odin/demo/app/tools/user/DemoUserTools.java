package com.jd.diviner.ai.rec.odin.demo.app.tools.user;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class DemoUserTools {

    private static final String USER_SCHEMA = """
            {"type":"object","properties":{"username":{"type":"string","minLength":1,"maxLength":64}},
             "required":["username"],"additionalProperties":false}
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

    private DemoUserTools() {
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

}
