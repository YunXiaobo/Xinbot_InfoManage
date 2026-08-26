package com.infomanage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 插件配置（对应 plugin/InfoManage/config.conf）。
 * 仅保存【连接服务器】所需的网络信息，与根目录的玩家账号信息完全无关。
 */
public class PluginConfig {
    // 连接相关选项默认留空，首次启动后需用户在 config.conf 中自行填写
    public String serverHost = "";
    public int serverPort = 25565;      // 留空时默认 25565
    public int serverProtocol = 774;    // 信息性字段，留空时默认 774 = 1.21.11
    public String webHost = "127.0.0.1";
    public int webPort = 8080;
    public int maxFeedEntries = 500;
    public String title = "服务器状态";
    public String dataDir = "plugin/InfoManage";

    public static PluginConfig load(File dataDir) {
        PluginConfig cfg = new PluginConfig();
        cfg.dataDir = dataDir.getPath();
        File f = new File(dataDir, "config.conf");
        if (f.exists()) {
            Properties p = new Properties();
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (Exception ignore) {
                // 读取失败则使用默认值
            }
            cfg.serverHost = str(p.getProperty("server.host"), cfg.serverHost);
            cfg.serverPort = parseInt(p.getProperty("server.port"), cfg.serverPort);
            cfg.serverProtocol = parseInt(p.getProperty("server.protocol"), cfg.serverProtocol);
            cfg.webHost = str(p.getProperty("web.host"), cfg.webHost);
            cfg.webPort = parseInt(p.getProperty("web.port"), cfg.webPort);
            cfg.maxFeedEntries = parseInt(p.getProperty("feed.maxEntries"), cfg.maxFeedEntries);
            cfg.title = str(p.getProperty("title"), cfg.title);
        } else {
            cfg.save(dataDir);
        }
        return cfg;
    }

    public void save(File dataDir) {
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("server.host", serverHost);
        p.setProperty("server.port", String.valueOf(serverPort));
        p.setProperty("server.protocol", String.valueOf(serverProtocol));
        p.setProperty("web.host", webHost);
        p.setProperty("web.port", String.valueOf(webPort));
        p.setProperty("feed.maxEntries", String.valueOf(maxFeedEntries));
        p.setProperty("title", title);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dataDir, "config.conf")), StandardCharsets.UTF_8)) {
            p.store(w, "InfoManage plugin config - server connection info only (not player account info)");
        } catch (Exception ignore) {
            // 忽略写失败
        }
    }

    private static String str(String v, String def) {
        if (v == null) return def;
        String t = v.trim();
        return t.isEmpty() ? def : t;
    }

    private static int parseInt(String v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
