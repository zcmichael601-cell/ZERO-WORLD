package com.jd.diviner.ai.rec.odin.demo.app.agent.runtime;

import com.jd.diviner.ai.rec.odin.demo.app.agent.skill.SkillDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class SkillExtensions {
    private final Map<String, SkillExtension> extensions = new LinkedHashMap<>();
    private final SkillExtension generic = new SkillExtension() {
        public String name() { return "generic"; }
        public SkillBehavior create(SkillContext context) { return new SkillBehavior(context); }
        public void validateDefinition(SkillDefinition skill) {
            if ("WORKFLOW".equals(skill.executionMode())) throw new IllegalArgumentException("WORKFLOW 需配置提供执行步骤的 handler");
        }
    };

    public SkillExtensions() { this(discovered()); }

    public SkillExtensions(List<SkillExtension> extensions) {
        this.extensions.put(generic.name(), generic);
        for (SkillExtension extension : extensions) {
            if (extension.name() == null || !extension.name().matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")
                    || this.extensions.putIfAbsent(extension.name(), extension) != null) {
                throw new IllegalArgumentException("非法或重复的 Skill handler: " + extension.name());
            }
        }
    }

    public static List<SkillExtension> discovered() {
        List<SkillExtension> extensions = new ArrayList<>();
        ServiceLoader.load(SkillExtension.class).forEach(extensions::add);
        return extensions;
    }

    public SkillExtension resolve(SkillDefinition skill) {
        if (skill.handler() != null) {
            SkillExtension extension = extensions.get(skill.handler());
            if (extension == null) throw new IllegalArgumentException("未注册的 Skill handler: " + skill.handler());
            return extension;
        }
        var matching = extensions.values().stream().filter(extension -> extension.supportsLegacy(skill)).toList();
        if (matching.size() > 1) throw new IllegalArgumentException("Skill 匹配多个兼容 handler，请显式配置: " + skill.id());
        return matching.isEmpty() ? generic : matching.get(0);
    }
}
