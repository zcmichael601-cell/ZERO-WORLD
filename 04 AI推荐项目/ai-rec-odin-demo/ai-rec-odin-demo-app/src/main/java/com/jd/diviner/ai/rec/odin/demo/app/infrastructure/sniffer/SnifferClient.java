package com.jd.diviner.ai.rec.odin.demo.app.infrastructure.sniffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ExecutionLog;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Posts captured Diviner forms for explicit SKU i2i and constrained category recall. */
public final class SnifferClient {

    public interface Transport {
        byte[] postForm(URI uri, String formBody, Duration timeout);
    }

    public record SkuCard(
            String skuId,
            String name,
            String brand,
            String image,
            Double price,
            Integer cid1,
            Integer cid2,
            Integer cid3) {
    }

    public static final int MAX_SEEDS = 10;
    /** 手机两屏共 8 个，一次取齐，不再单独点刷新下一屏。 */
    public static final int MAX_RESULTS = 8;
    /** 图默认 Cut 20，提到 200 才能在本地滤完已曝光后再截。 */
    static final int RECALL_FETCH = 200;
    static final int CUT_AFTER_FILTER = 20;
    private static final Charset GBK = Charset.forName("GBK");

    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final Map<String, String> form;
    private final Transport transport;

    public SnifferClient(ObjectMapper mapper, URI endpoint, Duration timeout, JsonNode formTemplate) {
        this(mapper, endpoint, timeout, formTemplate, new HttpTransport());
    }

