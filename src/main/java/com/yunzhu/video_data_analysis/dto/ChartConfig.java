package com.yunzhu.video_data_analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 在仪表板上渲染图表的配置。
 * <p>
 * AI模型根据查询结果生成图表定义，
 * 前端使用图表库（ECharts）直接渲染。
 */
public class ChartConfig {

    /** 图表类型："line" | "bar" | "pie"。 */
    @JsonProperty("type")
    private String type;

    @JsonProperty("title")
    private String title;

    @JsonProperty("data")
    private List<DataPoint> data;

    /** Y-axis / value unit label, e.g., "播放量", "完播率(%)". */
    @JsonProperty("yAxisLabel")
    private String yAxisLabel;

    /** X-axis / category label, e.g., "日期", "分类". */
    @JsonProperty("xAxisLabel")
    private String xAxisLabel;

    public ChartConfig() {}

    public ChartConfig(String type, String title, List<DataPoint> data) {
        this.type = type;
        this.title = title;
        this.data = data;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<DataPoint> getData() { return data; }
    public void setData(List<DataPoint> data) { this.data = data; }

    public String getYAxisLabel() { return yAxisLabel; }
    public void setYAxisLabel(String yAxisLabel) { this.yAxisLabel = yAxisLabel; }

    public String getXAxisLabel() { return xAxisLabel; }
    public void setXAxisLabel(String xAxisLabel) { this.xAxisLabel = xAxisLabel; }
}
