-- 播放明细表：记录每次播放的详细体验数据
-- 加入后，RAGAgent 的评论证据可以被客观数据验证
CREATE TABLE IF NOT EXISTS play_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    content_id VARCHAR(64) NOT NULL,
    play_duration INT NOT NULL COMMENT '实际观看时长(秒)',
    drop_off_second INT COMMENT '跳出时间点(秒)：用户在视频第几秒离开',
    completion_rate DECIMAL(5,2) COMMENT '完播率',
    created_at DATETIME NOT NULL
);

-- 拓展内容表字段（用于交叉验证"广告太多"类评论）
ALTER TABLE content_dim ADD COLUMN IF NOT EXISTS ad_count INT DEFAULT 0 COMMENT '贴片广告数量';
ALTER TABLE content_dim ADD COLUMN IF NOT EXISTS ad_positions JSON COMMENT '广告插入时间点';
