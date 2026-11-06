package com.jd.diviner.ai.rec.odin.demo.app.tools.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.user.UserIdentifier;

public final class SessionUserTool implements SkillTool {
    private final ObjectMapper mapper;
    private final SkillTool delegate;
    private final String username;

    public SessionUserTool(ObjectMapper mapper, SkillTool delegate, String username) {
        this.mapper = mapper;
        this.delegate = delegate;
        this.username = UserIdentifier.validate(username);
    }

    public String name() { return delegate.name(); }
    public String dataMode() { return delegate.dataMode(); }
    public String description() {
        return delegate.description() + " 用户已绑定到本次会话，建议传入空对象 {}，无需生成或转换用户标识。";
    }

    public String inputSchema() {
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject("username").put("type", "string")
                .put("description", "可省略，由服务端使用本次请求的用户标识；如提供则必须与本次输入一致")
                .putArray("enum").add(username);
        return schema.toString();
    }

    public JsonNode execute(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() > 1
                || arguments.size() == 1 && !arguments.has("username")
                || arguments.has("username") && (!arguments.path("username").isTextual()
                || !username.equals(arguments.path("username").asText().strip()))) {
            throw new InvalidArguments();
        }
        return delegate.execute(mapper.createObjectNode().put("username", username));
    }

    public static final class InvalidArguments extends IllegalArgumentException {
        public InvalidArguments() {
            super("用户查询参数不合法或用户标识与本次会话不一致，已阻止查询；请使用空参数对象，由服务端绑定当前用户");
        }
    }
}
