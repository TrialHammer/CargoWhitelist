# 设置白名单同步

以[默认配置](../src/main/resources/config.yml)为字段参考，在服务器的 `plugins/CargoWhitelist/config.yml` 中配置实际值。仓库的 `config.local.yml` 可作为本地部署副本，已被 Git 忽略。

## 选择节点角色

**主节点**使用 `mode: master`。填写 `tencent` 的应用凭据、文档 ID 和表头映射。普通在线表格选 `range`，智能表选 `smartsheet`。不确定时可先用 `auto`，它会依次尝试普通表、智能表和导出。若启用 Wiki 写入，填写实际 MediaWiki API 地址、机器人账号、密码和目标页面。

**从节点**使用 `mode: slave`。将 `verified-players` 和 `revoked-players` 指向 MediaWiki Cargo 的导出接口，返回字段需包含 `ID`。设置足够的 `limit`，让接口能返回完整名单。从节点不会主动同步写入 Wiki。若不希望从节点上的 `/cw add` 写入 Wiki，把该节点的 `wiki.enabled` 设为 `false`。

## 理解同步结果

状态取决于所配表头。使用发放与有效期两列时，已发放且仍有效表示在册，已发放但已失效表示撤销，未发放则忽略。同一玩家有多行记录时，任一在册记录有效。表中缺失不等于撤销。插件增量更新现有 Wiki 记录，不会因缺行删除名单。

`/cw add` 会立即在本服放行玩家。当前节点启用 Wiki 写入且设置了目标页面时，也会尝试同步手动加入项。`/cw remove` 只取消手动放行，不会阻止玩家通过同步白名单登录。管理命令需要 `cargowhitelist.admin`，默认给 OP。

同步缓存、手动名单和刷新的令牌写入 `data.yml`，不会回写 `config.yml`。腾讯文档限流时，插件跳过同步、保留上次名单，冷却结束后再恢复。识别出表格类型后，用对应的数据源可避免无谓地尝试导出。
