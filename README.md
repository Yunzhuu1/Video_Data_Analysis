# Multi-Agent 短视频数据分析系统

基于 Spring Boot + Spring AI 构建的 Multi-Agent 数据分析系统。从自然语言问询到结构化分析报告全自动生成，涵盖编排、模板匹配、RAG、交叉验证、可观测性的完整工程实现。

50000 条行为数据 + 500 条评论 + 10000 条播放明细，覆盖三层评测体系。

## 架构

```
用户 → DataAnalysisAgent
         ├─ SQL Template Matcher（新增：高频查询走模板，零 LLM）
         │    匹配 → 参数填充 → 执行 → 返回（<10ms, 100%正确）
         │    不匹配 → 继续
         │
         ├─ RouterAgent → 简单路径（单 Agent + cheap model，<2s）
         │
         └─ CoordinatorAgent → 复杂路径（多层编排，~16s）
              ├─ ① SchemaAgent（INFORMATION_SCHEMA 动态拉取）
              ├─ ② SQLGenerationAgent（SQL 方言自动适配）
              ├─ ③ Self-Correction（基于 SQL 原文 + 数据的精准修正）
              ├─ ④ RAGAgent → Cross-validation（RAG 主题二次验证）
              ├─ ⑤ 并行扇出  
              │      ├─ InsightAgent（strong）— 归因报告
              │      └─ RecAgent（cheap）— 建议（有 RAG 上下文）
              ├─ ⑥ 合并 → report.setRecommendations(recs)
              └─ ⑦ DBQA（cheap）— 报告质量检查 + ReAct 修正（1 轮）
```

## 功能

| 功能 | 说明 |
|---|---|
| **SQL 模板匹配** | 高频查询预写模板，零 LLM 调用，毫秒级返回，100% 正确。匹配不中才走 LLM |
| **Text2SQL 质量保障** | Schema 动态裁剪 → SQL Parser 语法树校验 → 规则检查 → EXPLAIN 编译+计划分析 → 15s 超时熔断 → Self-Correction 数据偏差修正 → DBQA 报告验证 |
| **SQL 执行防御** | SELECT 正则 + JSqlParser 语法树双重校验 → sql-rules.yml 可配置规则检查 → EXPLAIN 编译校验 → 全表扫描/临时表拦截 → setQueryTimeout(15s) + setMaxRows(101) → 3 次异常重试 |
| **SQL 方言动态适配** | 启动时自动检测数据库类型（MySQL/PostgreSQL），动态生成对应 SQL 语法规则，不硬编码方言 |
| **Self-Correction** | cheap model 同时看 SQL 原文 + 数据结果 + 表结构，给出具体到"哪行 SQL 写错了"的修正建议，不是猜测 PASS/FAIL |
| **DBQA 报告验证** | 报告生成后由第二个 LLM 检查完整性，发现缺口触发 ReAct 循环（补充查询 → 局部修正 → 复检） |
| **交叉验证归因** | RAG 评论主题经 play_detail 跳出点数据二次验证，形成三层证据链闭环 |
| **RAG 增强检索** | Query Rewriting → ANN → LLM Reranker → Self-Reflection（软信号 confidence 0.0-1.0） |
| **语义缓存** | ANN 召回 + LLM Judge 意图判定，防语义碰撞 |
| **SQL 结果缓存** | Redis（MD5 精确匹配），5 分钟 TTL |
| **异构模型** | strong（SQL/洞察）+ cheap（路由/校验/检索/建议）+ 本地 Ollama Embedding |
| **对话记忆** | Redis + MessageWindowChatMemory（20 条），24h TTL |
| **全链路观测** | Token 追踪 + Agent 日志 + 运维看板 + SSE 进度 + 慢查询持久化 + 周报汇总 |
| **慢查询监控** | EXPLAIN 检测到慢查询时自动入库，每周汇总 TOP-N 查询模式 |
| **预聚合** | metric_daily 将聚合查询从全表扫描降为单行读取 |

## 技术栈

Spring Boot 3.5.7 · Spring AI 1.1.6 · DeepSeek / OpenAI API · Ollama (nomic-embed-text) · Redis Stack · MySQL · JSqlParser · ThreadPoolExecutor · CompletableFuture · SSE · ECharts

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
docker compose up -d redis ollama
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

