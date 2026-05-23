package com.yunzhu.video_data_analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个关键指标，包含值、变化和趋势方向。
 */
public class MetricPoint {

    @JsonProperty("name")
    private String name;

    @JsonProperty("value")
    private double value;

    /** 与上一时期相比的百分比变化（例如，-2.1表示-2.1%）。 */
    @JsonProperty("change")
    private double change;

    /** 趋势方向："up"、"down" 或 "stable"。 */
    @JsonProperty("trend")
    private String trend;

    /** 单位字符串，例如 "%"、"次"、"秒"。 */
    @JsonProperty("unit")
    private String unit;

    public MetricPoint() {}

    public MetricPoint(String name, double value, double change, String trend, String unit) {
        this.name = name;
        this.value = value;
        this.change = change;
        this.trend = trend;
        this.unit = unit;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public double getChange() { return change; }
    public void setChange(double change) { this.change = change; }

    public String getTrend() { return trend; }
    public void setTrend(String trend) { this.trend = trend; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}
