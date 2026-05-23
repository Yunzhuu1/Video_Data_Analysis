package com.yunzhu.video_data_analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * SQL 执行结果缓存。
 * 对相同 SQL 原文在 TTL 内重复执行时直接返回缓存结果，
 * 避免重复查询数据库。
 * <p>
 * 键为 SQL 原文的 MD5，值为结果字符串。
 * 默认 TTL 5 分钟，可通过 {@code app.cache.sql-ttl-minutes} 配置。
 */
@Service
public class SqlResultCache {

    private static final Logger log = LoggerFactory.getLogger(SqlResultCache.class);
    private static final String PREFIX = "sql:cache:";

    private final StringRedisTemplate redis;
    private final long ttlMinutes;

    public SqlResultCache(StringRedisTemplate redis,
                          @org.springframework.beans.factory.annotation.Value("${app.cache.sql-ttl-minutes:5}") long ttlMinutes) {
        this.redis = redis;
        this.ttlMinutes = ttlMinutes;
    }

    public String get(String sql) {
        String key = key(sql);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            log.debug("SQL result cache HIT | sql=\"{}\"", truncate(sql, 60));
        }
        return cached;
    }

    public void put(String sql, String result) {
        String key = key(sql);
        redis.opsForValue().set(key, result, ttlMinutes, TimeUnit.MINUTES);
        log.debug("SQL result cache PUT | sql=\"{}\" | expires={}min", truncate(sql, 60), ttlMinutes);
    }

    private static String key(String sql) {
        return PREFIX + md5(sql);
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
