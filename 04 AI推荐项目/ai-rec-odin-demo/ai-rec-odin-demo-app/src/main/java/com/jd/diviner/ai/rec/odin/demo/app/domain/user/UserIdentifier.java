package com.jd.diviner.ai.rec.odin.demo.app.domain.user;

public final class UserIdentifier {
    private UserIdentifier() {}

    public static String validate(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("用户标识必须为 1~64 个字符，且不能包含控制字符；真实用户请填写 AI Memory 用户 UUID");
        }
        return value.strip();
    }
}
