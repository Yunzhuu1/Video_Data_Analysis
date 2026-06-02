# Agent 设计文档

## 概述

系统共 6 个 Agent + 2 个 Tool，由 CoordinatorAgent 编排。

```
流向: Schema → SQL → Validate(交叉验证) → RAG → parallel(Insight, Rec)
```

---

## 1. RouterAgent — 路径分类器

### 职责

判断用户问题是走简单路径还是复杂路径。

### 执行逻辑

```
isSimple(question):
  1. LISTING_KEYWORDS 匹配 → 返回 true（简单，<1ms）
  2. COMPLEX_KEYWORDS 匹配 → 返回 false（复杂，<1ms）
  3. 未命中 → cheap model 分类 → 返回结果（~2s）
```

### 输入/输出

```
输入:  question: String
输出:  boolean（true=简单路径）
```

### 设计要点

- 关键字短路优先，只有模糊 case 才调 LLM
- 简单路径命中时零 LLM 开销

---

## 2. SchemaAgent — 表结构裁剪

### 职责

根据用户问题，从完整 Schema 中筛选出相关表和字段，减少 SQLAgent 的搜索空间。

### 执行逻辑

```
identify(question):
  1. 调用 cheap model
  2. prompt: 给定 7 张表的紧凑描述 + 裁剪规则
  3. model 输出只包含相关表的裁剪后 Schema
```

### 输入/输出

```
输入:  question: String
输出:  schemaContext: String（裁剪后的表结构描述）
```

### 设计要点

- 输出格式为紧凑文本（非 JSON），减少 Token
- 只列相关表，省略无关表
- 用 cheap model，不需要强推理

---

## 3. SQLGenerationAgent — SQL 生成 + 执行

### 职责

根据用户问题和裁剪后的 Schema，生成正确的 MySQL SQL 并执行，出错时自动修正。

### 执行逻辑

```
execute(question, schemaContext, previousFeedback?):
  1. 调用 strong model（带 Tool: SqlExecutionTool + MetricQueryTool）
  2. model 自主决策：
     a. 涉及指标 → 先调 getMetricFormula
     b. 写 SQL → 调 executeSql
     c. 报错 → 分析错误 → 修正重试 ×3
     d. 自查 WHERE/JOIN 逻辑
  3. 返回查询结果
  4. 如果 previousFeedback 非空 → 注入校验反馈作为修正上下文
```

### 输入/输出

```
输入:  question: String
       schemaContext: String
       previousFeedback: String?（Execution Guidance 的校验反馈）
输出:  queryResult: String（TSV 格式数据）
```

### System Prompt 核心内容

```
SQL专家。按步骤执行：
1. 涉及指标→先getMetricFormula
2. 写SQL→executeSql
3. 报错→修正重试×3
4. 返回数据

规则：SELECT only。JSON用->>。时间直接比timestamp。
COALESCE防NULL。executeSql限100行，超限用GROUP BY+LIMIT。
表结构见下方上下文，勿臆测字段。

【自查】返回前检查SQL逻辑：
- 聚合查询是否遗漏了WHERE/event_type过滤？
- JOIN条件是否正确匹配了外键？
发现问题则用executeSql重新执行修正后的SQL。

【性能优化】聚合查询优先查 metric_daily 表。
```

---

## 4. RAGAgent — 评论检索 + 归因发现

### 职责

根据用户问题和 SQL 查询结果，从评论数据库中检索相关用户反馈，提取主题和情感倾向。

### 执行逻辑（四阶段 + 软反思）

```
analyze(question, queryResult):
  // 阶段1: Query Rewriting
  searchQuery = rewriteQuery(question, queryResult)
  // 用 cheap model 把指标问题转为体验关键词

  // 阶段2: ANN 检索 + Reranker
  candidates = vectorStore.similaritySearch(searchQuery, topK=10)
  topComments = rerank(candidates)
  // rerank: 用 cheap model 逐条打分 0-10，保留 >=5 的 top-5

  // 阶段3: 主题提取
  result.extractThemes(topComments)
  // cheap model 提取 3-5 个主题词 + 总结

  // 阶段4: Self-Reflection（软信号）
  result.confidence = measureConfidence(question, result)
  // confidence ∈ [0.0, 1.0]
  // 不拦截结果，只给 InsightAgent 参考

  return result
```

### 输入/输出

```
输入:  question: String
       queryResult: String
输出:  CommentResult {
         themes: List<String>,
         negativeRatio: double,
         representativeComments: List<String>,
         summary: String,
         confidence: double     // 软信号，0.0-1.0
       }
```

### System Prompt 核心内容

Rewrite:
```
指标→评论搜索关键词。体验问题(广告/卡顿/画质/内容)。
只输入关键词空格分隔，勿解释。
```

Rerank:
```
评论与问题相关度 0-10。10=直接解释数据变化原因。
问题:{question} 数据:{data}
评论:{comment}
只输出数字。
```

Reflection:
```
评论主题能否解释问题？能→true 否→false
问题:{question} 主题:{themes} 负面占比:{negativeRatio}
只输出true/false。
```

### 降级策略

- ANN 检索为空 → 返回空结果（confidence=0.0）
- Reranker 全部低分 → 返回空结果（confidence=0.0）
- LLM 调用异常 → confidence=0.5（容错：未知时给中等可信度）

---