    public SnifferClient(ObjectMapper mapper, URI endpoint, Duration timeout, JsonNode formTemplate, Transport transport) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Sniffer 超时需在 (0, 60s]");
        }
        if (formTemplate == null || !formTemplate.isObject() || !formTemplate.has("p") || !formTemplate.has("skus")) {
            throw new IllegalArgumentException("Sniffer 请求模板无效");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        formTemplate.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith("_")) {
                return;
            }
            if (!entry.getValue().isTextual() && !entry.getValue().isNumber()) {
                throw new IllegalArgumentException("Sniffer 模板字段必须是文本: " + entry.getKey());
            }
            fields.put(entry.getKey(), entry.getValue().asText());
        });
        this.form = Map.copyOf(fields);
        this.timeout = timeout;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public List<SkuCard> i2i(List<String> skuIds) {
        return i2i(skuIds, Set.of());
    }

    public List<SkuCard> i2i(List<String> skuIds, Set<String> exposedSkuIds) {
        if (skuIds == null || skuIds.isEmpty() || skuIds.size() > MAX_SEEDS) {
            throw new IllegalArgumentException("i2i skus 需为 1~" + MAX_SEEDS + " 个");
        }
        Set<String> unique = new HashSet<>();
        for (String skuId : skuIds) {
            if (skuId == null || !skuId.matches("[1-9][0-9]{5,19}") || !unique.add(skuId)) {
                throw new IllegalArgumentException("i2i skus 必须是 1~" + MAX_SEEDS + " 个不重复的有效 SKU");
            }
        }
        Set<String> exposed = exposedSkuIds == null ? Set.of() : exposedSkuIds;
        int fetch = RECALL_FETCH;
        Map<String, String> fields = new LinkedHashMap<>(form);
        fields.put("skus", String.join(",", skuIds));
        fields.put("i2iTrigger", "");
        fields.put("cid3", "");
        fields.put("brand", "");
        fields.put("lim", String.valueOf(fetch));
        fields.put("reqId", requestId());
        attachFilterSkus(fields, exposed);
        if (form.containsKey("expParam")) {
            fields.put("expParam", i2iExpParam(form.get("expParam")));
        }
        List<SkuCard> cards = keepFresh(request(fields, fetch), exposed);
        cards = cut(cards, CUT_AFTER_FILTER);
        if (cards.size() > MAX_RESULTS) {
            return List.copyOf(cards.subList(0, MAX_RESULTS));
        }
        return cards;
    }

    public List<SkuCard> recallCid2(int cid2) {
        return recallCid2(cid2, null, null);
    }

    public List<SkuCard> recallCid2(int cid2, Integer cid3, String brand) {
        return recallCid2(cid2, cid3, brand, Set.of());
    }

    public List<SkuCard> recallCid2(int cid2, Integer cid3, String brand, Set<String> exposedSkuIds) {
        return recallCid2(cid2, cid3, brand, exposedSkuIds, null);
    }

    public List<SkuCard> recallCid2(int cid2, Integer cid3, String brand, Set<String> exposedSkuIds, String interestText) {
        if (cid2 <= 0) {
            throw new IllegalArgumentException("cid2 必须为正整数");
        }
        if (cid3 != null && cid3 <= 0) {
            throw new IllegalArgumentException("cid3 必须为正整数");
        }
        String expParam = form.get("expParam");
        if (expParam == null || expParam.isBlank()) {
            throw new IllegalStateException("Sniffer 请求模板缺少 expParam");
        }
        String brandId = numericBrand(brand);
        Map<String, String> fields = new LinkedHashMap<>(form);
        fields.put("cid2", String.valueOf(cid2));
        fields.put("cid3", cid3 == null ? "" : String.valueOf(cid3));
        fields.put("brand", brandId == null ? "" : brandId);
        // 不带 skus 只跳过 i2i，类目和品牌召回继续。
        fields.put("skus", "");
        fields.put("i2iTrigger", "");
        Set<String> exposed = exposedSkuIds == null ? Set.of() : exposedSkuIds;
        boolean diverseBrand = cid3 == null && brandId == null;
        int fetch = RECALL_FETCH;
        fields.put("lim", String.valueOf(fetch));
        fields.put("reqId", requestId());
        attachFilterSkus(fields, exposed);
        if (interestText != null && !interestText.isBlank()) {
            attachDiscoveryInterest(fields, interestText);
        }
        fields.put("expParam", categoryExpParam(expParam, cid2, cid3, brandId));
        List<SkuCard> cards = loadCategoryCards(fields, fetch, exposed, cid2, cid3, brandId, "primary");
        if (cards.isEmpty()) {
            fields.put("reqId", requestId());
            fields.put("expParam", categoryGrowthExpParam(expParam, cid2, false));
            cards = loadCategoryCards(fields, fetch, exposed, cid2, cid3, brandId, "fallback8227");
        }
        if (cards.isEmpty()) {
            fields.put("reqId", requestId());
            cards = loadCategoryCards(fields, fetch, exposed, cid2, cid3, brandId, "fallback8227Retry");
        }
        cards = cut(cards, CUT_AFTER_FILTER);
        if (!diverseBrand) {
            if (cards.size() > MAX_RESULTS) {
                return List.copyOf(cards.subList(0, MAX_RESULTS));
            }
            return cards;
        }
        Map<String, Integer> perBrand = new LinkedHashMap<>();
        List<SkuCard> kept = new ArrayList<>();
        for (SkuCard card : cards) {
            if (kept.size() == MAX_RESULTS) {
                break;
            }
            String key = card.brand() == null ? "" : card.brand().strip();
            int count = perBrand.getOrDefault(key, 0);
            if (count >= 3) {
                continue;
            }
            perBrand.put(key, count + 1);
            kept.add(card);
        }
        return List.copyOf(kept);
    }

    private List<SkuCard> loadCategoryCards(Map<String, String> fields, int fetch, Set<String> exposed,
            int cid2, Integer cid3, String brandId, String path) {
        Retrieved retrieved;
        long start = System.nanoTime();
        try {
            retrieved = retrieve(fields, fetch);
        } catch (IllegalStateException exception) {
            ExecutionLog.current().info("SNIFFER_RECALL_FAILED", "channel", "SKILL",
                    "path", path, "cid2", cid2, "error", exception.getMessage(),
                    "elapsedMs", ExecutionLog.elapsed(start));
            return List.of();
        }
        List<SkuCard> received = keepFresh(retrieved.cards(), exposed);
        List<SkuCard> kept = received.stream()
                .filter(card -> {
                    if (cid3 != null) {
                        return card.cid3() != null && card.cid3().equals(cid3);
                    }
                    if (brandId != null || "primary".equals(path)) {
                        return true;
                    }
                    return card.cid2() == null || card.cid2() == cid2;
                })
                .toList();
        SkuCard sample = kept.isEmpty() ? null : kept.get(0);
        ExecutionLog.current().info("SNIFFER_RECALL_RESULT", "channel", "SKILL",
                "path", path, "cid2", cid2, "rawItems", retrieved.rawItems(),
                "received", received.size(), "kept", kept.size(),
                "sampleCid2", sample == null || sample.cid2() == null ? 0 : sample.cid2(),
                "sampleName", sample == null ? "" : sample.name(),
                "elapsedMs", ExecutionLog.elapsed(start));
        return kept;
    }

    /** 丢掉已曝光 SKU，再按主图去重。容器未吃到 filterSkus 时，翻页靠这里出新商品。 */
    private static List<SkuCard> keepFresh(List<SkuCard> cards, Set<String> exposedSkuIds) {
        Set<String> usedImages = new HashSet<>();
        for (SkuCard card : cards) {
            if (!exposedSkuIds.contains(card.skuId())) {
                continue;
            }
            String key = imageFingerprint(card.image());
            if (!key.isEmpty()) {
                usedImages.add(key);
            }
        }
        List<SkuCard> kept = new ArrayList<>();
        for (SkuCard card : cards) {
            if (exposedSkuIds.contains(card.skuId())) {
                continue;
            }
            String key = imageFingerprint(card.image());
            if (!key.isEmpty() && !usedImages.add(key)) {
                continue;
            }
            kept.add(card);
        }
        return List.copyOf(kept);
    }

    static List<SkuCard> cut(List<SkuCard> cards, int limit) {
        if (cards.size() <= limit) {
            return cards;
        }
        return List.copyOf(cards.subList(0, limit));
    }

    /**
     * Sniffer / recproxy 不会把未知表单字段拷进 Broadway paramsMap。
     * discoveryInterest 同时写到 hi / matExtMsg，embedding 才能读到。
     */
    void attachDiscoveryInterest(Map<String, String> fields, String interestText) {
        fields.put("discoveryInterest", interestText);
        putJsonField(fields, "hi", "discoveryInterest", interestText);
        putJsonField(fields, "matExtMsg", "discoveryInterest", interestText);
        ExecutionLog.current().info("DISCOVERY_INTEREST", "channel", "SNIFFER", "text", interestText);
    }

    /**
     * 会话已曝光 SKU 写进 hi / matExtMsg，容器里 RequestSkuFiltersCommonFilter 在 Cut 前过滤。
     */
    void attachFilterSkus(Map<String, String> fields, Set<String> exposedSkuIds) {
        if (exposedSkuIds == null || exposedSkuIds.isEmpty()) {
            return;
        }
        String joined = exposedSkuIds.stream()
                .filter(id -> id != null && id.matches("[1-9][0-9]{5,19}"))
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (joined.isEmpty()) {
            return;
        }
        fields.put("filterSkus", joined);
        putJsonField(fields, "hi", "filterSkus", joined);
        putJsonField(fields, "matExtMsg", "filterSkus", joined);
        ExecutionLog.current().info("FILTER_SKUS", "channel", "SNIFFER", "count", exposedSkuIds.size());
    }

    private void putJsonField(Map<String, String> fields, String field, String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        String raw = fields.get(field);
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode parsed = mapper.readTree(raw);
                if (parsed instanceof ObjectNode object) {
                    node = object;
                }
            } catch (Exception ignored) {
                node = mapper.createObjectNode();
            }
        }
        node.put(key, value);
        fields.put(field, node.toString());
    }

    /** 数字品牌 ID 才作为请求入参；品牌名不进倒排。 */
    public static String numericBrand(String brand) {
        if (brand == null) {
            return null;
        }
        String text = brand.strip();
        return text.matches("[1-9]\\d{0,18}") ? text : null;
    }

    private List<SkuCard> request(Map<String, String> fields) {
        return request(fields, MAX_RESULTS);
    }

    private List<SkuCard> request(Map<String, String> fields, int cardLimit) {
        return retrieve(fields, cardLimit).cards();
    }

    private Retrieved retrieve(Map<String, String> fields, int cardLimit) {
        StringBuilder body = new StringBuilder();
        for (var field : fields.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        byte[] raw = transport.postForm(endpoint, body.toString(), timeout);
        if (raw == null || raw.length == 0 || raw.length > 8 * 1024 * 1024) {
            throw new IllegalStateException("Sniffer 响应为空或过大");
        }
        JsonNode response = parse(raw);
        if (!response.path("success").asBoolean(false)) {
            String message = response.path("error_msg").asText("").strip();
            throw new IllegalStateException(message.isEmpty() ? "Sniffer 未返回成功结果" : "Sniffer 失败: " + message);
        }
        JsonNode data = response.path("data");
        int rawItems = data.isArray() ? data.size() : 0;
        if (!data.isArray() || data.isEmpty()) {
            return new Retrieved(List.of(), rawItems);
        }
        List<SkuCard> cards = new ArrayList<>();
        Set<String> seenSkuIds = new HashSet<>();
        for (JsonNode item : data) {
            if (cards.size() >= cardLimit) {
                break;
            }
            String skuId = item.path("itemid").asText(item.path("sku").asText("")).strip();
            String name = item.path("t").asText("").strip();
            if (!skuId.matches("[1-9][0-9]{5,19}") || name.isEmpty() || !seenSkuIds.add(skuId)) {
                continue;
            }
            String brand = item.path("bn").asText("").strip();
            String image = item.path("img").asText("").strip();
            Double price = null;
            String jp = item.path("jp").asText("").strip();
            if (jp.matches("\\d+(\\.\\d+)?")) {
                double value = Double.parseDouble(jp);
                if (value > 0 && Double.isFinite(value)) {
                    price = value;
                }
            }
            cards.add(new SkuCard(
                    skuId,
                    name,
                    brand,
                    image,
                    price,
                    positiveInteger(item.path("c1")),
                    positiveInteger(item.path("c2")),
                    positiveInteger(item.path("c3"))));
        }
        return new Retrieved(cards.isEmpty() ? List.of() : List.copyOf(cards), rawItems);
    }

    private record Retrieved(List<SkuCard> cards, int rawItems) {
    }

    private static String requestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String i2iExpParam(String source) {
        Map<String, String> settings = parseExpParam(source);
        settings.put(
                "hotSkuRecallCenter.StandardRecallCenterProcessor.seedInfoConfigs",
                "[{seedInfoName=i2iTrigger,"
                        + "seedInfoBuilder=com.jd.rec.odin.processor.recallcenter.feature.RequestParamI2ITriggerBuilder,"
                        + "seedInfoProcessConfig={limit=200}}]");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.KVRecallProcessor1.processorEnable",
                "true");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.processorEnable",
                "false");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.tagPriority",
                "[1]");
        settings.put("skuKnn675CutByWeight.ResultCutByWeightProcessor.limit", String.valueOf(RECALL_FETCH));
        settings.put(
                "skuKnn675SameImgFilter.CommonFilterProcessor.filterConfig.RequestSkuFiltersCommonFilter.filterSwitch",
                "true");
        return serializeExpParam(settings);
    }

    static String cid2ExpParam(String source, int cid2) {
        return categoryExpParam(source, cid2, null);
    }

    /**
     * 有 cid3 或数字品牌 ID 时走 8081：cid3 检索 cb_cid3，品牌 ID 检索 cb_brand_id。
     * 两个都没有时打开 KNN 675，只吃 discoveryInterest 文本，不再做 cid2 正排过滤。
     */
    static String categoryExpParam(String source, int cid2, Integer cid3) {
        return categoryExpParam(source, cid2, cid3, null);
    }

    static String categoryExpParam(String source, int cid2, Integer cid3, String brandId) {
        if (cid3 == null && brandId == null) {
            return categoryGrowthExpParam(source, cid2, true);
        }
        return termWeightExpParam(source, cid3, brandId);
    }

    private static String termWeightExpParam(String source, Integer cid3, String brandId) {
        Map<String, String> settings = parseExpParam(source);
        StringBuilder seeds = new StringBuilder();
        StringBuilder nodes = new StringBuilder();
        StringBuilder features = new StringBuilder();
        if (cid3 != null) {
            appendSeed(seeds, "reqCid3", cid3, "request.cid3");
            appendNode(nodes, "cb_cid3", "reqCid3");
            appendFeature(features, "user_pref_term_weight_cid3", "cb_cid3", "reqCid3");
        }
        if (brandId != null) {
            appendSeed(seeds, "reqBrand", brandId, "request.brand");
            appendNode(nodes, "cb_brand_id", "reqBrand");
            appendFeature(features, "user_pref_term_weight_brand", "cb_brand_id", "reqBrand");
        }
        settings.put(
                "hotSkuRecallCenter.StandardRecallCenterProcessor.seedInfoConfigs",
                withOptionalSkuSeed(seeds.toString()));
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.KVRecallProcessor1.processorEnable",
                "true");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.processorEnable",
                "true");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8227.processorEnable",
                "false");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.tagPriority",
                "[1,8081]");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.basicConf",
                "{tokenId=common-retrieval,databaseName=darab_sku_recall_cb_term_weight_v3_byspu,recallTagId=8081,limit=200}");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.commonParams",
                "{recaller_timeout_1=16,recaller_segment_size_1=100}");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.filters",
                "[]");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.features",
                "[" + features + "]");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.retrieveNodes",
                "{operationType=OP_OR,subNodes=[" + nodes + "]}");
        return serializeExpParam(settings);
    }

    /** i2i 种子可选。没有 skus 时构建器返回空列表，不中断类目召回。 */
    private static String withOptionalSkuSeed(String categorySeeds) {
        String i2i = "{seedInfoName=i2iTrigger,"
                + "seedInfoBuilder=com.jd.rec.odin.processor.recallcenter.feature.RequestParamI2ITriggerBuilder,"
                + "seedInfoProcessConfig={limit=200}}";
        if (categorySeeds == null || categorySeeds.isBlank()) {
            return "[" + i2i + "]";
        }
        return "[" + i2i + "," + categorySeeds + "]";
    }

    private static void appendSeed(StringBuilder seeds, String name, Object key, String origin) {
        if (!seeds.isEmpty()) {
            seeds.append(',');
        }
        seeds.append("{seedInfoName=").append(name)
                .append(",seedInfoBuilder=com.jd.rec.odin.processor.recallcenter.feature.NcaCidTriggerBuilder,")
                .append("seedInfoProcessConfig={cid2List=[{key=").append(key)
                .append(",origin=").append(origin).append("}]}}");
    }

    private static String categoryGrowthExpParam(String source, int cid2, boolean knn) {
        Map<String, String> settings = parseExpParam(source);
        settings.put(
                "hotSkuRecallCenter.StandardRecallCenterProcessor.seedInfoConfigs",
                withOptionalSkuSeed("{seedInfoName=reqCid2,"
                        + "seedInfoBuilder=com.jd.rec.odin.processor.recallcenter.feature.NcaCidTriggerBuilder,"
                        + "seedInfoProcessConfig={cid2List=[{key=" + cid2 + ",origin=request.cid2}]}}"));
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.merger.SearcherRecallProcessor.tokenId",
                "recall-category-growth");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.merger.SearcherRecallProcessor.database",
                "category_growth_v1");
        settings.put(
                "knnSkuRecallCenter.StandardRecallCenterProcessor.processorSwitch",
                knn ? "true" : "false");
        if (knn) {
            // 675 只看三塔距离分，不走首页精排 hp_rank_90。图侧库存已改吃召回权重。
            settings.put("sku666603PsRanker.RankerProcessor.processorSwitch", "false");
            settings.put("sku666603PsUser.RankerUserProcessor.processorSwitch", "false");
            settings.put("skuKnn675CutByWeight.ResultCutByWeightProcessor.limit", String.valueOf(RECALL_FETCH));
            settings.put(
                    "skuKnn675SameImgFilter.CommonFilterProcessor.filterConfig.RequestSkuFiltersCommonFilter.filterSwitch",
                    "true");
        }
        settings.put(
                "knnSkuRecallCenter.StandardRecallCenterProcessor.seedInfoConfigs",
                "[{seedInfoName=reqCid2,"
                        + "seedInfoBuilder=com.jd.rec.odin.processor.recallcenter.feature.NcaCidTriggerBuilder,"
                        + "seedInfoProcessConfig={cid2List=[{key=" + cid2 + ",origin=request.cid2}]}}]");
        String knnFilters = "[{aliasName=darab_unified_sku_recall_hot_profile_filter,filterName=CommonProfileFilters,"
                + "structParams={forward_name=darab_unified_sku_recall_hot_profile,"
                + "filter_name_list=[sku_exposure_filter,spu_exposure_filter,black_spu_filter]}},"
                + "{aliasName=KnnBlackGenderFilter,filterName=CommonFieldMatchFilter,filterOut=true,switchOff=false,"
                + "structParams={field_name=gender,value_match={seedFetcher={fetcherName=ProfileSeedFetcher,"
                + "fetcherConf={profileType=gender,profileSource=user_gender_reversal,limit=2}}}}}]";
        settings.put(
                "index$knnSkuRecallCenter.StandardRecallCenterProcessor.searcher.KNNRecallProcessor675",
                "{basicConf={tokenId=common-retrieval,databaseName=discovery_interest_knn_scann_byspu,recallTagId=675,limit=200},"
                        + "diversities=[{aliasName=div_675cid3_config,diversityName=DiversityOp,structParams={field=cid3,limit=10}}],"
                        + "commonParams={using_embedding_weight=true,scann_reorder_coefficient=3,"
                        + "scann_prefiltering_coefficient=35,scann_search_leaves=50,scann_pre_filter=true},"
                        + "embeddingRecall={modelName=discovery_knn_v3_660000_recall_raw},"
                        + "processorEnable=true,filters=" + knnFilters + "}");
        settings.put(
                "index$knnSkuRecallCenter.StandardRecallCenterProcessor.searcher.KNNRecallProcessor675.basicConf",
                "{tokenId=common-retrieval,databaseName=discovery_interest_knn_scann_byspu,recallTagId=675,limit=200}");
        settings.put(
                "index$knnSkuRecallCenter.StandardRecallCenterProcessor.searcher.KNNRecallProcessor675.filters",
                knnFilters);
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.KVRecallProcessor1.processorEnable",
                "true");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8081.processorEnable",
                "false");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.tagPriority",
                knn ? "[1]" : "[1,8227]");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8227.processorEnable",
                knn ? "false" : "true");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8227.basicConf",
                "{tokenId=common-retrieval,databaseName=category_growth_v1,recallTagId=8227,limit=1000}");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8227.filters",
                "[]");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8227.retrieveNodes",
                "{operationType=OP_OR,subNodes=[{fieldName=cid2,values={seedFetcher="
                        + "{fetcherName=SeedInfoFetcher,fetcherConf={seedKey=reqCid2}}}}]}");
        settings.put(
                "index$hotSkuRecallCenter.StandardRecallCenterProcessor.searcher.RetrievalProcessor8227.diversities",
                "{diversities=[{aliasName=div_8227brand_config,diversityName=DiversityOp,structParams={field=brand_id,limit=3}}]}");
        settings.put(
                "hotSkuRecallCenter.StandardRecallCenterProcessor.indexDiversityConfigs",
                "[{diversityInstance={aliasName=diversity_config,diversityName=DiversityOp,paramNames=[DiversityOp]},"
                        + "diversityDataConfigs=[{paramName=DiversityOp,diversitySourceConfigs=[{seedSourceEnum=CUSTOM_PARAM,"
                        + "fixedSeedConfigs=[{seedType=\"field\",filterValue=\"brand_id\"},{seedType=\"limit\",filterValue=3}]}]}]}]");
        return serializeExpParam(settings);
    }

    private static void appendNode(StringBuilder nodes, String fieldName, String seedKey) {
        if (!nodes.isEmpty()) {
            nodes.append(',');
        }
        nodes.append("{fieldName=").append(fieldName)
                .append(",values={seedFetcher={fetcherName=SeedInfoFetcher,fetcherConf={seedKey=")
                .append(seedKey).append("}}}}");
    }

    private static void appendFeature(StringBuilder features, String alias, String field, String seedKey) {
        if (!features.isEmpty()) {
            features.append(',');
        }
        features.append("{aliasName=").append(alias)
                .append(",featureName=UserPrefTermWeightScoreFeature,structParams={weight=1.0,field=")
                .append(field)
                .append(",features={seedFetcher={fetcherName=SeedInfoFetcher,fetcherConf={seedKey=")
                .append(seedKey).append("}}}}}");
    }

    private static Map<String, String> parseExpParam(String source) {
        Map<String, String> settings = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : source.split("\\R")) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf("\"=\"");
            if (!line.startsWith("\"") || separator < 2 || !line.endsWith("\"")) {
                throw new IllegalArgumentException("Sniffer expParam 格式无效: " + lineNumber);
            }
            String key = line.substring(1, separator);
            String value = line.substring(separator + 3, line.length() - 1);
            if (settings.put(key, value) != null) {
                throw new IllegalArgumentException("Sniffer expParam 包含重复配置: " + key);
            }
        }
        return settings;
    }

    private static String serializeExpParam(Map<String, String> settings) {
        StringBuilder result = new StringBuilder();
        settings.forEach((key, value) -> result.append('"').append(key).append("\"=\"")
                .append(value).append("\"\n"));
        return result.toString();
    }

    private static Integer positiveInteger(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0) {
            return value.asInt();
        }
        String text = value.asText("");
        if (text.matches("[1-9][0-9]{0,8}")) {
            return Integer.parseInt(text);
        }
        return null;
    }

    public static String imageFingerprint(String image) {
        if (image == null || image.isBlank()) {
            return "";
        }
        String value = image.strip().toLowerCase(Locale.ROOT);
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int pathStart = value.indexOf('/', scheme + 3);
            value = pathStart >= 0 ? value.substring(pathStart + 1) : value.substring(scheme + 3);
        }
        int jfs = value.indexOf("jfs/");
        if (jfs >= 0) {
            value = value.substring(jfs);
        }
        value = value.replaceAll("!.*$", "");
        value = value.replaceAll("\\.avif$", "").replaceAll("\\.webp$", "");
        return value;
    }

    private JsonNode parse(byte[] raw) {
        try {
            return mapper.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception utf8) {
            try {
                return mapper.readTree(new String(raw, GBK));
            } catch (Exception gbk) {
                throw new IllegalStateException("Sniffer 响应不是 JSON", gbk);
            }
        }
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override
        public byte[] postForm(URI uri, String formBody, Duration timeout) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("User-Agent", "OdinSkillSniffer/1.0");
            String pin = formValue(formBody, "pin");
            if (!pin.isEmpty()) {
                builder.header("Cookie", "pin=" + URLEncoder.encode(pin, StandardCharsets.UTF_8));
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(formBody)).build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Sniffer HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Sniffer 请求被中断", exception);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Sniffer 请求失败", exception);
            }
        }

        private static String formValue(String formBody, String key) {
            String prefix = key + "=";
            for (String part : formBody.split("&")) {
                if (!part.startsWith(prefix)) {
                    continue;
                }
                return java.net.URLDecoder.decode(part.substring(prefix.length()), StandardCharsets.UTF_8);
            }
            return "";
        }
    }
}
