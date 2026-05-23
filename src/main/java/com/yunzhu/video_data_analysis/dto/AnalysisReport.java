package com.yunzhu.video_data_analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 由AI智能体生成的结构化分析报告。
 * <p>
 * 模型将其输出为JSON，然后由Spring AI的
 * {@link org.springframework.ai.converter.BeanOutputConverter} 解析并
 * 返回到前端进行可视化。
 */
public class AnalysisReport {

    /** 分析的一段自然语言摘要。 */
    @JsonProperty("summary")
    private String summary;

    /** 关键业务指标及其值和趋势。 */
    @JsonProperty("metrics")
    private List<MetricPoint> metrics;

    /** 用于可视化的图表配置。 */
    @JsonProperty("charts")
    private List<ChartConfig> charts;

    /** 基于数据的可操作建议。 */
    @JsonProperty("recommendations")
    private List<String> recommendations;

    /** 分析的时间段，例如 "2023-10-01 ~ 2023-10-07"。 */
    @JsonProperty("period")
    private String period;

    public AnalysisReport() {}

    public AnalysisReport(String summary, List<MetricPoint> metrics,
                          List<ChartConfig> charts, List<String> recommendations, String period) {
        this.summary = summary;
        this.metrics = metrics;
        this.charts = charts;
        this.recommendations = recommendations;
        this.period = period;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<MetricPoint> getMetrics() { return metrics; }
    public void setMetrics(List<MetricPoint> metrics) { this.metrics = metrics; }

    public List<ChartConfig> getCharts() { return charts; }
    public void setCharts(List<ChartConfig> charts) { this.charts = charts; }

    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
}
