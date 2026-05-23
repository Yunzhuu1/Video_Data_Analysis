package com.yunzhu.video_data_analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 来自 {@code RAGAgent} 评论分析的结构化结果。
 * 由 {@code InsightAgent} 消费，以便在归因分析中
 * 添加用户反馈证据。
 */
public class CommentResult {

    @JsonProperty("themes")
    private List<String> themes;

    @JsonProperty("totalScanned")
    private int totalScanned;

    @JsonProperty("negativeRatio")
    private double negativeRatio;

    @JsonProperty("representativeComments")
    private List<String> representativeComments;

    @JsonProperty("summary")
    private String summary;

    public CommentResult() {}

    public CommentResult(List<String> themes, int totalScanned, double negativeRatio,
                         List<String> representativeComments, String summary) {
        this.themes = themes;
        this.totalScanned = totalScanned;
        this.negativeRatio = negativeRatio;
        this.representativeComments = representativeComments;
        this.summary = summary;
    }

    public List<String> getThemes() { return themes; }
    public void setThemes(List<String> themes) { this.themes = themes; }
    public int getTotalScanned() { return totalScanned; }
    public void setTotalScanned(int totalScanned) { this.totalScanned = totalScanned; }
    public double getNegativeRatio() { return negativeRatio; }
    public void setNegativeRatio(double negativeRatio) { this.negativeRatio = negativeRatio; }
    public List<String> getRepresentativeComments() { return representativeComments; }
    public void setRepresentativeComments(List<String> representativeComments) { this.representativeComments = representativeComments; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