### 4. 管理接口

```bash
# 跳过缓存调试
curl "http://localhost:8080/api/agent/analyze?userId=demo&message=问题&nocache=true"

# 手动刷新 Schema（DDL 变更后调用）
curl -X POST "http://localhost:8080/api/agent/admin/schema/refresh"
```

## 项目结构

```
src/main/java/com/yunzhu/video_data_analysis/
├── agent/
│   ├── DataAnalysisAgent.java      # 外观 + 模板匹配 + 路由 + 双层缓存
│   ├── CoordinatorAgent.java       # 编排器 + Self-Correction + DBQA + 交叉验证
│   ├── RouterAgent.java            # 关键字短路 + cheap model
│   ├── SchemaAgent.java            # INFORMATION_SCHEMA 动态拉取 + 裁剪
│   ├── SQLGenerationAgent.java     # SQL 生成（方言动态适配）
│   ├── RAGAgent.java               # 四阶段评论检索 + 软反思
│   ├── InsightAgent.java           # 归因报告
│   ├── RecommendationAgent.java    # 运营建议（带 RAG 上下文）
│   └── DbqaAgent.java              # 报告质量验证 + ReAct 修正
├── config/
│   ├── AgentModelConfig.java       # 三模型 Bean
│   ├── ChatMemoryConfig.java       # Redis 记忆
│   ├── ThreadPoolConfig.java       # 自定义线程池
│   └── VectorStoreConfig.java      # Redis 向量存储
├── controller/
│   └── AgentController.java        # 聊天 / 分析 / 运维 API
├── dto/
│   ├── AnalysisReport.java, CommentResult.java, ...
├── service/
│   ├── SemanticCacheService.java   # 语义缓存（ANN + LLM Judge）
│   ├── SqlResultCache.java         # SQL 结果缓存
│   ├── TokenUsageService.java      # Token 追踪
│   ├── SlowQueryService.java       # 慢查询持久化 + 周报
│   ├── SqlDialectService.java      # 数据库方言检测
│   └── RedisChatMemoryRepository.java
├── tool/
│   ├── SqlExecutionTool.java       # SQL 执行（Parser + EXPLAIN + 超时 + 熔断）
│   ├── MetricQueryTool.java        # 指标公式查询
│   └── SqlRulesChecker.java        # YAML 配置的 SQL 逻辑规则
└── util/
    ├── SqlParserValidator.java     # JSqlParser 语法树校验
    └── SqlTemplateMatcher.java     # SQL 模板匹配器
```

## API 接口

| 端点 | 用途 |
|---|---|
| `GET /api/agent/chat` | 流式对话（有历史记忆） |
| `GET /api/agent/analyze` | 结构化分析（同步 JSON） |
| `GET /api/agent/analyze-stream` | 结构化分析（SSE 进度事件） |
| `GET /api/agent/admin/stats` | 缓存 + Token 统计 |
| `GET /api/agent/admin/recent` | 最近调用记录 |
| `POST /api/agent/admin/cache/clear` | 清空语义缓存 |
| `POST /api/agent/admin/tokens/clear` | 清空 Token 记录 |
| `POST /api/agent/admin/schema/refresh` | 手动刷新 Schema 缓存 |

## 评测体系

三层评测，覆盖 14 个测试用例，详见 [EVALUATION.md](EVALUATION.md)

| 层级 | 评测内容 | 指标 | 基线 |
|---|---|---|---|
| Text2SQL | 10 个预设问题 | 首次通过率 80%、响应 16s | 已建立 |
| RAG 检索 | 4 个归因问题 | 主题命中率 75%、精度@5 80% | 已建立 |
| 端到端报告 | 14 个用例 LLM-as-Judge | 综合评分 7.8/10 | 待运行 |

## 测试查询

```bash
# SQL 模板匹配（零 LLM，毫秒级）
"各分类播放量"
"完播率趋势"

# 简单查询
"有哪些分类的视频"
"美妆类视频有哪些"

# 指标查询
"完播率是多少"
"美食类视频的总播放量"

# 归因分析（全链路 + RAG + 交叉验证 + DBQA）
"为什么国庆后美食视频完播率下降了"

# 压力测试（触发 EXPLAIN 拦截）
"查询所有数据"
```
