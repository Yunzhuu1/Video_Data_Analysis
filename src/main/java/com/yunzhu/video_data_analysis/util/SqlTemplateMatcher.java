package com.yunzhu.video_data_analysis.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 模板匹配器。从 {@code sql-templates.yml} 加载预定义模板，
 * 根据用户问题的意图关键词匹配最合适的模板，填充参数后直接执行。
 * <p>
 * 不经过 LLM，零推理成本，毫秒级返回。
 */
@Component
public class SqlTemplateMatcher {

    private static final Logger log = LoggerFactory.getLogger(SqlTemplateMatcher.class);

    private final JdbcTemplate jdbcTemplate;
    private List<TemplateDef> templates = new ArrayList<>();

    /** 已知分类值，从 metric_daily 数据中提取 */
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("(美妆|美食|游戏)");
    /** 日期范围：10月1日~10月7日、国庆、活动期间等 */
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile("(国庆|活动期间|10月\\d{1,2}日)");

    public SqlTemplateMatcher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            InputStream is = getClass().getClassLoader().getResourceAsStream("sql-templates.yml");
            if (is == null) {
                log.warn("sql-templates.yml not found, template matching disabled");
                return;
            }
            TemplateFile tf = mapper.readValue(is, TemplateFile.class);
            this.templates = tf.templates != null ? tf.templates : List.of();
            log.info("SqlTemplateMatcher loaded {} templates", templates.size());
        } catch (Exception e) {
            log.warn("Failed to load sql-templates.yml: {}", e.getMessage());
        }
    }

    /**
     * 尝试匹配模板。命中时返回填充好参数的 SQL；未命中返回 null。
     */
    public String matchAndFill(String question) {
        if (templates.isEmpty()) return null;

        for (TemplateDef tpl : templates) {
            // 1. 意图关键词匹配
            boolean intentMatch = tpl.intent_match.stream()
                    .anyMatch(kw -> question.contains(kw));
            if (!intentMatch) continue;

            // 2. 提取参数
            String category = extractCategory(question);
            String metric = extractMetric(question, tpl.params.get("metric"));

            // 3. 填充 SQL
            String sql = tpl.sql;
            if (category != null) sql = sql.replace("{category}", category);
            if (metric != null) sql = sql.replace("{metric}", metric);

            // 简化时间范围
            sql = sql.replace("{time_filter}", "");

            // 4. 检查是否还有未填充的占位符
            if (sql.contains("{") && sql.contains("}")) continue;

            log.info("Template matched: {} | sql={}", tpl.id, sql);
            return sql;
        }

        return null;
    }

    /**
     * 执行模板 SQL 并返回结果。
     */
    public String execute(String sql) {
        if (sql == null) return null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            if (rows.isEmpty()) return "未找到相关数据";
            StringBuilder sb = new StringBuilder();
            sb.append(rows.size()).append(" rows\n");
            if (!rows.isEmpty()) {
                sb.append(String.join("|", rows.get(0).keySet())).append("\n");
            }
            for (Map<String, Object> row : rows) {
                sb.append(String.join("|", row.values().stream()
                        .map(v -> v == null ? "" : v.toString())
                        .toArray(String[]::new))).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null; // 模板执行失败，回退到 LLM
        }
    }

    private String extractCategory(String question) {
        Matcher m = CATEGORY_PATTERN.matcher(question);
        return m.find() ? m.group(1) : null;
    }

    private String extractMetric(String question, List<String> allowed) {
        if (allowed == null) return null;
        // 匹配关键词到指标名
        if (question.contains("播放量") || question.contains("播放次数")) return "total_plays";
        if (question.contains("播放时长")) return "total_play_duration";
        if (question.contains("点赞")) return "total_likes";
        if (question.contains("评论")) return "total_comments";
        if (allowed.contains("total_plays")) return "total_plays";
        return null;
    }

    /* ==================== YAML 绑定 ==================== */

    private static class TemplateFile {
        public List<TemplateDef> templates;
    }

    private static class TemplateDef {
        public String id;
        public String description;
        public String sql;
        public Map<String, List<String>> params;
        public List<String> intent_match;
    }
}
