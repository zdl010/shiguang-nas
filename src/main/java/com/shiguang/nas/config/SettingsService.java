package com.shiguang.nas.config;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行期可改的设置项，存在 settings 表。
 *
 * <p>带一层内存缓存：这些值几乎每个请求都要读（比如"是否开放注册"），
 * 每次都查库是白白的 IO。单进程部署，缓存和库不会不一致。
 */
@Service
public class SettingsService {

    public static final String TRASH_RETENTION_DAYS = "trash.retention.days";
    public static final String SITE_NAME = "site.name";

    private final JdbcClient jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        reload();
    }

    public final void reload() {
        cache.clear();
        jdbc.sql("SELECT k, v FROM settings")
                .query((rs, n) -> Map.entry(rs.getString("k"), rs.getString("v")))
                .list()
                .forEach(e -> cache.put(e.getKey(), e.getValue()));
    }

    public String get(String key, String fallback) {
        return cache.getOrDefault(key, fallback);
    }

    public boolean getBoolean(String key, boolean fallback) {
        return Optional.ofNullable(cache.get(key)).map(Boolean::parseBoolean).orElse(fallback);
    }

    public long getLong(String key, long fallback) {
        String raw = cache.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // 设置项被手工改坏了不该让功能整个失效，用默认值继续跑
            return fallback;
        }
    }

    public void put(String key, String value) {
        jdbc.sql("""
                INSERT INTO settings(k, v, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated_at = excluded.updated_at
                """)
                .params(key, value, System.currentTimeMillis())
                .update();
        cache.put(key, value);
    }

    public Map<String, String> all() {
        return Map.copyOf(cache);
    }
}
