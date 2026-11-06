package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.domain.category.Cid2NameCatalog;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Cid2NameCatalogTests {

    @Test
    void resolvesNamesAndUsesTheBroaderDuplicateBranch() {
        var resource = new ByteArrayResource("""
                cid1,cid1_name,cid2,cid2_name,n_cid3
                1620,生活日用,53144,净化除味,3
                1620,生活日用,31636,净化除味,10
                670,电脑、办公,671,电脑整机,12
                """.getBytes(StandardCharsets.UTF_8));

        var catalog = new Cid2NameCatalog(resource);

        assertEquals(2, catalog.size());
        assertEquals(31636, catalog.require("生活日用", "净化除味").cid2());
        assertEquals(671, catalog.require("电脑、办公", "电脑整机").cid2());
    }

    @Test
    void packagesTheProvidedMappingFile() {
        var catalog = new Cid2NameCatalog(new ClassPathResource("catalog/cid2_name_map.csv"));

        assertEquals(671, catalog.require("电脑、办公", "电脑整机").cid2());
        assertEquals(1343, catalog.require("服饰内衣", "女装").cid2());
        assertEquals("手机", catalog.findByCid2(653).cid2Name());
        assertEquals("手机配件", catalog.findByCid2(830).cid2Name());
        assertEquals("智能设备", catalog.findByCid2(12345).cid2Name());
        assertEquals("摄影摄像", catalog.findByCid2(654).cid2Name());
    }
}
