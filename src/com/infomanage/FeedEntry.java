package com.infomanage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信息流中的一条记录（聊天 / 系统消息 / 加入 / 离开）。
 * 仅在内存中缓存，用于网页实时展示。
 */
public class FeedEntry {
    public final long ts;
    public final String type;   // chat | system | join | leave
    public final String name;   // 玩家名（系统消息为空串）
    public final String message;
    public final String uuid;
    public final boolean premium;

    public FeedEntry(long ts, String type, String name, String message, String uuid, boolean premium) {
        this.ts = ts;
        this.type = type;
        this.name = name;
        this.message = message;
        this.uuid = uuid;
        this.premium = premium;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", ts);
        m.put("type", type);
        m.put("name", name);
        m.put("message", message);
        m.put("uuid", uuid);
        m.put("premium", premium);
        return m;
    }
}
