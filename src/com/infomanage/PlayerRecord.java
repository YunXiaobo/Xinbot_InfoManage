package com.infomanage;

/**
 * 全服玩家档案（持久化到 players.json）。
 * 记录某位玩家首次/最近在线、在线时长、死亡/发言/击杀等统计信息。
 */
public class PlayerRecord {
    public String name = "";
    public String uuid = "";
    public String skinUrl = "";       // 皮肤纹理 URL（正版与离线玩家皆有，用于头像）
    public boolean premium = false;
    public long firstSeenMs = 0L;    // 首次加入时间
    public long lastSeenMs = 0L;     // 最近活动时间（保留兼容）
    public long lastLoginMs = 0L;    // 上次登录(加入)时间
    public long lastLogoutMs = 0L;   // 上次退出时间
    public int sessions = 0;         // 加入次数
    public long totalOnlineMs = 0L;  // 累计在线时长(ms)
    public int deathCount = 0;       // 死亡次数
    public int messageCount = 0;     // 发言次数
    public int killCount = 0;        // 击杀数量
}
