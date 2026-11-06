package com.jd.diviner.ai.rec.odin.demo.app.application.jevcid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jd.diviner.ai.rec.odin.demo.app.agent.model.SkillModelClient;
import com.jd.diviner.ai.rec.odin.demo.app.agent.tool.SkillTool;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.BrandNameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.Cid2NameCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.domain.category.CidTopSkuCatalog;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.jev.OpenJevClient;
import com.jd.diviner.ai.rec.odin.demo.app.infrastructure.sniffer.SnifferClient;
import com.jd.diviner.ai.rec.odin.demo.app.tools.category.CategoryIntentTools;
import com.jd.diviner.ai.rec.odin.demo.app.tools.category.CategorySelectionTools;
import com.jd.diviner.ai.rec.odin.demo.app.tools.category.SnifferRecallTools;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/** Beans owned by the JEV/CID skill and its tools. */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:/skills/jev.properties")
public class JevCidConfiguration {

    @Bean
    public CidTopSkuCatalog cidTopSkuCatalog(ResourceLoader loader, Environment environment) {
        String location = environment.getProperty(
                "odin.cid.topn.location", "classpath:catalog/cid3_top10_sku_30d.csv");
        requireLocalResource(location, "类目热销表");
        return new CidTopSkuCatalog(loader.getResource(location));
    }

    @Bean
    public Cid2NameCatalog cid2NameCatalog(ResourceLoader loader, Environment environment) {
        String location = environment.getProperty(
                "odin.cid.names.location", "classpath:catalog/cid2_name_map.csv");
        requireLocalResource(location, "cid2 映射表");
        return new Cid2NameCatalog(loader.getResource(location));
    }

    @Bean
    public BrandNameCatalog brandNameCatalog(ResourceLoader loader, Environment environment) {
        String location = environment.getProperty(
                "odin.brand.names.location", "classpath:catalog/brand_code_name_map.csv");
        requireLocalResource(location, "品牌映射表");
        return new BrandNameCatalog(loader.getResource(location));
    }

    @Bean
    public OpenJevClient openJevClient(ObjectMapper mapper, Environment environment) {
        return new OpenJevClient(
                mapper,
                URI.create(environment.getProperty(
                        "odin.jev.endpoint", "https://openjev-laya.jd.com/v1/systemone")),
                environment.getProperty("odin.jev.model", "laya-1.0"),
                Duration.ofMillis(environment.getProperty(
                        "odin.jev.timeout-ms", Long.class, 60000L)));
    }

    @Bean
    public SessionInterestJudge sessionInterestJudge(ObjectMapper mapper, SkillModelClient client) {
        return new SessionInterestJudge(mapper, client);
    }

    @Bean
    public CategoryIntentTools categoryIntentTools(
            ObjectMapper mapper, OpenJevClient client, CidTopSkuCatalog catalog) {
        return new CategoryIntentTools(mapper, client, catalog);
    }

    @Bean
    public SkillTool inferCategoryWithJevTool(CategoryIntentTools tools) {
        return tools.inferCategory();
    }

    @Bean
    public SkillTool recallCategoryTopnTool(CategoryIntentTools tools) {
        return tools.recallTopn();
    }

    @Bean
    public SkillTool rankCandidatesWithJevTool(CategoryIntentTools tools) {
        return tools.rankCandidates();
    }

    @Bean
    public CategorySelectionTools categorySelectionTools(
            ObjectMapper mapper, CidTopSkuCatalog catalog, Cid2NameCatalog cid2Names) {
        return new CategorySelectionTools(mapper, catalog, cid2Names);
    }

    @Bean
    public SkillTool selectCid1Tool(CategorySelectionTools tools) {
        return tools.selectCid1();
    }

    @Bean
    public SkillTool selectCid2Tool(CategorySelectionTools tools) {
        return tools.selectCid2();
    }

    @Bean
    public SnifferClient snifferClient(
            ObjectMapper mapper, Environment environment, ResourceLoader loader) throws IOException {
        String location = environment.getProperty(
                "odin.sniffer.template", "classpath:skills/sniffer-request.json");
        requireLocalResource(location, "Sniffer 模板");
        try (var input = loader.getResource(location).getInputStream()) {
            return new SnifferClient(
                    mapper,
                    URI.create(environment.getProperty(
                            "odin.sniffer.endpoint", "http://diviner.jd.local/diviner")),
                    Duration.ofMillis(environment.getProperty(
                            "odin.sniffer.timeout-ms", Long.class, 45000L)),
                    mapper.readTree(input));
        }
    }

    @Bean
    public SnifferRecallTools snifferRecallTools(
            ObjectMapper mapper,
            SnifferClient client,
            BrandNameCatalog brands,
            OpenJevClient brandModel,
            Cid2NameCatalog cid2Names) {
        return new SnifferRecallTools(mapper, client, brands, brandModel, cid2Names);
    }

    @Bean
    public SkillTool i2iTool(SnifferRecallTools tools) {
        return tools.i2i();
    }

    @Bean
    public SkillTool recallCid2Tool(SnifferRecallTools tools) {
        return tools.recallCid2();
    }

    private static void requireLocalResource(String location, String name) {
        if (location == null || (!location.startsWith("classpath:") && !location.startsWith("file:"))) {
            throw new IllegalArgumentException(name + "仅支持 classpath: 或 file:");
        }
    }
}
