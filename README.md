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
