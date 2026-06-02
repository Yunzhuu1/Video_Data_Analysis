package com.yunzhu.video_data_analysis.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL 逻辑规则检查器。基于外部配置的规则文件对 SQL 做静态分析，
 * 在 EXPLAIN 之后、执行之前拦截常见的逻辑错误。
 * <p>
 * 规则定义在 {@code sql-rules.yml} 中，新增表或字段时只需修改配置文件，
 * 不需要改动 Java 代码。
 */
@Component
public class SqlRulesChecker {

    private static final Logger log = LoggerFactory.getLogger(SqlRulesChecker.class);

    private final List<SqlRule> rules;

    public SqlRulesChecker(SqlRuleConfig config) {
        this.rules = config != null && config.getRules() != null
                ? config.getRules()
                : List.of();
        log.info("SqlRulesChecker initialized with {} rules", rules.size());
    }

    /**
     * 对 SQL 执行全部规则检查。
     *
     * @param sql 待检查的 SQL
     * @return 规则检查不通过则返回警告信息，全部通过返回 null
     */
    public String check(String sql) {
        if (rules.isEmpty()) return null;

        String upper = sql.toUpperCase();
        List<String> warnings = new ArrayList<>();

        for (SqlRule rule : rules) {
            String failReason = evaluate(rule, upper, sql);
            if (failReason != null) {
                warnings.add(failReason);
            }
        }

        if (warnings.isEmpty()) return null;

        String msg = "SQL 逻辑检查发现 " + warnings.size() + " 个问题:\n"
                + String.join("\n", warnings)
                + "\n请修正 SQL 后重新执行。";
        log.warn("SQL rule check failed: {}", msg);
        return msg;
    }

    private String evaluate(SqlRule rule, String upper, String original) {
        boolean hasAggregation = containsAny(upper, "SUM(", "AVG(", "COUNT(", "MAX(", "MIN(");
        boolean hasEventTypeFilter = containsAny(upper, "EVENT_TYPE", "event_type");
        boolean hasJoin = upper.contains("JOIN");
        boolean hasOn = upper.contains(" ON ");
        boolean hasGroupBy = upper.contains("GROUP BY");
        boolean hasLimit = upper.contains("LIMIT");
        boolean hasTableScan = containsAny(upper, "FROM USER_BEHAVIOR_FACT", "FROM PLAY_DETAIL");
        boolean hasContentTable = containsAny(upper, "CONTENT_DIM", "content_dim");
        boolean hasJoinCreator = containsAny(upper, "CREATOR_DIM", "creator_dim");

        // 检查表范围：如果规则指定了 tables，当前 SQL 必须涉及这些表才触发
        if (rule.getTables() != null && !rule.getTables().isEmpty()) {
            boolean tableMatched = rule.getTables().stream().anyMatch(t -> upper.contains(t.toUpperCase()));
            if (!tableMatched) return null;
        }

        return switch (rule.getCheck()) {
            case "aggregation AND NOT event_type_filter" ->
                hasAggregation && !hasEventTypeFilter
                    ? "▶ 聚合查询(" + rule.getName() + "): SUM/AVG/COUNT 可能遗漏了 event_type 过滤，请检查 WHERE 条件"
                    : null;

            case "join AND NOT on_clause" ->
                hasJoin && !hasOn
                    ? "▶ JOIN 条件缺失(" + rule.getName() + "): JOIN 语句必须带 ON 条件"
                    : null;

            case "group_by AND NOT group_by_in_select" -> {
                if (!hasGroupBy) yield null;
                // 提取 GROUP BY 后面的字段，检查是否在 SELECT 中
                int gbIdx = upper.indexOf("GROUP BY");
                String afterGroupBy = upper.substring(gbIdx + 9).trim();
                String gbField = afterGroupBy.split("[,\\s)]")[0].trim();
                if (!upper.contains(gbField)) {
                    yield "▶ GROUP BY 字段不匹配(" + rule.getName() + "): GROUP BY 的字段必须在 SELECT 列表中";
                }
                yield null;
            }

            case "table_scan AND NOT limit" ->
                hasTableScan && !hasLimit
                    ? "▶ 缺少 LIMIT(" + rule.getName() + "): 大数据表查询必须带 LIMIT 限制"
                    : null;

            case "table_content AND NOT join_creator" ->
                hasContentTable && !hasJoinCreator
                    ? "▶ 可能的 JOIN 遗漏(" + rule.getName() + "): 查询 content_dim 时如需创作者信息请 JOIN creator_dim"
                    : null;

            default -> null;
        };
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /** 从 YAML 反序列化的规则结构 */
    public static class SqlRule {
        private String name;
        private String description;
        private String check;
        private List<String> tables;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCheck() { return check; }
        public void setCheck(String check) { this.check = check; }
        public List<String> getTables() { return tables; }
        public void setTables(List<String> tables) { this.tables = tables; }
    }

    /** Spring Boot 配置绑定：读取 sql-rules.yml 中的 rules 列表 */
    @Configuration
    @ConfigurationProperties(prefix = "sql-rules")
    public static class SqlRuleConfig {
        private List<SqlRule> rules = new ArrayList<>();

        public List<SqlRule> getRules() { return rules; }
        public void setRules(List<SqlRule> rules) { this.rules = rules; }
    }
}
