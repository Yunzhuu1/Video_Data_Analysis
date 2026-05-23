# Multi-Agent 短视频数据分析系统

基于 Spring Boot + Spring AI 构建的 Multi-Agent 数据分析系统。从自然语言问询到结构化分析报告全自动生成，涵盖编排、RAG、缓存、可观测性的完整工程实现。

## 架构

```
用户 → DataAnalysisAgent
         ├─ RouterAgent → 简单路径（单 Agent + cheap model）
         └─ CoordinatorAgent → 复杂路径
              ├─ SchemaAgent（裁剪表结构）
              ├─ SQLGenerationAgent（SQL 生成 + 执行 + 重试）
              ├─ Execution Guidance（校验数据合理性）
              └─ 并行扇出
                   ├─ RAGAgent（四阶段评论检索）
                   ├─ InsightAgent（趋势分析 → 结构化报告）
                   └─ RecommendationAgent（运营建议）
```

## 功能

| 功能 | 说明 |
|---|---|
| Text2SQL 质量保障 | Schema 裁剪 → EXPLAIN 编译校验 → 查询计划分析 → 执行超时熔断 → Execution Guidance 基线校验 |
| RAG 增强归因 | Query Rewriting → Milvus/Redis ANN → LLM Reranker → Self-Reflection |
| 语义缓存 | embedding ANN + LLM Judge 意图匹配，双重判定 |
| SQL 结果缓存 | Redis 存储 SQL→结果映射，5 分钟 TTL |
| 异构模型 | strong model（gpt-4o/DeepSeek-chat）+ cheap model（gpt-4o-mini）+ 本地 embedding（Ollama） |
| 对话记忆 | Redis 持久化 + MessageWindowChatMemory 滑动窗口（20 条） |
| 可观测性 | Token 追踪 + Agent 调用日志 + 运维看板 |
| 预聚合 | metric_daily 表将聚合查询从全表扫描降为单行读取 |

## 技术栈

Spring Boot 3.5.7 · Spring AI 1.1.6 · OpenAI / DeepSeek API · Ollama (nomic-embed-text) · Redis Stack · MySQL · ThreadPoolExecutor · CompletableFuture · SSE · ECharts

## 快速开始

### 1. 启动依赖服务

```bash
docker compose up -d
```

### 2. 配置环境变量

```bash
cp env.example .env
# 修改 .env 中的 AI_API_KEY
```

### 3. 初始化数据库

```bash
# 连接 MySQL 执行 src/main/resources/schema.sql
# 应用启动时会自动注入测试数据
```

### 4. 启动应用

```bash
export AI_API_KEY=sk-your-key
mvn spring-boot:run
```

打开 `http://localhost:8080/dashboard.html`

## 项目结构

```
src/main/java/com/yunzhu/video_data_analysis/
├── agent/                    # LLM Agent 层
│   ├── DataAnalysisAgent.java    # 外观 + 路由 + 缓存
│   ├── CoordinatorAgent.java     # 编排器（串行→并行→合并）
│   ├── RouterAgent.java          # 简单/复杂路径分类
│   ├── SchemaAgent.java          # 表结构裁剪
│   ├── SQLGenerationAgent.java   # SQL 生成 + 执行
│   ├── RAGAgent.java             # 评论检索四阶段
│   ├── InsightAgent.java         # 趋势分析 + 报告生成
│   └── RecommendationAgent.java  # 运营建议
├── config/
│   ├── AgentModelConfig.java     # strong/cheap/embedding 三模型 Bean
│   ├── ChatMemoryConfig.java     # Redis 持久化对话记忆
│   ├── ThreadPoolConfig.java     # 自定义线程池
│   └── VectorStoreConfig.java    # 向量存储（Redis/Milvus）
├── controller/
│   └── AgentController.java      # 聊天 / 分析 / 运维 API
├── dto/                          # 结构化报告 POJO
├── service/
│   ├── SemanticCacheService.java # 语义缓存（ANN + LLM Judge）
│   ├── SqlResultCache.java       # SQL 结果缓存
│   ├── TokenUsageService.java    # Token 成本追踪
│   └── RedisChatMemoryRepository.java
└── tool/
    ├── SqlExecutionTool.java     # SQL 执行（SELECT 校验 + EXPLAIN + 超时 + 熔断）
    └── MetricQueryTool.java      # 指标公式查询
```

## API 接口

| 端点 | 用途 |
|---|---|
| `GET /api/agent/chat` | 流式对话 |
| `GET /api/agent/analyze` | 结构化分析 |
| `GET /api/agent/analyze-stream` | 结构化分析（SSE 进度） |
| `GET /api/agent/admin/stats` | 运维统计 |
| `GET /api/agent/admin/recent` | 最近调用记录 |
| `POST /api/agent/admin/cache/clear` | 清空语义缓存 |
| `POST /api/agent/admin/tokens/clear` | 清空 Token 记录 |

## 测试查询

```bash
# 基础查询（简单路径）
"有哪些分类的视频"
"美妆类视频有哪些"

# 指标查询
"完播率是多少"
"美食类视频的总播放量"

# 归因分析（全链路）
"为什么国庆后美食视频完播率下降了"
"活动期间和活动后的完播率对比"

# 压力测试
"查询所有数据"  # 会触发 EXPLAIN 全表扫描拦截
```
