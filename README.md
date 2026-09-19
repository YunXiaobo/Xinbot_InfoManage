# Xinbot_InfoManage
为Xinbot打造的类似3c3u.info的插件，能够查询服务器状态、玩家信息等。

## 功能

- 在本地创建一个网页端服务，用于展示服务器信息及玩家信息
- 自动记录聊天量信息
- 网页端显示所有在线玩家和玩家数据
- 可以作为一个元插件利用，在配置文件中可以选择要加入的服务器IP和Host

## 安装

1. 安装前置插件 [XinVia](https://github.com/huangdihd/XinVia)
2. 将 InfoManage JAR 放入 Xinbot 的 `plugins` 文件夹。
3. 启动一次 Xinbot，生成 `.\plugin\InfoManage`。
4. 编辑 `config.conf`。
5. 重载或重启 Xinbot。

## 配置文件
`config.conf` 示例：

```
#InfoManage plugin config - server connection info only (not player account info)
#Wed Aug 26 15:42:36 CST 2026
feed.maxEntris=500
server.host=3c3uorg
server.port=25565
server.protocol=774
title=服务器状态
web.host=127.0.0.1
web.port=8080
```

# InfoManage — Xinbot 普通插件

InfoManage 是 [Xinbot](https://github.com/huangdihd/Xinbot) 的元插件（META_PLUGIN）。插件作为机器人加入目标 Minecraft 服务器，加入成功后在本地启动一个 Web 页面，实时展示在线玩家与聊天信息流，并将所有玩家的在线记录持久化到本地。

## 技术栈

- Java 21
- Xinbot 2.4.2-RELEASE（插件宿主，classpath 来源）
- mcprotocollib（随 Xinbot fat jar 提供，Minecraft 协议实现）
- JDK 内置 `com.sun.net.httpserver.HttpServer`（Web 服务，无第三方框架依赖）
- Gson（随 Xinbot fat jar 提供，JSON 序列化）

## 目录结构

```
INFO_Penguin/
└── src/
    ├── plugin.yml                  # 插件描述文件（打包后位于 jar 根目录）
    └── com/infomanage/
        ├── InfoManagePlugin.java   # 插件入口，实现 MetaPlugin
        ├── PluginConfig.java       # plugin/InfoManage/config.conf 读写
        ├── InfoEventListener.java  # Xinbot 高层事件 → DataStore 数据变更
        ├── DataStore.java          # 实时状态维护 + 持久化 + 周期对账
        ├── PlayerRecord.java       # 单个玩家的档案数据
        ├── FeedEntry.java          # 信息流（聊天/系统/加入/离开）条目
        └── WebServer.java          # 本地 Web 服务与页面
```

运行时数据目录（与机器人根目录的账号配置完全分离）：

```
plugin/InfoManage/
├── config.conf    # 目标服务器连接信息
├── players.json   # 全服玩家档案（跨重启保留）
├── online.log     # 玩家 JOIN/LEAVE 记录（跨重启保留）
└── pinned.txt     # 置顶玩家名单（跨重启保留）
```

## 配置文件

`plugin/InfoManage/config.conf`（Properties 格式，UTF-8）：

| 键 | 说明 | 默认值 |
|---|---|---|
| `server.host` | 目标服务器地址（必填，留空时回退 127.0.0.1） | 空 |
| `server.port` | 目标服务器端口 | `25565` |
| `server.protocol` | 目标服务器协议版本号（如 `767` = 1.21.1，`774` = 1.21.11） | `774` |
| `web.host` | 本地 Web 服务监听地址 | `127.0.0.1` |
| `web.port` | 本地 Web 服务端口 | `8080` |
| `feed.maxEntries` | 信息流内存缓存的最大条数 | `500` |
| `title` | 页面标题 | `服务器状态` |

注意：此文件只包含**连接服务器**的网络信息；机器人的账号名、密码等仍由 Xinbot 根目录的 `config.conf` 管理，两者互不相干。

## 安装与使用

1. 将 `InfoManage_0.0.1.jar` 放入 Xinbot 的 `plugin/` 目录。
2. 首次启动后，编辑 `plugin/InfoManage/config.conf` 填写目标服务器地址、端口与协议版本，重启机器人。
3. 机器人成功加入服务器后，浏览器访问 `http://<web.host>:<web.port>` 即可看到实时页面。

## 常用命令

## Web 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 信息展示页面 |
| GET | `/api/state` | 标题、在线人数、注册玩家数、在线玩家列表、信息流、置顶名单 |
| POST | `/api/pin` | 设置/取消玩家置顶（表单参数 `name`、`pin`） |
| GET | `/api/player?name=<玩家名>` | 查询单个玩家的详细档案 |

## 实现约定

- **机器人自身不计入在线列表**：`DataStore` 通过 Xinbot 账号名过滤采集端自身，页面展示的是服务器上的其他玩家。
- **正版/离线判定**：离线模式服务器中，离线玩家 UUID 由 `OfflinePlayer:<名字>` 派生，与真实 Mojang UUID 不同；插件据此比对 UUID 判定验证状态，不依赖皮肤纹理签名。
- **重连防误报**：收到登录包后抑制一段时间的在线列表清理，避免重连时 tab 列表清空重建产生大量误报；断线后按 Xinbot 配置自动重连。
- **周期对账**：每 5 秒与 Xinbot 维护的 tab 列表对账，每 10 秒按需保存一次玩家档案。

## 安全措施

- 置顶接口校验 `Origin` 与 `Host` 一致，跨站请求返回 403（CSRF 防护）。
- POST 请求体限制为 4KB。
- 皮肤 URL 仅接受 `http(s)` 且禁止引号、反斜杠、尖括号与空白字符，防止 CSS 注入。
- Web 服务只提供固定路由，不提供任意文件读取，避免路径穿越。
- 定时任务捕获全部异常，防止周期执行静默终止。
- 自动登录密码仅允许字母、数字与 `_.-` 字符，避免命令注入。

## 插件依赖

`plugin.yml` 中声明 `softdepend: XinVia`。当目标服务器协议与 Xinbot 核心协议不一致时（如核心为 774、目标为 767），插件反射调用 XinVia 完成跨版本桥接；XinVia 不存在时跳过桥接，仅支持同版本服务器。
