# CargoWhitelist

CargoWhitelist 把腾讯文档里的白名单同步到 Minecraft 服务器。需要多台服务器共享名单时，由一台主节点写入 MediaWiki，其余节点从 Wiki 读取，避免多台服重复同步。

## 部署

运行 `./build.sh` 构建，在 `target/` 取得 JAR 并放入服务端的 `plugins/`。启动一次生成配置后，按[同步配置指南](docs/configuration.md)设置主节点或从节点。凭据和部署地址只填在部署文件中，不提交到仓库。

管理员用 `/cw add <玩家>` 和 `/cw remove <玩家>` 管理手动放行名单。用 `/cw list` 查看名单，用 `/cw reload` 重载。同步记录不会移除手动放行。
