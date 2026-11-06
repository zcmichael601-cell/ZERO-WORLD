package com.jd.diviner.ai.rec.odin.demo.app.tools.product;

import com.jd.diviner.ai.rec.odin.demo.app.domain.product.ProductSearchService;

import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.product.JdProductCatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JdSearchProductsTool implements SkillTool {

    private final ProductSearchService service;

    public JdSearchProductsTool(ObjectMapper mapper, JdProductCatalog catalog) {
        this(new ProductSearchService(mapper, catalog));
    }

    public JdSearchProductsTool(ProductSearchService service) {
        this.service = service;
    }

    public String name() { return "search_products"; }
    public JsonNode prepareArguments(JsonNode profile, JsonNode behaviors) { return service.prepareArguments(profile, behaviors); }
    public String dataMode() { return "JD_PAGE_SNAPSHOT"; }
    public String description() {
        return "从已采集的真实京东 SKU 目录按兴趣和预算查询；不是全站实时搜索。返回采集时间、商详链接和参考价。"
                + "支持手机、3C、家电、商超、服饰及原有运动家居等品类，优先使用具体需求标签召回。"
                + "排除明确缺货、价格未知及过期快照；库存 UNKNOWN 不代表有货。无匹配时返回空数组，不生成商品。";
    }
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "interests":{"type":"array","items":{"type":"string","minLength":1,"maxLength":50},"maxItems":12,"description":"从真实画像及行为提炼需求标签，优先具体品类如手机、笔记本电脑、冰箱、牛奶、纸巾、男装，而非仅数码、家居等大类；缺少依据传空数组，不自行猜测兴趣"},
                  "budget":{"type":["number","null"],"exclusiveMinimum":0,"description":"已知预算传正数；画像预算缺失必须传 null，不假设用户预算"},
                  "limit":{"type":"integer","minimum":1,"maximum":12}},
                 "required":["interests","budget","limit"],"additionalProperties":false}
                """;
    }
    public JsonNode execute(JsonNode arguments) { return service.search(arguments); }
}
