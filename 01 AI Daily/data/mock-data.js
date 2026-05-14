const AI_DIGEST_DATA = [
  {
    id: "yt-001",
    platform: "youtube",
    datetime: "2026-05-13T09:30:00",
    title: "GPT-5 发布后的 AI Agent 格局变化：从工具到自主执行",
    tags: ["GPT-5", "Agent", "OpenAI", "自主执行"],
    summary: "OpenAI 发布 GPT-5 后，AI Agent 的能力边界发生了根本性变化。本视频深度分析了 GPT-5 在多步骤任务执行上的突破，以及这对现有 Agent 框架的冲击。",
    core_content: [
      "GPT-5 在工具调用上的成功率从 GPT-4o 的 73% 提升到 91%，是 Agent 可用性的关键跃升",
      "新的「Deep Research」模式支持跨 30+ 个数据源自主检索，完成时间从数小时压缩到 3 分钟",
      "Agent 框架正在从「LLM + 工具」的拼接模式，转向模型原生的任务规划与自我纠错",
      "商业化路径：OpenAI 将 Agent 能力作为 API 增值服务，按任务执行次数计费"
    ],
    url: "https://www.youtube.com/watch?v=example001",
    original_url: "https://www.youtube.com/watch?v=example001"
  },
  {
    id: "yt-002",
    platform: "youtube",
    datetime: "2026-05-13T11:00:00",
    title: "Google DeepMind AlphaFold 3 最新进展：蛋白质设计进入工程化时代",
    tags: ["DeepMind", "AlphaFold", "生物AI", "蛋白质"],
    summary: "AlphaFold 3 在 2026 年更新中新增了反向设计能力，科学家现在可以输入目标功能描述，让 AI 直接生成对应结构的蛋白质序列。",
    core_content: [
      "AlphaFold 3 新增反向设计：输入功能描述 → 输出蛋白质结构，而非传统的序列预测结构",
      "已有 3 款基于 AF3 设计的候选药物进入 Phase I 临床试验，设计周期从 2 年压缩到 6 周",
      "开放 API 供学术机构免费使用，但商业用途需授权（Google 正在构建生物 AI 护城河）",
      "局限性：RNA 和膜蛋白的预测精度仍有明显差距"
    ],
    url: "https://www.youtube.com/watch?v=example002",
    original_url: "https://www.youtube.com/watch?v=example002"
  },
  {
    id: "x-001",
    platform: "x",
    datetime: "2026-05-13T08:15:00",
    title: "Sam Altman：AGI 定义争议与 OpenAI 内部路线图",
    tags: ["AGI", "OpenAI", "Sam Altman", "战略"],
    summary: "Sam Altman 在 X 上发布长推，首次公开讨论 OpenAI 内部对 AGI 的操作性定义，以及公司认为距离 AGI 还差的具体能力维度。",
    core_content: [
      "Altman 认为 AGI 的核心判断标准是「能否在不依赖人类反馈的情况下自主完成跨领域科学发现」",
      "OpenAI 内部认为当前模型缺少的关键能力：持久记忆、真实世界感知、长程规划一致性",
      "暗示 GPT-6 训练已启动，重点攻坚方向是推理效率而非参数规模",
      "引发社区热议：Yann LeCun 回怼称 AGI 定义本身就是 OpenAI 的营销话术"
    ],
    url: "https://x.com/sama/status/example001",
    original_url: "https://x.com/sama/status/example001"
  },
  {
    id: "x-002",
    platform: "x",
    datetime: "2026-05-13T14:22:00",
    title: "Andrej Karpathy 发布「Software 3.0」系列第二篇：LLM 作为操作系统",
    tags: ["Karpathy", "LLM OS", "Software 3.0", "架构"],
    summary: "Karpathy 将 LLM 类比为新时代的操作系统内核，上层运行的「应用」是各种 Agent 和工具，这一框架引发了开发者社区的广泛讨论和争议。",
    core_content: [
      "类比：LLM = CPU（核心执行单元），Context Window = RAM，外部存储 = 磁盘，工具调用 = 系统调用",
      "「Software 3.0」特征：用自然语言编程，模型的权重就是程序本身",
      "对现有软件工程实践的冲击：调试从看代码变成看 prompt，测试从单元测试变成行为评估",
      "争议点：该框架低估了 LLM 的不可靠性，类操作系统需要确定性，而 LLM 本质是概率性的"
    ],
    url: "https://x.com/karpathy/status/example002",
    original_url: "https://x.com/karpathy/status/example002"
  },
  {
    id: "pod-001",
    platform: "podcast",
    datetime: "2026-05-13T07:00:00",
    title: "Lex Fridman #450：与 Dario Amodei 深谈 Claude 4 与 AI 安全的真实边界",
    tags: ["Anthropic", "Claude", "AI安全", "Dario", "Lex Fridman"],
    summary: "3 小时长播客，Dario Amodei 首次深度讨论 Claude 4 的训练细节、Constitutional AI 的局限性，以及 Anthropic 对 AI 安全的最新内部认知。",
    core_content: [
      "Claude 4 训练中最大的挑战不是能力，而是「有益但不谄媚」——模型倾向于告诉用户想听的话",
      "Constitutional AI 2.0 引入了「模型互相批评」机制，让一个 Claude 检查另一个 Claude 的回复是否符合价值观",
      "Dario 坦承：目前没有任何技术能「证明」一个 AI 系统是安全的，只能通过大量测试降低风险",
      "对 AI 监管的态度：支持政府介入，但担忧监管捕获（被大公司利用监管壁垒阻挡竞争）"
    ],
    url: "https://lexfridman.com/dario-amodei-4",
    original_url: "https://podcasts.apple.com/example"
  },
  {
    id: "pod-002",
    platform: "podcast",
    datetime: "2026-05-12T06:00:00",
    title: "a16z AI Podcast：AI 基础设施投资的下一个五年",
    tags: ["a16z", "投资", "基础设施", "算力", "商业化"],
    summary: "a16z 合伙人 Martin Casado 和 Guido Appenzeller 讨论了 2026-2030 年 AI 基础设施投资的核心主题，以及为什么「应用层」的钱比「模型层」更好赚。",
    core_content: [
      "核心论点：基础模型的商品化趋势不可逆，差异化机会在垂直行业的 AI 应用层",
      "算力投资已达天花板前迹象：Nvidia H100 利用率在部分云厂商降至 60%，过度建设风险出现",
      "AI 应用层的护城河来源：专有数据、工作流深度集成、用户习惯迁移成本",
      "最看好的投资方向：AI + 法律、AI + 医疗诊断、AI + 企业知识管理"
    ],
    url: "https://a16z.com/podcast/example",
    original_url: "https://a16z.com/podcast/example"
  },
  {
    id: "dy-001",
    platform: "douyin",
    datetime: "2026-05-13T12:00:00",
    title: "字节豆包新功能实测：手机助手帮我完成了一次完整的旅行规划",
    tags: ["豆包", "字节跳动", "Agent", "实测"],
    summary: "UP 主实测豆包手机助手完成端到端旅行规划：从出发地/目的地/时间输入，到自动查询机票、订酒店、生成行程单，全程约 4 分钟，中间人工干预 2 次。",
    core_content: [
      "成功完成：机票查询 + 酒店比价 + 景点推荐 + 行程表生成，整体体验流畅",
      "失败环节：自动订票时因支付验证码需要人工介入，Agentic 能力在支付环节仍有断点",
      "与豆包网页版对比：手机助手的跨 App 调度能力是差异化亮点，网页版无法实现",
      "用户感受：有「被帮到了」的真实感，但对 AI 代理支付的安全感仍不足"
    ],
    url: "https://www.douyin.com/video/example001",
    original_url: "https://www.douyin.com/video/example001"
  },
  {
    id: "xhs-001",
    platform: "xiaohongshu",
    datetime: "2026-05-13T10:30:00",
    title: "用 Claude 做产品调研的完整工作流分享（附 Prompt 模板）",
    tags: ["Claude", "产品经理", "工作流", "Prompt"],
    summary: "一位产品经理分享了她用 Claude 完成竞品调研、用户访谈分析、PRD 撰写的完整工作流，附带 6 个可直接复用的 Prompt 模板。",
    core_content: [
      "竞品调研 Prompt：让 Claude 扮演「挑剔的用户」分别体验竞品，从用户视角输出优劣势",
      "访谈分析：将录音转写文本喂给 Claude，提取「用户真实痛点」vs「用户以为的痛点」",
      "PRD 撰写：用「先写用户故事，再让 Claude 反推技术需求」的顺序，比直接写 PRD 质量高",
      "最重要的经验：Claude 的输出质量 80% 取决于你给它的上下文信息量，而不是 Prompt 技巧"
    ],
    url: "https://www.xiaohongshu.com/explore/example001",
    original_url: "https://www.xiaohongshu.com/explore/example001"
  }
];
