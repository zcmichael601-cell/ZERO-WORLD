package com.jd.diviner.ai.rec.odin.demo.app.agent.model;

public final class ModelGenerationException extends RuntimeException {
    private final String reason;

    public ModelGenerationException(String reason) {
        super("模型生成异常（" + reason + "），本轮内容未通过校验，未执行本轮工具");
        this.reason = reason;
    }

    public String reason() { return reason; }
}
