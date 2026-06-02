package com.yunzhu.video_data_analysis.tool;

import com.yunzhu.video_data_analysis.service.SlowQueryService;
import com.yunzhu.video_data_analysis.service.SqlResultCache;
import com.yunzhu.video_data_analysis.util.SqlParserValidator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用于对 video_data_analysis 数据库执行SQL SELECT查询的工具。
 * AI模型使用此工具查询数据进行分析。
 * <p>
 * 安全性：只允许SELECT语句。所有其他SQL操作都被拒绝。
 * 行限制通过 {@link java.sql.Statement#setMaxRows(int)} 在JDBC驱动级别强制执行。
 * 错误以字符串形式返回给AI模型，以便它可以自我修正。
 */
@Component
public class SqlExecutionTool {

    private static final int MAX_ROWS = 100;
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    /** 最近一次被执行的 SQL（供外部读取验证） */
    private static String lastExecutedSql = "";

    /** 获取最近一次被执行的 SQL 文本，用于 Execution Guidance 校验 */
    public static String getLastExecutedSql() {
        return lastExecutedSql;
    }
    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures = new java.util.concurrent.atomic.AtomicInteger(0);

    /** 匹配以SELECT开头的SQL的正则表达式（忽略前导空格/注释）。 */
    private static final Pattern SELECT_PATTERN =
            Pattern.compile("^\\s*(--.*\\n)*\\s*SELECT\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 慢查询独立日志，便于后期分析 SQL 性能模式 */
    private static final org.slf4j.Logger slowLog = org.slf4j.LoggerFactory.getLogger("slow-query");

    private final JdbcTemplate jdbcTemplate;
    private final SqlResultCache sqlResultCache;
    private final SqlRulesChecker sqlRulesChecker;
    private final SqlParserValidator sqlParserValidator;
    private final SlowQueryService slowQueryService;

    public SqlExecutionTool(JdbcTemplate jdbcTemplate, SqlResultCache sqlResultCache,
                            SqlRulesChecker sqlRulesChecker, SqlParserValidator sqlParserValidator,
                            SlowQueryService slowQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlResultCache = sqlResultCache;
        this.sqlRulesChecker = sqlRulesChecker;
        this.sqlParserValidator = sqlParserValidator;
        this.slowQueryService = slowQueryService;
    }

    /**
     * 对数据库执行SQL SELECT语句。
     * 行限制 ({@value #MAX_ROWS}) 在JDBC驱动层强制执行以防止OOM。
     * 只允许SELECT；尝试执行DDL/DML将被立即拒绝。
     * <p>
     * <b>预验证：</b> 在执行前运行 {@code EXPLAIN <sql>} 以捕获
     * schema级别的错误（不存在的表、列），而无需数据开销。
     * 捕获的异常以字符串形式返回，以便AI可以自我修正。
     *
     * @param sql 要执行的SQL SELECT语句
     * @return 格式化的查询结果作为包含行数 and 数据的字符串，
     * 或如果查询失败则返回错误消息
     */
    @Tool(description = """
            Execute a SQL SELECT statement on the video_data_analysis MySQL database.
            Returns query results as a string (max 100 rows).
            Only SELECT queries are permitted; UPDATE, DELETE, DROP, INSERT, ALTER, CREATE,
            TRUNCATE, REPLACE, CALL and other DDL/DML statements will be rejected.
            Use this tool to query user behavior data, dimension tables, and metric definitions.
            If the query returns an error, analyze the error message, fix the SQL, and retry (up to 3 times).
            """)
    public String executeSql(
            @ToolParam(description = "The SQL SELECT statement to execute. Must start with SELECT.") String sql) {
        // 安全检查：只允许SELECT语句（正则 + SQL Parser 双重校验）
        if (!SELECT_PATTERN.matcher(sql).matches()) {
            return "Error: Only SELECT statements are allowed. Your statement was rejected: " + sql;
        }
        String parseError = sqlParserValidator.validate(sql);
        if (parseError != null) {
            return "SQL Syntax Error: " + parseError;
        }

        // 记录 SQL 供外部 Execution Guidance 验证读取
        lastExecutedSql = sql;

        // SQL 结果缓存检查：相同 SQL 在 TTL 内直接返回缓存
        String cached = sqlResultCache.get(sql);
        if (cached != null) {
            return cached;
        }

        // 逻辑规则检查：基于 sql-rules.yml 的静态分析，在 EXPLAIN 之前执行
        String ruleWarning = sqlRulesChecker.check(sql);
        if (ruleWarning != null) {
            return ruleWarning;
        }

        // 预验证：EXPLAIN编译校验 + 查询计划分析
        try {
            List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + sql);
            for (Map<String, Object> row : plan) {
                String type = (String) row.get("type");
                String extra = (String) row.get("Extra");
                Number rows = (Number) row.get("rows");

                // 全表扫描
                if ("ALL".equals(type)) {
                    String table = (String) row.get("table");
                    long r = rows != null ? rows.longValue() : 0;
                    slowQueryService.record("FULL_SCAN", sql, table, r);
                    return "SQL Performance Warning: Full table scan on '" + table
                            + "' (" + r + " rows). Add WHERE conditions on indexed columns.";
                }
                // 使用临时表
                if (extra != null && extra.contains("Using temporary")) {
                    slowQueryService.record("TEMP_TABLE", sql, (String) row.get("table"),
                            rows != null ? rows.longValue() : 0);
                    return "SQL Performance Warning: Query uses temporary table. "
                            + "Consider indexing GROUP BY/ORDER BY columns or simplifying the query.";
                }
                // 文件排序
                if (extra != null && extra.contains("Using filesort")) {
                    slowQueryService.record("FILESORT", sql, (String) row.get("table"),
                            rows != null ? rows.longValue() : 0);
                    return "SQL Performance Warning: Query uses filesort. "
                            + "Consider adding indexes for ORDER BY columns.";
                }
                // 扫描行数过大
                if (rows != null && rows.longValue() > 100000) {
                    slowQueryService.record("LARGE_SCAN", sql, (String) row.get("table"), rows.longValue());
                    return "SQL Performance Warning: Scanning " + rows + " rows. "
                            + "Add more specific WHERE filters to reduce the scan range.";
                }
            }
        } catch (Exception e) {
            return "SQL Compile Error: " + e.getMessage() + ". SQL: " + sql;
        }

        // 熔断检查：连续超时达到阈值时给出建议
        if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
            consecutiveFailures.set(0); // 重置——用户可能已经修改了查询
            return "Warning: Previous SQL queries timed out repeatedly. "
                    + "Please simplify the query, add proper WHERE filters, "
                    + "and avoid table scans. Retry your simplified SQL.";
        }

        try {
            List<Map<String, Object>> results = jdbcTemplate.query(
                    connection -> {
                        var ps = connection.prepareStatement(sql);
                        ps.setMaxRows(MAX_ROWS + 1);
                        ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        return ps;
                    },
                    new ColumnMapRowMapper()
            );
            consecutiveFailures.set(0); // 成功执行，重置熔断计数器

            boolean truncated = results.size() > MAX_ROWS;
            if (truncated) {
                results = results.subList(0, MAX_ROWS);
            }

            // 紧凑的TSV类格式：标题行 + 数据行
            // 与冗长的 "Row N: {key=val}" 格式相比节省约40% token
            StringBuilder sb = new StringBuilder();
            sb.append(results.size()).append(" rows\n");
            if (!results.isEmpty()) {
                sb.append(String.join("|", results.get(0).keySet())).append("\n");
            }
            for (Map<String, Object> row : results) {
                sb.append(String.join("|", row.values().stream()
                        .map(v -> v == null ? "" : v.toString())
                        .toArray(String[]::new))).append("\n");
            }
            if (truncated) {
                sb.append("... [").append(results.size()).append("+ rows, truncated]");
            }
            String result = sb.toString();
            sqlResultCache.put(sql, result);
            return result;
        } catch (Exception e) {
            // 超时检查——只有超时才计入熔断计数器
            String msg = e.getMessage();
            if (msg != null && msg.contains("timeout")) {
                consecutiveFailures.incrementAndGet();
            }
            // 将异常消息返回给AI模型进行自我修正
            return "SQL Execution Error: " + msg;
        }
    }
}
