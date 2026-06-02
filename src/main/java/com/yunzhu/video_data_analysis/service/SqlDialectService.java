package com.yunzhu.video_data_analysis.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 数据库方言检测服务。运行时检测数据库类型，生成对应的 SQL 语法规则，
 * 使 SQLGenerationAgent 的 System Prompt 自动适配不同数据库。
 * <p>
 * 目前支持 MySQL，预留 PostgreSQL 扩展点。
 */
@Service
public class SqlDialectService {

    private static final Logger log = LoggerFactory.getLogger(SqlDialectService.class);

    private final JdbcTemplate jdbcTemplate;

    private String databaseType = "mysql";
    private boolean initialized = false;

    public SqlDialectService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            String product = jdbcTemplate.queryForObject(
                    "SELECT LOWER(VERSION())", String.class);
            if (product != null) {
                if (product.contains("mysql")) databaseType = "mysql";
                else if (product.contains("postgresql") || product.contains("pg")) databaseType = "postgresql";
                else if (product.contains("mariadb")) databaseType = "mariadb";
            }
            initialized = true;
            log.info("SqlDialectService detected database: {} ({})", databaseType, product);
        } catch (Exception e) {
            log.warn("Failed to detect database type, defaulting to MySQL: {}", e.getMessage());
        }
    }

    /**
     * 获取数据库方言对应的 SQL 规则字符串，供 SQLGenerationAgent 的 System Prompt 使用。
     */
    public String getSqlRules() {
        return switch (databaseType) {
            case "postgresql" -> """
                规则：SELECT only。JSON用#>>。时间用timestamp比较。
                COALESCE防NULL。LIMIT限制行数。
                表结构见下方上下文，勿臆测字段。
                """;
            default -> // mysql / mariadb
                """
                规则：SELECT only。JSON用->>。时间直接比timestamp。
                COALESCE防NULL。executeSql限100行，超限用GROUP BY+LIMIT。
                表结构见下方上下文，勿臆测字段。

                【性能优化】聚合查询优先查 metric_daily 表。
                """;
        };
    }

    public String getDatabaseType() { return databaseType; }
    public boolean isInitialized() { return initialized; }
}
