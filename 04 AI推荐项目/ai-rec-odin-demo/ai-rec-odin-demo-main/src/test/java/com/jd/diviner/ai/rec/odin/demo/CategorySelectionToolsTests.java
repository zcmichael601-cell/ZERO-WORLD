package com.jd.diviner.ai.rec.odin.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.Cid2NameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.tools.category.CategorySelectionTools;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategorySelectionToolsTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsFreeFormProductTierBrandQuery() {
        var tools = tools();

        var selected = tools.selectCid1().execute(
                MAPPER.createObjectNode().put("query", "羽毛球拍｜川崎"));

        assertEquals("羽毛球拍|川崎", selected.path("query").asText());
        assertEquals("羽毛球拍", selected.path("product").asText());
        assertEquals("川崎", selected.path("brand").asText());
        assertEquals("羽毛球拍", selected.path("cid2_name").asText());
        assertEquals("QUERY_INTEREST", selected.path("source").asText());
    }

    @Test
    void doesNotRequireCatalogCid1OrCid2Names() {
        var tools = tools();

        var selected = tools.selectCid1().execute(
                MAPPER.createObjectNode().put("query", "不存在的产品词|自有品牌"));

        assertEquals("不存在的产品词|自有品牌", selected.path("query").asText());
        assertEquals("不存在的产品词", selected.path("product").asText());
    }

    @Test
    void rejectsIncompleteQuery() {
        var tools = tools();

        assertThrows(IllegalArgumentException.class,
                () -> tools.selectCid1().execute(MAPPER.createObjectNode().put("query", "羽毛球拍｜中高端｜川崎")));
        assertThrows(IllegalArgumentException.class,
                () -> tools.selectCid1().execute(MAPPER.createObjectNode().put("query", "羽毛球拍")));
        assertThrows(IllegalArgumentException.class,
                () -> tools.selectCid1().execute(MAPPER.createObjectNode().put("cid1_name", "家装建材")));
    }

    @Test
    void stillGatesSelectCid2AgainstTheCatalog() {
        var tools = tools();

        var cid2Arguments = MAPPER.createObjectNode();
        cid2Arguments.putArray("interests").addObject()
                .put("cid1_name", "家装建材")
                .put("cid2_name", "基建材料")
                .put("interest", 72)
                .put("confidence", 46);
        var cid2 = tools.selectCid2().execute(cid2Arguments);
        assertEquals("基建材料", cid2.path("cid2_name").asText());
        assertEquals(1000, cid2.path("cid2").asInt());

        var invalidCid2 = MAPPER.createObjectNode();
        invalidCid2.putArray("interests").addObject()
                .put("cid1_name", "家装建材")
                .put("cid2_name", "西式主食/快餐")
                .put("interest", 50)
                .put("confidence", 30);
        assertThrows(IllegalArgumentException.class,
                () -> tools.selectCid2().execute(invalidCid2));
    }

    private static CategorySelectionTools tools() {
        var catalog = new CidTopSkuCatalog(
                new ClassPathResource("catalog/cid3_top10_sku_30d_small.csv"));
        return new CategorySelectionTools(MAPPER, catalog, cid2Names());
    }

    private static Cid2NameCatalog cid2Names() {
        return new Cid2NameCatalog(new org.springframework.core.io.ByteArrayResource("""
                cid1,cid1_name,cid2,cid2_name,n_cid3
                999,家装建材,1000,基建材料,1
                2000,外卖美食,2001,西式主食/快餐,1
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
