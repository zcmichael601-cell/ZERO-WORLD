# RAG：Skill 的商品知识基础能力

## 当前职责

RAG（检索增强生成）先查询本地商品知识，再将命中的片段、来源和引用信息交给模型生成推荐理由和商品对比。它不是模型训练，不是用户记忆，也不代替实时库存/价格查询。

2026-10-30 清理后只保留 Skill 内的商品 RAG；独立 `rag.html`、`/api/rag/**` 与问答用 `RagService` 已删除。开发目录与扩展方式见 [开发指南](development.md)。

```text
catalog/jd-products.json（真实京东页面快照）
  → domain.product.JdProductCatalog.documents()
  → agent.rag.RagKnowledgeBase：读取、切分、Embedding、SimpleVectorStore
  → tools.product：按需求召回 / 按 SKU 深读
  → application.skill.SkillEngine：将片段送给当前 LLM
  → domain.recommendation.ProductComparisonValidator：SKU、证据、原文匹配校验
  → skills.html：推荐理由、商品对比、来源依据
```

## 启动与配置

- 唯一启动类 `AiRecOdinDemoApplication` 显式装配 `bootstrap.RagConfiguration`。
- 只注册 `productRagKnowledgeBase`，商品数据从 `odin.products.location` 读取，默认 `classpath:catalog/jd-products.json`。保留原有 1000 SKU，不加载旧演示商品资料。
- Embedding 地址、key、模型沿用 `profile/ai.properties` 中 `dong.spring.ai.joyai.embedding.*`；模型 key 不应写入文档或日志。
- `odin.rag.top-k` 默认 4、`odin.rag.similarity-threshold` 默认 0.5；商品工具可按具体场景选择检索数量。向量响应校验、有界重试见 `agent.rag.RagEmbeddingTransport`。
- `odin.rag.embedding.max-attempts` 默认 3；`odin.rag.embedding.retry-delay-millis` 默认 1000。永久错误不盲目重试，最终失败终止启动，不使用假向量。

`RagStartupLifecycle` **在 HTTP 监听前** 完成索引构建，结束后应用才正式启动。页面不再提供手动建索引按钮；进程内索引在每次启动时重建，未实现持久化向量服务。改商品数据后需要重启，不做运行期隐式热切换。

## 控制台日志

IDEA 当前进程的 Run / Debug Console 中查看 `agent_diag`，可按 `startupId` 关联：

1. `AGENT_LOGGING_READY`：诊断输出通道已初始化。
2. `SERVICE_STARTUP_LOADING` / `RAG_STARTUP_LOADING`：正在预加载商品库。
3. `RAG_DOCUMENT_LOAD_*`：资料读取和切分。
4. `RAG_VECTOR_BATCH_*` / `RAG_EMBEDDING_*`：向量请求批次、耗时、错误类别和重试。
5. `RAG_INDEX_PROGRESS`：已完成数量和进度。
6. `RAG_STARTUP_READY` / `RAG_STARTUP_COMPLETED`：索引完成。
7. `HTTP_SERVER_STARTED` / `SERVICE_READY`：端口已监听、服务正式就绪。

失败看 `SERVICE_STARTUP_FAILED`，携带 startupId、错误分类和时间排查向量网关。索引未就绪时网页打不开是启动门禁的预期行为，不能通过跳过加载来伪装服务正常。

## 业务约束

- `ProductKnowledgeTool` 返回同一版索引的原文证据；满血版可以读取多个候选 SKU 的详细片段。
- 比较结论必须绑定本商品的证据和原文，不能引用其他商品或把搜索标签当性能事实。
- 资料不足时明确缺口，不能为了“优劣对比”补造参数。自动修正有预算上限，不发布未通过校验的推荐。
- 页面快照有时间边界；实际成交价和库存需到京东核实。RAG 不会让旧价格变成实时报价。
- 旧假商品知识移至 `src/test/resources/knowledge`，仅用于确定性测试，不进入正式包。

## 测试

`RagTests` 覆盖索引、召回、快照与并发；`RagStartupTests` 覆盖启动顺序、HTTP 门禁和失败终止；`RagEmbeddingRecoveryTests` 覆盖网关响应校验与重试；`SkillRagTests` 与推荐契约测试覆盖工具到推荐校验的联动。测试向量不会替代真实运行的 Embedding 服务。
