package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public final class AnswerFailure extends RuntimeException {
    public enum Kind { FORMAT, EVIDENCE }

    private final Kind kind;
    private final String reason;
    private final JsonNode constraint;
    private final JsonNode issues;

    public AnswerFailure(String reason) {
        this(Kind.FORMAT, reason, null, JsonNodeFactory.instance.arrayNode());
    }

    public AnswerFailure(String reason, Throwable cause) {
        this(reason);
        initCause(cause);
    }

    public AnswerFailure(Kind kind, String reason, JsonNode constraint, JsonNode issues) {
        super(reason);
        this.kind = kind;
        this.reason = reason;
        this.constraint = constraint == null ? null : constraint.deepCopy();
        this.issues = issues.deepCopy();
    }

    public Kind kind() { return kind; }
    public String reason() { return reason; }
    public JsonNode constraint() { return constraint; }
    public JsonNode issues() { return issues; }
}
