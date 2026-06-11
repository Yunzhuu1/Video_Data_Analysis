package com.yunzhu.video_data_analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 慢查询持久化服务。记录 EXPLAIN 检测到的慢查询并定期汇总 TOP-N 模式。
 */
@Service
public class SlowQueryService {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public SlowQueryService(JdbcTemplate jdbcTemplate,
                            @Value("${app.slow-query.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        if (enabled) {
            ensureTable();
        }
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS slow_query_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                sql_text TEXT NOT NULL,
                issue_type VARCHAR(32) NOT NULL COMMENT 'FULL_SCAN/TEMP_TABLE/FILESORT/LARGE_SCAN',
                rows_examined BIGINT DEFAULT 0,
                table_name VARCHAR(64),
                created_at DATETIME NOT NULL
            )
            """);
    }

    public void record(String issueType, String sql, String tableName, long rows) {
        if (!enabled) return;
        jdbcTemplate.update(
            "INSERT INTO slow_query_log (sql_text, issue_type, rows_examined, table_name, created_at) VALUES (?, ?, ?, ?, ?)",
            truncate(sql, 500), issueType, rows, tableName, LocalDateTime.now()
        );
    }

    /** 每周汇总 TOP 慢查询模式 */
    @Scheduled(cron = "0 0 2 * * MON")
    public void weeklySummary() {
        if (!enabled) return;
        List<Map<String, Object>> stats = jdbcTemplate.queryForList(
            "SELECT issue_type, table_name, COUNT(*) as cnt, "
            + "ROUND(AVG(rows_examined)) as avg_rows "
            + "FROM slow_query_log "
            + "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
            + "GROUP BY issue_type, table_name ORDER BY cnt DESC LIMIT 10");

        if (stats.isEmpty()) return;
        log.info("=== Slow Query Weekly Report ===");
        for (var row : stats) {
            log.info("{} | table={} | count={} | avg_rows={}",
                    row.get("issue_type"), row.get("table_name"),
                    row.get("cnt"), row.get("avg_rows"));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
