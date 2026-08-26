package com.infomanage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundHelloPacket;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Utils;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.EventPriority;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.DisconnectEvent;
import xin.bbtt.mcbot.events.PlayerJoinEvent;
import xin.bbtt.mcbot.events.PlayerLeaveEvent;
import xin.bbtt.mcbot.events.ReceivePacketEvent;
import xin.bbtt.mcbot.events.SystemChatMessageEvent;

/**
 * 事件监听器：把 Xinbot 高层事件转成 DataStore 的数据变更。
 */
public class InfoEventListener implements Listener {
    private final DataStore store;

    private volatile long lastAuthAttempt = 0L;
    private volatile String password = null;

    public InfoEventListener(DataStore store) {
        this.store = store;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        store.onPlayerJoin(e.getPlayerProfile());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerLeaveEvent e) {
        store.onPlayerLeave(e.getPlayerProfile());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSystem(SystemChatMessageEvent e) {
        if (e.isOverlay()) return;
        String text = e.getText();
        maybeAuthenticate(text);

        Component content = e.getContent();
        // 死亡/击杀：通过组件 translation key 识别（death.*）
        if (content instanceof TranslatableComponent) {
            TranslatableComponent tc = (TranslatableComponent) content;
            String key = tc.key();
            if (key != null && key.startsWith("death.")) {
                handleDeath(tc, key, text);
                return;
            }
        }

        // 玩家发言：服务器把聊天以 «name» message 形式通过系统消息发送
        if (text != null && text.startsWith("«")) {
            int end = text.indexOf("»");
            if (end > 1 && end + 1 < text.length()) {
                String name = stripColorCodes(text.substring(1, end));
                String message = text.substring(end + 1).trim();
                store.onPlayerChat(name, message, false);
                return;
            }
        }

        store.onSystemMessage(text);
    }

    private static String stripColorCodes(String s) {
        if (s == null) return null;
        return s.replaceAll("\u00a7[0-9a-fk-or]", "");
    }

    private void handleDeath(TranslatableComponent tc, String key, String text) {
        String[] names;
        try {
            names = tc.arguments().stream()
                    .map(a -> stripColorCodes(Utils.toString(a.asComponent())))
                    .toArray(String[]::new);
        } catch (Exception ignore) {
            names = new String[0];
        }
        String victim = names.length > 0 ? names[0] : null;
        boolean isPlayerKill = key.contains("player") && names.length >= 2;
        String killer = isPlayerKill ? names[1] : null;
        store.onDeath(victim, killer);
        store.onSystemMessage(text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisconnect(DisconnectEvent e) {
        store.notifyLogin(); // 断开后进入重连，抑制对账误清理
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPacket(ReceivePacketEvent e) {
        Object packet = e.getPacket();
        if (packet instanceof ClientboundLoginPacket) {
            store.notifyLogin();
        } else if (packet instanceof ClientboundPlayerChatPacket) {
            handlePlayerChat((ClientboundPlayerChatPacket) packet);
        } else if (packet instanceof ClientboundPingPacket) {
            handlePing((ClientboundPingPacket) packet);
        } else if (packet instanceof ClientboundHelloPacket) {
            handleHello();
        }
    }

    /**
     * 离线模式(无 access token)下，部分裂痕服仍会发送加密请求(ClientboundHelloPacket 且
     * shouldAuthenticate=true)。mcprotocollib 的 ClientListener 在无 token 时会抛
     * UnexpectedEncryptionException 断开。这里在 ClientListener 处理之前补上占位 token
     * 和无操作 SessionService，让加密握手正常完成、服务器回退到离线登录。
     */
    private void handleHello() {
        org.geysermc.mcprotocollib.network.ClientSession session = Bot.INSTANCE.getSession();
        if (session == null) return;
        try {
            if (session.getFlag(MinecraftConstants.ACCESS_TOKEN_KEY) == null) {
                session.setFlag(MinecraftConstants.ACCESS_TOKEN_KEY, "offline");
                session.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService() {
                    @Override
                    public void joinServer(GameProfile profile, String authenticationToken, String serverId) {
                        // 不向 Mojang 认证，裂痕服会回退为离线登录
                    }
                });
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 1.21.2+ 将 keepalive 由 ClientboundKeepAlivePacket 改为 ClientboundPingPacket，
     * 而 mcprotocollib 的 ClientListener 只自动回应旧的 KeepAlive，未回应 Ping，
     * 导致服务器超时踢出（连接超时）。这里手动回应 Pong。
     */
    private void handlePing(ClientboundPingPacket ping) {
        org.geysermc.mcprotocollib.network.ClientSession session = Bot.INSTANCE.getSession();
        if (session != null && session.isConnected()) {
            try {
                session.send(new ServerboundPongPacket(ping.getId()));
            } catch (Exception ignore) {
            }
        }
    }

    private void handlePlayerChat(ClientboundPlayerChatPacket chat) {
        String content;
        if (chat.getUnsignedContent() != null) {
            content = Utils.toString(chat.getUnsignedContent());
        } else {
            content = chat.getContent();
        }
        String name = null;
        GameProfile gp = null;
        if (chat.getSender() != null) {
            gp = Bot.INSTANCE.players.get(chat.getSender());
            if (gp != null) name = gp.getName();
        }
        if (name == null || name.isEmpty()) {
            name = Utils.toString(chat.getName());
        }
        boolean premium = gp != null && DataStore.isPremium(gp);
        store.onPlayerChat(name, content, premium);
    }

    /**
     * 服务器使用 AuthMe 登录系统，未登录会被踢出。
     * 检测到注册/登录提示时，使用根目录 config.conf 中的账号密码自动应答。
     */
    private void maybeAuthenticate(String text) {
        if (text == null) return;
        String lower = text.toLowerCase();
        boolean register = lower.contains("/register");
        boolean login = lower.contains("/login");
        if (!register && !login) return;
        String pw = getPassword();
        if (pw.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAuthAttempt < 10000L) return;
        lastAuthAttempt = now;
        if (register) {
            Bot.INSTANCE.sendCommand("register " + pw + " " + pw);
        } else {
            Bot.INSTANCE.sendCommand("login " + pw);
        }
    }

    private String getPassword() {
        String p = password;
        if (p == null) {
            try {
                p = Bot.INSTANCE.getConfig().getConfigData().getAccount().getPassword();
            } catch (Exception e) {
                p = null;
            }
            if (p == null) p = "";
            // 仅允许安全字符，避免命令注入
            p = p.replaceAll("[^a-zA-Z0-9_.\\-]", "");
            password = p;
        }
        return password;
    }
}
