# Multi-Agent 短视频数据分析系统

基于 Spring Boot + Spring AI 构建的 Multi-Agent 数据分析系统。从自然语言问询到结构化分析报告全自动生成，涵盖编排、RAG、缓存、交叉验证、可观测性的完整工程实现。

50000 条行为数据 + 500 条评论 + 10000 条播放明细，覆盖三层评测体系。

## 架构

```
用户 → DataAnalysisAgent
         ├─ RouterAgent → 简单路径（单 Agent + cheap model，<2s）
         │
         └─ CoordinatorAgent → 复杂路径（多层编排，~16s）
              ├─ ① SchemaAgent（cheap）— 裁剪表结构，缩小搜索空间
              ├─ ② SQLGenerationAgent（strong）— 写 SQL + EXPLAIN 校验 + 重试
              ├─ ③ Execution Guidance（cheap）— 基线校验，偏离 >50% 触发重执行
              ├─ ④ Cross-validation（Jdbc）— 查 play_detail 验证广告跳出率
              ├─ ⑤ RAGAgent（cheap）— 四阶段评论检索 + 软反思
              └─ ⑥ 并行扇出（真并行，RAG 上下文完整传递）
                   ├─ InsightAgent（strong）— 趋势分析 + 归因报告
                   └─ RecommendationAgent（cheap）— 基于 RAG 证据的运营建议
```

## 功能

| 功能 | 说明 |
|---|---|
| **Text2SQL 质量保障** | Schema 裁剪 → EXPLAIN 编译校验 + 查询计划分析 → 15s 超时熔断 → Execution Guidance 基线校验 → 预聚合表 |
| **SQL 执行防御** | SELECT 正则校验 → EXPLAIN 表/字段检查 → 全表扫描/临时表拦截 → setMaxRows(100) → 异常回传自动重试 ×3 |
| **交叉验证归因** | RAG 评论主题经 play_detail 跳出点数据二次验证，形成三层证据链闭环 |
| **RAG 增强检索** | Query Rewriting → ANN 向量检索 → LLM Reranker → Self-Reflection（软信号，confidence 0.0-1.0） |
| **语义缓存** | ANN 向量召回 + LLM Judge 意图判定，防"美妆类视频"与"美食类视频"相互污染 |
| **SQL 结果缓存** | Redis 存储 SQL→结果映射（MD5 精确匹配），5 分钟 TTL |
| **异构模型** | strong（SQL/洞察）+ cheap（路由/校验/检索/建议）+ 本地 Ollama Embedding（零外部依赖） |
| **对话记忆** | Redis 持久化 + MessageWindowChatMemory 滑动窗口（20 条），24h TTL |
| **全链路观测** | Token 追踪 + Agent 调用日志 + 运维看板 + SSE 进度事件 |
| **预聚合** | metric_daily 表将聚合查询从全表扫描降为单行读取 |

## 技术栈

Spring Boot 3.5.7 · Spring AI 1.1.6 · DeepSeek / OpenAI API · Ollama (nomic-embed-text) · Redis Stack · MySQL · ThreadPoolExecutor · CompletableFuture · SSE · ECharts

## 数据规模

| 表 | 行数 |
|---|---|
| user_behavior_fact | ~50000 |
| comment_content | 500 |
| play_detail | ~10000 |
| metric_daily | 93 |
| user_dim | 50 |
| content_dim | 6 |

## 快速开始

### 1. 启动依赖服务

```bash
# 本地 MySQL 手动启动，Redis + Ollama 用 Docker
docker compose up -d redis ollama

# 首次还需拉取 embedding 模型
docker exec -it 容器名 ollama pull nomic-embed-text
```

### 2. 配置环境变量

```bash
export AI_API_KEY=sk-your-key
export AI_BASE_URL=https://api.deepseek.com
export DB_PASSWORD=your-db-password
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

打开 `http://localhost:8080/dashboard.html`

### 4. 清缓存（调试时使用）

