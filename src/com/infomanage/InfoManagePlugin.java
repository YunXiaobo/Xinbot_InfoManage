package com.infomanage;

import io.netty.channel.Channel;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.mcbot.plugin.MetaPlugin;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * InfoManage 元插件：
 * 1. 作为元插件加入目标服务器（地址来自 plugin/InfoManage/config.conf）；
 * 2. 成功进入服务器后，在本地启动一个网页服务，实时展示聊天、在线人数、
 *    正版/离线校验等信息，并持久化玩家在线记录。
 */
public class InfoManagePlugin implements MetaPlugin {
    private static final Logger log = LoggerFactory.getLogger("InfoManage");

    private PluginConfig config;
    private DataStore store;
    private InfoEventListener listener;
    private WebServer webServer;
    private volatile boolean webStarted = false;

    // XinVia 跨版本桥接状态（反射调用，不直接依赖 XinVia 类，避免缺插件时崩溃）
    private volatile boolean viaSetupDone = false;
    private Object viaUserConnection;

    @Override
    public void onLoad() {
        try {
            File dataDir = new File("plugin/InfoManage");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
            this.config = PluginConfig.load(dataDir);
            this.store = new DataStore(config);
            if (isHostEmpty()) {
                log.warn("⚠ config.conf 尚未配置服务器地址！请在 plugin/InfoManage/config.conf 中填写 server.host 与 server.port 后重启机器人。");
            } else {
                log.info("InfoManage 配置加载完成，目标服务器 {}:{}", config.serverHost, config.serverPort);
            }
        } catch (Exception e) {
            log.error("InfoManage 加载失败", e);
        }
    }

    @Override
    public void onEnable() {
        try {
            if (store == null) {
                store = new DataStore(config != null ? config : PluginConfig.load(new File("plugin/InfoManage")));
            }
            store.start();
            this.listener = new InfoEventListener(store);
            Bot.INSTANCE.getPluginManager().registerEvents(this.listener, this);
            if (!webStarted) {
                this.webServer = new WebServer(config, store);
                this.webServer.start();
                webStarted = true;
            }
            setupViaBridge();
            log.info("InfoManage 已启用");
        } catch (Exception e) {
            log.error("InfoManage 启用失败", e);
        }
    }

    @Override
    public void onDisable() {
        try {
            viaSetupDone = false;
            ClientSession session = Bot.INSTANCE.getSession();
            Channel channel = session != null ? session.getChannel() : null;
            callStaticQuiet("xin.bbtt.via.XinViaProvider", "teardown", channel, viaUserConnection);
            viaUserConnection = null;
            if (listener != null) {
                Bot.INSTANCE.getPluginManager().events().unregisterAll(this);
                listener = null;
            }
            if (store != null) {
                store.shutdown();
            }
            // 网页服务不随断开连接而停止，保持运行以便在重连期间持续提供服务
        } catch (Exception e) {
            log.error("InfoManage 停用失败", e);
        }
    }

    @Override
    public void onUnload() {
        try {
            if (webServer != null) {
                webServer.stop();
                webServer = null;
                webStarted = false;
            }
            if (store != null) {
                store.shutdown();
            }
            log.info("InfoManage 已卸载，数据已保存");
        } catch (Exception e) {
            log.error("InfoManage 卸载失败", e);
        }
    }

    @Override
    public SocketAddress getServerSocketAddress() {
        if (config == null) {
            config = PluginConfig.load(new File("plugin/InfoManage"));
        }
        if (isHostEmpty()) {
            log.error("未配置目标服务器！请在 plugin/InfoManage/config.conf 中填写 server.host 与 server.port 后重启机器人。");
            // 回退到本地地址，避免空地址导致崩溃；配置后即按配置连接
            return new InetSocketAddress("127.0.0.1", config.serverPort);
        }
        return new InetSocketAddress(config.serverHost, config.serverPort);
    }

    private boolean isHostEmpty() {
        return config == null || config.serverHost == null || config.serverHost.trim().isEmpty();
    }

    /**
     * 通过 XinVia 桥接客户端协议(774)到目标服务器协议(config.server.protocol)。
     * 在首个出站包时拿到 Netty Channel 并安装翻译编解码器，从而支持连接不同版本的离线服务器。
     * 通过反射调用，若 XinVia 插件未加载则跳过桥接，仅支持同版本服务器。
     */
    private void setupViaBridge() {
        viaSetupDone = false;
        try {
            Bot.INSTANCE.addPacketListener(new SessionAdapter() {
                @Override
                public void packetSending(PacketSendingEvent event) {
                    if (viaSetupDone) return;
                    Channel channel = event.getSession().getChannel();
                    if (channel != null) {
                        viaSetupDone = true;
                        try {
                            Object serverVersion = callStatic(
                                    "com.viaversion.viaversion.api.protocol.version.ProtocolVersion",
                                    "getProtocol", config.serverProtocol);
                            viaUserConnection = callStatic("xin.bbtt.via.XinViaProvider", "setupClient",
                                    channel, serverVersion);
                            if (viaUserConnection != null) {
                                log.info("XinVia 跨版本桥接已启用，目标协议 {}", config.serverProtocol);
                            } else {
                                log.warn("XinVia 尚未就绪，本次连接不做跨版本翻译");
                            }
                        } catch (Throwable e) {
                            log.error("XinVia 初始化失败，跳过跨版本翻译：{}", e.toString());
                        }
                    }
                }
            }, this);
        } catch (Throwable e) {
            log.warn("XinVia 插件不可用，仅支持连接 1.21.11 同版本服务器：{}", e.toString());
        }
    }

    /** 反射调用静态方法（按名称+参数个数匹配，避免版本差异） */
    private static Object callStatic(String clsName, String methodName, Object... args) throws Exception {
        Class<?> cls = Class.forName(clsName);
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == args.length
                    && Modifier.isStatic(m.getModifiers())) {
                try {
                    return m.invoke(null, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new RuntimeException(cause);
                }
            }
        }
        throw new NoSuchMethodException(clsName + "#" + methodName);
    }

    private static void callStaticQuiet(String clsName, String methodName, Object... args) {
        try {
            callStatic(clsName, methodName, args);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public Server getServer(ClientboundLoginPacket loginPacket) {
        return Server.Game;
    }
}
