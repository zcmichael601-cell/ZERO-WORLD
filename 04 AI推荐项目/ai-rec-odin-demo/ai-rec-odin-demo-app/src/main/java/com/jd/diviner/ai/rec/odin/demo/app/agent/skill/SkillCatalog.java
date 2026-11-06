package com.jd.diviner.ai.rec.odin.demo.app.agent.skill;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.ToolRegistry;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtension;
import com.jd.diviner.ai.rec.odin.demo.app.agent.runtime.SkillExtensions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

public class SkillCatalog {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{0,63}");
    private final SkillExtensions extensions;
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    private final Map<String, String> instructions = new HashMap<>();

    public SkillCatalog(ObjectMapper mapper, Resource resource, ToolRegistry registry) {
        this(mapper, resource, registry, new SkillExtensions());
    }

    public SkillCatalog(ObjectMapper mapper, Resource resource, ToolRegistry registry, SkillExtensions extensions) {
        this.extensions = extensions;
        try (InputStream stream = resource.getInputStream()) {
            SkillDefinition.Catalog catalog = mapper.readerFor(SkillDefinition.Catalog.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(stream);
            if (catalog == null || catalog.version() != 2 || catalog.skills() == null || catalog.skills().isEmpty()) {
                throw new IllegalArgumentException("需要 version: 2 的 Skill 配置及非空 skills");
            }
            for (SkillDefinition skill : catalog.skills()) {
                validate(skill, registry);
                if (skills.putIfAbsent(skill.id(), skill) != null) {
                    throw new IllegalArgumentException("重复的 Skill: " + skill.id());
                }
                try (InputStream instructionStream = resource.createRelative(skill.instructions()).getInputStream()) {
                    byte[] bytes = instructionStream.readNBytes(65537);
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    if (bytes.length > 65536 || content.isBlank()) {
                        throw new IllegalArgumentException("SKILL.md 必须非空且不超过 64KB: " + skill.id());
                    }
                    validateFrontMatter(content, skill.id());
                    instructions.put(skill.id(), content);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Skill 配置或指令文件: " + resource.getDescription(), exception);
        }
    }

    public SkillExtension extension(SkillDefinition skill) { return extensions.resolve(skill); }

    public List<SkillDefinition> enabledSkills() {
        return skills.values().stream().filter(SkillDefinition::enabled).toList();
    }

    public SkillDefinition findEnabled(String id) {
        SkillDefinition skill = skills.get(id);
        return skill != null && skill.enabled() ? skill : null;
    }

    public String instructions(String id) {
        if (findEnabled(id) == null) {
            throw new IllegalArgumentException("Skill 不存在或未启用");
        }
        return instructions.get(id);
    }

    private void validate(SkillDefinition skill, ToolRegistry registry) {
        if (skill == null) {
            throw new IllegalArgumentException("Skill 不能为空");
        }
        requireIdentifier(skill.id());
        SkillExtension extension = extensions.resolve(skill);
        if (skill.name() == null || skill.name().isBlank() || skill.description() == null || skill.description().isBlank()
                || skill.maxModelCalls() < extension.minimumModelCalls(skill) || skill.maxModelCalls() > 12 || skill.maxToolCalls() < 1 || skill.maxToolCalls() > 20) {
            throw new IllegalArgumentException("Skill 需要名称、说明、符合执行模式要求的模型调用上限（最多 12）及 1~20 次工具调用上限");
        }
        validateNames(skill.requiredInputs());
        validateNames(skill.tools());
        validateNames(skill.requiredTools());
        validateNames(skill.requiredOutputs());
        if (!extension.supportsMode(skill.executionMode())) {
            throw new IllegalArgumentException("不支持的 Skill executionMode");
        }
        if (skill.reasoningEffort() != null && !List.of("low", "medium", "high").contains(skill.reasoningEffort())) {
            throw new IllegalArgumentException("reasoningEffort 仅支持 low/medium/high；未配置时沿用模型默认值");
        }
        extension.validateDefinition(skill);
        if (skill.tools().isEmpty() || skill.requiredOutputs().isEmpty() || !skill.tools().containsAll(skill.requiredTools())) {
            throw new IllegalArgumentException("需要非空 tools/requiredOutputs，requiredTools 必须属于 tools");
        }
        skill.tools().forEach(registry::require);
        if (skill.tools().contains("Skill")) {
            throw new IllegalArgumentException("Skill 是保留工具名");
        }
        if (skill.instructions() == null || !skill.instructions().matches("(?:[a-zA-Z0-9_-]+/)*SKILL\\.md")) {
            throw new IllegalArgumentException("instructions 必须是配置文件目录下的相对 SKILL.md 路径，不允许路径穿越");
        }
    }

    private void validateNames(List<String> names) {
        if (names == null || new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("标识符列表不能为空或重复");
        }
        names.forEach(this::requireIdentifier);
    }

    private void requireIdentifier(String id) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("非法标识符: " + id);
        }
    }

    private void validateFrontMatter(String content, String id) {
        String normalized = content.replace("\r\n", "\n");
        int end = normalized.indexOf("\n---\n", 4);
        if (!normalized.startsWith("---\n") || end < 0) {
            throw new IllegalArgumentException("SKILL.md 必须包含 name/description front matter: " + id);
        }
        String header = normalized.substring(4, end);
        if (!Pattern.compile("(?m)^name:\\s*" + Pattern.quote(id) + "\\s*$").matcher(header).find()
                || !Pattern.compile("(?m)^description:[ \\t]*\\S.+$").matcher(header).find()) {
            throw new IllegalArgumentException("SKILL.md 的 name 必须匹配配置 id，description 必须非空: " + id);
        }
    }
}