## 5. InsightAgent — 趋势分析 + 归因报告

### 职责

根据 SQL 查询数据、RAG 评论证据、交叉验证数据，生成结构化的分析报告。

### 执行逻辑

```
analyze(question, queryResult, schemaContext, ragResult, crossValidation):
  1. 调用 strong model
  2. model 分析数据：
     a. 总结趋势
     b. 识别异常波动
     c. 如果有 RAG 评论 → 融入归因段落
     d. 如果有交叉验证数据 → 验证评论指控
     e. 生成图表配置（line/bar/pie）
  3. 输出 AnalysisReport（JSON 结构化）
```

### 输入/输出

```
输入:  question: String
       queryResult: String（SQL 数据）
       schemaContext: String
       ragResult: CommentResult（RAG 评论证据）
       crossValidation: String（播放跳出交叉验证数据）
输出:  AnalysisReport {
         summary: String,
         metrics: List<MetricPoint>,
         charts: List<ChartConfig>,
         recommendations: null（由 RecAgent 填充）
       }
```

### System Prompt 核心内容

```
数据分析师。生成结构化JSON报告。
总结发现→异常归因→生成图表配置(line/bar/pie)。
有【用户评论分析】时融入归因段落。confidence<0.5表示证据较弱，可选择性引用。
不含建议(另有专家)。不含图表外的多余文字。
```

---

## 6. RecommendationAgent — 运营建议

### 职责

根据 SQL 数据和 RAG 评论证据，生成可操作的运营建议。

### 执行逻辑

```
recommend(question, queryResult, schemaContext, ragResult):
  1. 调用 cheap model
  2. model 分析数据和评论
  3. 输出 1-3 条建议（JSON 数组）
```

### 输入/输出

```
输入:  question: String
       queryResult: String
       schemaContext: String
       ragResult: CommentResult?（RAG 上下文，可能为 null）
输出:  recommendations: List<String>
```

### 设计要点

- 与 InsightAgent 真并行，两者都持有 RAG 上下文
- 建议基于真实评论数据而非泛泛而谈
- 最多 3 条，每条一句话

---

## 7. CoordinatorAgent — 编排器

### 职责

编排所有 Agent 的执行顺序，处理数据依赖和异常容错。

### 管线拓扑

```
顺序:
  [1] SchemaAgent.identify(question)
      → schemaContext
  [2] SQLGenerationAgent.execute(question, schemaContext)
      → queryResult
  [3] Execution Guidance.validateResult(question, queryResult)
      → feedback（可能触发 SQLAgent 重执行）
  [4] Cross-validation.crossValidate(queryResult)
      → crossValidation
  [5] RAGAgent.analyze(question, queryResult)
      → ragResult

并行（互相独立，线程池并发）:
  [6a] InsightAgent.analyze(question, queryResult, schemaContext, ragResult, crossValidation)
       → AnalysisReport（含 summary/metrics/charts）
  [6b] RecommendationAgent.recommend(question, queryResult, schemaContext, ragResult)
       → List<String>

合并:
  report.setRecommendations(recs)
  return report
```

### 容错策略

| 支路 | 超时 | 失败兜底 |
|---|---|---|
| SchemaAgent | — | 抛出异常 |
| SQLAgent | — | 抛出异常（含重试 ×3）|
| Execution Guidance | — | 放行（默认 PASS）|
| RAGAgent | 30s | 空结果 + confidence=0 |
| InsightAgent | 60s | fallbackReport |
| RecAgent | 30s | 空列表 |

---

## 8. DataAnalysisAgent — 外观层

### 职责

统一对外接口，处理路由、缓存、对话记忆。

### 执行逻辑

```
chat(userId, message):
  → MessageChatMemoryAdvisor 注入历史
  → strong model + Tool（有记忆，流式输出）
  → MessageChatMemoryAdvisor 保存历史

analyze(userId, message, onProgress?, bypassCache?):
  1. 语义缓存检查（ANN + LLM Judge）
  2. 缓存命中 → 返回
  3. RouterAgent 判断路径
     a. 简单路径 → simpleAnalysisClient（cheap model + Tool）
     b. 复杂路径 → CoordinatorAgent
  4. 写回语义缓存
  5. 返回 AnalysisReport
```

### 输入/输出

```
chat:
  输入: userId, message
  输出: Flux<String>（SSE 流式）

analyze:
  输入: userId, message, bypassCache?
  输出: AnalysisReport（JSON）
```

---

## 9. Tools

### SqlExecutionTool

```
executeSql(sql):
  1. SELECT 正则校验
  2. SQL 结果缓存检查（Redis MD5，5 分钟 TTL）
  3. 规则检查（sql-rules.yml，可配置，不改代码）
     - 聚合必须过滤 event_type
     - JOIN 必须带 ON
     - GROUP BY 字段必须在 SELECT 中
     - 大表查询必须带 LIMIT
  4. EXPLAIN 编译校验 + 查询计划分析
  5. 熔断检查（连续 3 次超时）
  6. setQueryTimeout(15s) + setMaxRows(101)
  7. 执行 → 格式化 TSV
  8. 写入 SQL 结果缓存
  8. 异常 → 返回给 LLM 修正重试
```

### MetricQueryTool

```
getMetricFormula(metricName):
  SELECT formula FROM metric_def WHERE metric_name = ?
  → 返回公式字符串或"未找到该指标定义"
```