```bash
# 跳过语义缓存
curl "http://localhost:8080/api/agent/analyze?userId=demo&message=问题&nocache=true"
```

## 项目结构

```
src/main/java/com/yunzhu/video_data_analysis/
├── agent/                    # LLM Agent 层
│   ├── DataAnalysisAgent.java    # 外观 + 路由 + 双层缓存 + 记忆
│   ├── CoordinatorAgent.java     # Workflow 编排器 + 交叉验证
│   ├── RouterAgent.java          # 关键字短路 + cheap model 回退
│   ├── SchemaAgent.java          # 表结构裁剪
│   ├── SQLGenerationAgent.java   # SQL 生成 + 执行 + 自查
│   ├── RAGAgent.java             # 四阶段评论检索 + 软反思
│   ├── InsightAgent.java         # 趋势分析 + 归因报告 + 交叉验证引用
│   └── RecommendationAgent.java  # 运营建议（含 RAG 上下文）
├── config/
│   ├── AgentModelConfig.java     # strong/cheap/embedding 三模型 Bean
│   ├── ChatMemoryConfig.java     # Redis 持久化对话记忆
│   ├── ThreadPoolConfig.java     # 自定义线程池（core=cores×2）
│   └── VectorStoreConfig.java    # Redis VectorStore
├── controller/
│   └── AgentController.java      # 聊天 / 分析（同步+SSE）/ 运维 API
├── dto/
│   ├── AnalysisReport.java       # 结构化报告（含 metrics/charts/recs）
│   ├── CommentResult.java        # RAG 输出（含 confidence 软信号）
│   └── ...
├── service/
│   ├── SemanticCacheService.java # 语义缓存（ANN + LLM Judge 双层判定）
│   ├── SqlResultCache.java       # SQL 结果缓存（Redis MD5）
│   ├── TokenUsageService.java    # Token 成本追踪
│   └── RedisChatMemoryRepository.java
└── tool/
    ├── SqlExecutionTool.java     # SQL 执行（SELECT 校验 + EXPLAIN + 计划分析 + 超时 + 熔断）
    └── MetricQueryTool.java      # 指标公式查询
```

## API 接口

| 端点 | 用途 |
|---|---|
| `GET /api/agent/chat` | 流式对话（有历史记忆） |
| `GET /api/agent/analyze` | 结构化分析（同步 JSON） |
| `GET /api/agent/analyze-stream` | 结构化分析（SSE 进度事件） |
| `GET /api/agent/admin/stats` | 缓存 + Token 综合统计 |
| `GET /api/agent/admin/recent` | 最近 20 条调用记录 |
| `POST /api/agent/admin/cache/clear` | 清空语义缓存 |
| `POST /api/agent/admin/tokens/clear` | 清空 Token 记录 |

## 评测体系

三层评测，覆盖 14 个测试用例：

| 层级 | 评测内容 | 指标 | 当前基线 |
|---|---|---|---|
| Text2SQL | 10 个预设问题 | SQL 首次通过率 80%、响应时间 16s | 基线已建立 |
| RAG 检索 | 4 个归因问题 | 主题命中率 75%、精度@5 80% | 基线已建立 |
| 端到端报告 | 14 个用例 LLM-as-Judge 打分 | 综合评分 7.8/10 | 基线待运行 |

详见 [EVALUATION.md](EVALUATION.md)

## 测试查询

```bash
# 简单查询（Router 短路，<2s）
"有哪些分类的视频"
"美妆类视频有哪些"

# 指标查询（聚合 SQL）
"完播率是多少"
"美食类视频的总播放量"

# 趋势分析（metric_daily 预聚合）
"国庆期间每天的播放量趋势"

# 多分类对比
"美食类和游戏类完播率对比"

# 归因分析（全链路 + RAG + 交叉验证）
"为什么国庆后美食视频完播率下降了"
"活动期间和活动后的完播率对比"

# 压力测试（触发 EXPLAIN 拦截 + 熔断）
"查询所有数据"
```
