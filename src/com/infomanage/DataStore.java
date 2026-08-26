package com.infomanage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.texture.Texture;
import org.geysermc.mcprotocollib.auth.texture.TextureType;
import xin.bbtt.mcbot.Bot;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 共享数据存储：负责实时状态维护与持久化。
 * 数据目录为 plugin/InfoManage/，其中：
 *   - config.conf  插件配置（连接信息）
 *   - players.json 全服玩家档案（跨重启保留）
 *   - online.log   玩家加入/离开记录（跨重启保留）
 *   - pinned.txt   置顶玩家列表（跨重启保留）
 */
public class DataStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PluginConfig config;
    private final File dataDir;

    // 全服玩家档案：name -> PlayerRecord
    private final Map<String, PlayerRecord> registry = new ConcurrentHashMap<>();
    // 当前在线：name -> 加入时间(ms)
    private final Map<String, Long> online = new ConcurrentHashMap<>();
    // 信息流缓存
    private final ConcurrentLinkedDeque<FeedEntry> feed = new ConcurrentLinkedDeque<>();
    // 置顶玩家
    private final Set<String> pinned = ConcurrentHashMap.newKeySet();

    private final Object logLock = new Object();
    private BufferedWriter logWriter;

    private volatile ScheduledExecutorService scheduler;
    private volatile long suppressReconcileUntil = 0L;
    private volatile String botName = null;
    private final AtomicBoolean registryDirty = new AtomicBoolean(false);

    public DataStore(PluginConfig config) {
        this.config = config;
        this.dataDir = new File(config.dataDir);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        loadRegistry();
        loadPinned();
        openLog();
    }

    // ---------------- 启动 ----------------
    public void start() {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) {
            s = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "InfoManage-Reconcile");
                t.setDaemon(true);
                return t;
            });
            scheduler = s;
        }
        openLog();
        s.scheduleWithFixedDelay(this::reconcile, 5, 5, TimeUnit.SECONDS);
        s.scheduleWithFixedDelay(this::saveRegistryIfDirty, 10, 10, TimeUnit.SECONDS);
    }

    public void shutdown() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
        flush();
    }

    // 收到 ClientboundLoginPacket 后调用，抑制一段时间内对 online 的清理，
    // 避免重连时 tab 列表清空再重建导致误报大量离开/加入。
    public void notifyLogin() {
        suppressReconcileUntil = System.currentTimeMillis() + 15000L;
    }

    // ---------------- 持久化：加载 ----------------
    private void loadRegistry() {
        File f = new File(dataDir, "players.json");
        if (!f.exists()) return;
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<PlayerRecord> list = GSON.fromJson(json, new TypeToken<List<PlayerRecord>>() {}.getType());
            if (list != null) {
                for (PlayerRecord r : list) {
                    if (r != null && r.name != null && !r.name.isEmpty()) {
                        registry.put(r.name, r);
                    }
                }
            }
        } catch (Exception ignore) {
            // 忽略损坏的档案文件
        }
    }

    private void loadPinned() {
        File f = new File(dataDir, "pinned.txt");
        if (!f.exists()) return;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String n = line.trim();
                if (!n.isEmpty()) pinned.add(n);
            }
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private void openLog() {
        synchronized (logLock) {
            if (logWriter != null) return;
            try {
                logWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(dataDir, "online.log"), true), StandardCharsets.UTF_8));
            } catch (IOException e) {
                logWriter = null;
            }
        }
    }

    // ---------------- 正版/离线判定 ----------------
    // 裂痕服(offline-mode)中，离线(盗版)玩家的 UUID 由名字派生(OfflinePlayer:xxx)，
    // 而正版玩家拥有真实 Mojang UUID。服务器会给所有玩家注入带签名的皮肤，
    // 因此不能靠 textures 签名区分，必须比对 UUID。
    public static boolean isPremium(GameProfile profile) {
        if (profile == null || profile.getId() == null) return false;
        String name = cleanName(profile.getName());
        if (name == null || name.isEmpty()) return false;
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return !profile.getId().equals(offline);
    }

    // 从 GameProfile 提取皮肤纹理 URL（服务器会给正版与离线玩家都注入皮肤）
    private static String extractSkinUrl(GameProfile profile) {
        if (profile == null) return null;
        try {
            Map<TextureType, Texture> textures = profile.getTextures(false);
            Texture skin = textures.get(TextureType.SKIN);
            if (skin != null) {
                String url = skin.getURL();
                if (url != null && !url.isEmpty()) return url;
            }
        } catch (Exception ignore) {
            // 无皮肤或纹理解析失败
        }
        return null;
    }

    // ---------------- 事件处理 ----------------
    public void onPlayerJoin(GameProfile profile) {
        if (profile == null) return;
        String name = profile.getName();
        if (invalid(name)) return;
        long now = System.currentTimeMillis();
        registerJoin(name, profile, now);
    }

    public void onPlayerLeave(GameProfile profile) {
        if (profile == null) return;
        String name = profile.getName();
        if (invalid(name)) return;
        registerLeave(name, System.currentTimeMillis());
    }

    public void onSystemMessage(String text) {
        if (text == null) return;
        text = stripControl(text).trim();
        if (text.isEmpty()) return;
        addFeed("system", "", text, "", false);
    }

    public void onPlayerChat(String name, String message, boolean premium) {
        if (invalid(name)) return;
        if (message == null) message = "";
        message = stripControl(message).trim();
        PlayerRecord r = registry.get(name);
        if (r != null) {
            r.messageCount++;
            markDirty();
        }
        addFeed("chat", name, message, "", premium);
    }

    /**
     * 记录一次死亡/击杀。victim 必填，killer 可为空（非玩家击杀）。
     */
    public void onDeath(String victim, String killer) {
        if (victim != null && !victim.isEmpty() && !invalid(victim)) {
            PlayerRecord r = registry.get(victim);
            if (r != null) {
                r.deathCount++;
                markDirty();
            }
        }
        if (killer != null && !killer.isEmpty() && !invalid(killer)) {
            PlayerRecord r = registry.get(killer);
            if (r != null) {
                r.killCount++;
                markDirty();
            }
        }
    }

    // ---------------- 注册 / 注销 ----------------
    private void registerJoin(String name, GameProfile profile, long ts) {
        PlayerRecord r = registry.computeIfAbsent(name, k -> new PlayerRecord());
        r.name = name;
        if (profile != null) {
            r.uuid = profile.getIdAsString();
            r.premium = isPremium(profile);
            String skin = extractSkinUrl(profile);
            if (skin != null) r.skinUrl = skin;
        }
        if (r.firstSeenMs == 0L) r.firstSeenMs = ts;
        r.lastSeenMs = ts;
        r.lastLoginMs = ts;
        r.sessions++;
        online.put(name, ts);
        appendLog("JOIN", name, r.premium);
        addFeed("join", name, name + " 加入了服务器", r.uuid, r.premium);
        markDirty();
    }

    private void registerLeave(String name, long ts) {
        Long joined = online.remove(name);
        PlayerRecord r = registry.get(name);
        if (r != null) {
            if (joined != null) r.totalOnlineMs += Math.max(0L, ts - joined);
            r.lastSeenMs = ts;
            r.lastLogoutMs = ts;
        }
        appendLog("LEAVE", name, r != null && r.premium);
        addFeed("leave", name, name + " 离开了服务器", r != null ? r.uuid : "", r != null && r.premium);
        markDirty();
    }

    // ---------------- 周期性对账 ----------------
    private void reconcile() {
        Map<UUID, GameProfile> players = Bot.INSTANCE.players;
        if (players == null || players.isEmpty()) {
            // 仅在未抑制时清空（避免重连时 tab 列表清空导致误报大量离开）
            if (System.currentTimeMillis() >= suppressReconcileUntil && !online.isEmpty()) {
                for (String name : new ArrayList<>(online.keySet())) {
                    registerLeave(name, System.currentTimeMillis());
                }
            }
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, GameProfile> current = new LinkedHashMap<>();
        for (GameProfile p : players.values()) {
            if (p == null) continue;
            String n = p.getName();
            if (invalid(n)) continue;
            current.put(n, p);
        }
        // 新增在线
        for (Map.Entry<String, GameProfile> e : current.entrySet()) {
            String name = e.getKey();
            if (!online.containsKey(name)) {
                registerJoin(name, e.getValue(), now);
            } else {
                // 更新正版状态与 uuid（textures 可能稍后到达）
                PlayerRecord r = registry.get(name);
                if (r != null) {
                    boolean premium = isPremium(e.getValue());
                    if (premium != r.premium) {
                        r.premium = premium;
                        markDirty();
                    }
                    String u = e.getValue().getIdAsString();
                    if (u != null && !u.isEmpty() && !u.equals(r.uuid)) {
                        r.uuid = u;
                        markDirty();
                    }
                    String skin = extractSkinUrl(e.getValue());
                    if (skin != null && !skin.equals(r.skinUrl)) {
                        r.skinUrl = skin;
                        markDirty();
                    }
                }
            }
        }
        // 移除已离线
        for (String name : new ArrayList<>(online.keySet())) {
            if (!current.containsKey(name)) {
                registerLeave(name, now);
            }
        }
    }

    // ---------------- 工具 ----------------
    private boolean invalid(String name) {
        if (name == null) return true;
        String c = cleanName(name);
        if (c.isEmpty()) return true;
        return c.equals(botName());
    }

    // 去除服务器给未认证玩家加的前缀(如 AuthMe 的 "||")，真实 MC 用户名只含 [a-zA-Z0-9_]
    private static String cleanName(String name) {
        if (name == null) return null;
        int i = 0;
        while (i < name.length() && name.charAt(i) == '|') i++;
        return name.substring(i);
    }

    private String botName() {
        String b = botName;
        if (b == null) {
            try {
                b = Bot.INSTANCE.getConfig().getConfigData().getAccount().getName();
            } catch (Exception ignore) {
                b = null;
            }
            if (b == null || b.isEmpty()) {
                try {
                    GameProfile p = Bot.INSTANCE.getProtocol().getProfile();
                    b = p != null ? p.getName() : null;
                } catch (Exception ignore) {
                    b = null;
                }
            }
            botName = (b == null ? "" : b);
        }
        return botName;
    }

    private String stripControl(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                sb.append(c).append(s.charAt(i + 1)); // 保留 Minecraft 颜色代码，供前端渲染彩色
                i++;
                continue;
            }
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private void addFeed(String type, String name, String message, String uuid, boolean premium) {
        feed.addLast(new FeedEntry(System.currentTimeMillis(), type, name, message, uuid, premium));
        while (feed.size() > config.maxFeedEntries) {
            feed.pollFirst();
        }
    }

    private void appendLog(String action, String name, boolean premium) {
        synchronized (logLock) {
            if (logWriter == null) return;
            try {
                logWriter.write(TS.format(Instant.now()) + " [" + action + "] " + name
                        + (premium ? " [正版]" : " [离线]"));
                logWriter.newLine();
                logWriter.flush();
            } catch (IOException ignore) {
                // 忽略写失败
            }
        }
    }

    private void markDirty() {
        registryDirty.set(true);
    }

    private void saveRegistryIfDirty() {
        if (registryDirty.getAndSet(false)) {
            saveRegistry();
        }
    }

    private void saveRegistry() {
        try {
            List<PlayerRecord> list = new ArrayList<>(registry.values());
            list.sort(Comparator.comparingLong(r -> r.firstSeenMs));
            String json = GSON.toJson(list);
            writeAtomic("players.json", json);
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private void savePinned() {
        try {
            List<String> list = new ArrayList<>(pinned);
            java.util.Collections.sort(list);
            StringBuilder sb = new StringBuilder();
            for (String s : list) {
                sb.append(s).append('\n');
            }
            writeAtomic("pinned.txt", sb.toString());
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private void writeAtomic(String fileName, String content) throws IOException {
        File target = new File(dataDir, fileName);
        File tmp = new File(dataDir, fileName + ".tmp");
        Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void flush() {
        saveRegistry();
        savePinned();
        synchronized (logLock) {
            if (logWriter != null) {
                try {
                    logWriter.close();
                } catch (IOException ignore) {
                }
                logWriter = null;
            }
        }
    }

    // ---------------- 置顶 ----------------
    public boolean setPin(String name, boolean pin) {
        if (name == null) return false;
        name = name.trim();
        if (name.isEmpty()) return false;
        if (pin) pinned.add(name);
        else pinned.remove(name);
        savePinned();
        return pinned.contains(name);
    }

    public boolean isPinned(String name) {
        return name != null && pinned.contains(name);
    }

    // ---------------- 查询 ----------------
    public int getOnlineCount() {
        return online.size();
    }

    public int getTotalPlayers() {
        return registry.size();
    }

    public Set<String> getPinned() {
        return new TreeSet<>(pinned);
    }

    public List<Map<String, Object>> getOnlinePlayers() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>(online.keySet());
        names.sort((a, b) -> {
            boolean pa = pinned.contains(a), pb = pinned.contains(b);
            if (pa != pb) return pa ? -1 : 1;
            return a.compareToIgnoreCase(b);
        });
        for (String name : names) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            PlayerRecord r = registry.get(name);
            m.put("premium", r != null && r.premium);
            m.put("pinned", pinned.contains(name));
            m.put("uuid", r != null ? r.uuid : "");
            m.put("skin", r != null ? r.skinUrl : "");
            Long since = online.get(name);
            m.put("onlineSeconds", since != null ? (now - since) / 1000L : 0L);
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> getFeed() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FeedEntry e : feed) {
            out.add(e.toMap());
        }
        return out;
    }

    public Map<String, Object> getPlayerDetail(String name) {
        if (name == null) return null;
        PlayerRecord r = registry.get(name);
        if (r == null) return null;
        long now = System.currentTimeMillis();
        Long since = online.get(name);
        boolean isOnline = since != null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name);
        m.put("premium", r.premium);
        m.put("pinned", pinned.contains(r.name));
        m.put("online", isOnline);
        m.put("skin", r.skinUrl);
        m.put("onlineSeconds", isOnline ? (now - since) / 1000L : 0L);
        m.put("firstSeenMs", r.firstSeenMs);
        m.put("lastLoginMs", r.lastLoginMs);
        m.put("lastLogoutMs", isOnline ? 0L : r.lastLogoutMs);
        m.put("totalOnlineMs", r.totalOnlineMs);
        m.put("sessions", r.sessions);
        m.put("deathCount", r.deathCount);
        m.put("messageCount", r.messageCount);
        m.put("killCount", r.killCount);
        return m;
    }
}
