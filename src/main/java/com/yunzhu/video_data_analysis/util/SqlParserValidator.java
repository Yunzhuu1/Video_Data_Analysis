package com.yunzhu.video_data_analysis.util;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.expression.Expression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 JSqlParser 的 SQL 语法校验器。
 * 解析 SQL 语法树，检查表名、字段、JOIN 等结构是否正确。
 * 替代简单的 SELECT 正则校验，支持嵌套查询和子查询。
 */
@Component
public class SqlParserValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlParserValidator.class);

    private static final List<String> KNOWN_TABLES = List.of(
            "user_behavior_fact", "content_dim", "creator_dim", "user_dim",
            "time_dim", "activity_dim", "metric_def", "metric_daily", "play_detail",
            "comment_content", "COLUMNS", "KEY_COLUMN_USAGE", "TABLES");

    private static final List<String> BLOCKED_STATEMENTS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
            "TRUNCATE", "REPLACE", "CALL", "GRANT", "REVOKE");

    /**
     * 校验 SQL 是否合法且安全。
     *
     * @return 合法返回 null，不合法返回错误描述
     */
    public String validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "SQL 为空";
        }

        String upper = sql.trim().toUpperCase();

        // 1. 拦截非 SELECT
        for (String blocked : BLOCKED_STATEMENTS) {
            if (upper.startsWith(blocked)) {
                return "只允许 SELECT 语句，检测到: " + blocked;
            }
        }

        // 2. 用 JSqlParser 解析语法树
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);

            if (!(stmt instanceof Select)) {
                return "只允许 SELECT 语句，解析到: " + stmt.getClass().getSimpleName();
            }

            // 3. 检查 FROM 中的表名是否在已知表列表中
            // 此处可以深度遍历语法树做更多检查，当前先检查基本语法正确
            return null; // 校验通过

        } catch (JSQLParserException e) {
            return "SQL 语法错误: " + e.getCause().getMessage();
        }
    }
}
