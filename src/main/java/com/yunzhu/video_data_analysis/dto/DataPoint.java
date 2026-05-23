package com.yunzhu.video_data_analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用于图表渲染的单个数据点。
 */
public class DataPoint {

    @JsonProperty("label")
    private String label;

    @JsonProperty("value")
    private double value;

    /** 可选的分组字段（例如，分组条形图的分类名称）。 */
    @JsonProperty("category")
    private String category;

    public DataPoint() {}

    public DataPoint(String label, double value) {
        this.label = label;
        this.value = value;
    }

    public DataPoint(String label, double value, String category) {
        this.label = label;
        this.value = value;
        this.category = category;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
