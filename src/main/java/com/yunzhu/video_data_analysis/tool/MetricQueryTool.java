package com.yunzhu.video_data_analysis.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用于从metric_def表查询业务指标定义的工具。
 * 当用户询问特定指标（例如，完播率、互动率）时，
 * AI模型必须首先调用此工具以获取正确的公式，
 * 然后再编写任何SQL。
 * <p>
 * 此工具执行纯动态数据库查找 — 没有硬编码的业务逻辑。
 */
@Component
public class MetricQueryTool {

    private final JdbcTemplate jdbcTemplate;

    public MetricQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 从metric_def表中按名称查找指标定义。
     * 返回所有可用的元数据：公式、维度配置和时间粒度。
     *
     * @param metricName 要查询的指标名称（区分大小写，匹配metric_name列）
     * @return 格式化的指标定义信息，或“未找到”消息
     */
    @Tool(description = """
            Query the metric definition table (metric_def) by metric name to get
            the calculation formula, available dimensions, and time granularity.
            Call this tool FIRST when the user asks about specific metrics like
            completion rate (完播率), engagement rate (互动率), like rate (点赞率),
            share rate (分享率), comment rate (评论率), follow rate (关注率), etc.
            Returns the metric's formula expression and dimension configuration.
            """)
    public String getMetricFormula(
            @ToolParam(description = "The metric name to query, e.g., '完播率', '互动率', '点赞率'. Case-sensitive, must match the metric_name column exactly.") String metricName) {
        String sql = "SELECT metric_name, formula, dimension, time_granularity FROM metric_def WHERE metric_name = ?";

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, metricName);

            if (results.isEmpty()) {
                return "未找到该指标定义: " + metricName
                        + "。请检查指标名称是否正确，可尝试其他关键词查询。";
            }

            Map<String, Object> row = results.get(0);
            return String.format(
                    "指标名称: %s\n公式: %s\n维度: %s\n时间粒度: %s",
                    row.get("metric_name"),
                    row.get("formula"),
                    row.get("dimension"),
                    row.get("time_granularity")
            );
        } catch (Exception e) {
            return "查询指标定义出错: " + e.getMessage();
        }
    }
}
