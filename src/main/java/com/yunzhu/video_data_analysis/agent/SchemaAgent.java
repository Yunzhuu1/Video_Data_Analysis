package com.yunzhu.video_data_analysis.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 识别用户问题相关的数据库表和字段，返回一个<b>裁剪后的schema</b>。
 * <p>
 * 表结构不硬编码在 prompt 中，而是运行时从 MySQL INFORMATION_SCHEMA 动态拉取。
 * 加表、加字段、改类型后自动感知，不需要改 prompt。
 * <p>
 * 使用廉价模型 (gpt-4o-mini)：schema裁剪是匹配任务，不是深度推理。
 */
@Component
public class SchemaAgent {

    private static final Logger log = LoggerFactory.getLogger(SchemaAgent.class);

    private final ChatClient chatClient;
    private final JdbcTemplate jdbcTemplate;

    private String cachedSchema;        // 动态加载的表结构描述
    private long lastRefreshMs = 0;
    private static final long CACHE_TTL_MS = 300_000; // 5 分钟

    public SchemaAgent(@Qualifier("cheapChatModel") ChatModel chatModel,
                       JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // system prompt 只保留裁剪指令，表结构在每次 identify 时动态注入
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是数据库Schema顾问。根据用户问题裁剪表结构，只输出相关表、字段和JOIN条件。")
                .build();
    }

    /**
     * 为用户问题识别相关的schema元素。
     *
     * @return 裁剪后的schema描述（纯文本，非JSON）
     */
    public String identify(String question) {
        if (cachedSchema == null) refreshSchema();
        refreshIfStale();
        log.info("SchemaAgent (cheap model) identifying schema");

        return chatClient.prompt()
                .user(u -> u.text("""
                        用户问题: {question}

                        数据库表结构:
                        {schema}

                        从以上表结构中，只输出与用户问题相关的表、字段和JOIN条件。
                        格式:
                        相关表-表名:字段
                        关联-JOIN
                        """)
                        .param("question", question)
                        .param("schema", cachedSchema))
                .call()
                .content();
    }

    /** 强制刷新 Schema 缓存，由 /admin 接口触发 */
    public synchronized void refresh() {
        log.info("SchemaAgent cache forced refresh");
        refreshSchema();
    }

    /* ==================== 动态 Schema 加载 ==================== */

    private synchronized void refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshMs > CACHE_TTL_MS) {
            refreshSchema();
        }
    }

    private synchronized void refreshSchema() {
        try {
            StringBuilder sb = new StringBuilder();

            // 1. 查所有表和字段
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT "
                    + "FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA = 'video_data_analysis' "
                    + "ORDER BY TABLE_NAME, ORDINAL_POSITION");

            // 按表名分组
            Map<String, List<Map<String, Object>>> grouped = columns.stream()
                    .collect(Collectors.groupingBy(m -> (String) m.get("TABLE_NAME")));

            for (var entry : grouped.entrySet()) {
                String table = entry.getKey();
                sb.append(table).append(":");
                for (var col : entry.getValue()) {
                    String colName = (String) col.get("COLUMN_NAME");
                    String colType = (String) col.get("COLUMN_TYPE");
                    sb.append(colName);

                    // 枚举类型：提取枚举值
                    if (colType != null && colType.startsWith("enum(")) {
                        String values = colType.substring(5, colType.length() - 1)
                                .replace("'", "");
                        sb.append("(").append(values).append(")");
                    }
                    // JSON 类型打标记
                    if (colType != null && colType.contains("json")) {
                        sb.append("(JSON)");
                    }
                    sb.append(",");
                }
                sb.setLength(sb.length() - 1); // 去掉末尾逗号
                sb.append("\n");
            }

            // 2. 查外键关系（JOIN 条件）
            List<Map<String, Object>> fks = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                    + "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = 'video_data_analysis' AND REFERENCED_TABLE_NAME IS NOT NULL");

            if (!fks.isEmpty()) {
                sb.append("关联:");
                for (var fk : fks) {
                    sb.append(fk.get("TABLE_NAME")).append(".").append(fk.get("COLUMN_NAME"))
                      .append("=").append(fk.get("REFERENCED_TABLE_NAME")).append(".").append(fk.get("REFERENCED_COLUMN_NAME"))
                      .append(" | ");
                }
                sb.setLength(sb.length() - 3);
            }

            cachedSchema = sb.toString();
            lastRefreshMs = System.currentTimeMillis();
            log.debug("Schema refreshed: {} tables, {} chars", grouped.size(), cachedSchema.length());
        } catch (Exception e) {
            log.error("Failed to load schema from INFORMATION_SCHEMA, using last known", e);
            if (cachedSchema == null) {
                cachedSchema = "数据库表结构加载失败";
            }
        }
    }

    private int countTables() {
        return cachedSchema == null ? 0 : (int) cachedSchema.lines().filter(l -> l.contains(":")).count();
    }
}
