package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CidTopSkuCatalogTests {

    @Test
    void loadsHierarchyAndCapsTopSkus() {
        var catalog = new CidTopSkuCatalog(new ClassPathResource("catalog/cid3_top10_sku_30d_small.csv"));
        assertFalse(catalog.cid1Names().isEmpty());
        assertTrue(catalog.cid1Names().contains("家装建材") || catalog.cid1Names().contains("外卖美食"));

        var skus = catalog.topSkus("外卖美食", "西式主食/快餐", "汉堡包/热狗", 10);
        assertFalse(skus.isEmpty());
        assertTrue(skus.size() <= CidTopSkuCatalog.MAX_SKUS);

        assertThrows(IllegalArgumentException.class,
                () -> catalog.topSkus("外卖美食", "西式主食/快餐", "汉堡包/热狗", 11));
    }

    @Test
    void filtersBlockedCategories() {
        var catalog = new CidTopSkuCatalog(new ClassPathResource("catalog/cid3_top10_sku_30d_small.csv"));
        assertEquals(null, catalog.find("奢侈品", "平台专用类目", "京东卡"));
    }

    @Test
    void intentCid1OmitsCategoriesUnsupportedByProductRecall() {
        var catalog = new CidTopSkuCatalog(new ClassPathResource("catalog/cid3_top10_sku_30d.csv"));
        var names = catalog.intentCid1Names();
        assertTrue(names.contains("生鲜"));
        for (String excluded : List.of(
                "到家服务", "卖家服务", "二手商品", "二手回收", "医药", "医疗保健")) {
            assertTrue(catalog.cid1Names().contains(excluded));
            assertFalse(names.contains(excluded));
            assertFalse(catalog.intentCategoryMenu().contains(excluded + "："));
        }
        assertTrue(catalog.intentCategoryMenu().contains("生鲜："));
    }

    @Test
    void mergeTopSkusRoundRobinsAcrossCategories() {
        var catalog = new CidTopSkuCatalog(new ClassPathResource("catalog/cid3_top10_sku_30d.csv"));
        var paths = List.of(
                new CidTopSkuCatalog.CategoryPath("运动户外", "滑雪运动", "滑雪服"),
                new CidTopSkuCatalog.CategoryPath("运动户外", "滑雪运动", "滑雪裤"),
                new CidTopSkuCatalog.CategoryPath("运动户外", "滑雪运动", "滑雪镜"));
        var merged = catalog.mergeTopSkus(paths, CidTopSkuCatalog.MAX_SKUS);
        assertEquals(10, merged.size());
        assertEquals(catalog.topSkus("运动户外", "滑雪运动", "滑雪服", 1).get(0).skuId(), merged.get(0).skuId());
        assertEquals(catalog.topSkus("运动户外", "滑雪运动", "滑雪裤", 1).get(0).skuId(), merged.get(1).skuId());
        assertEquals(catalog.topSkus("运动户外", "滑雪运动", "滑雪镜", 1).get(0).skuId(), merged.get(2).skuId());

        var capped = catalog.mergeTopSkus(paths, CidTopSkuCatalog.MAX_SKUS, 3);
        assertTrue(capped.size() <= 9);
        assertEquals(merged.subList(0, 3), capped.subList(0, 3));
    }
}
