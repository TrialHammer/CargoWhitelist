# CargoWhitelist

Spigot 白名单插件，支持主从两种来源：

- **master（主机）**：从腾讯文档读取白名单，同步到本地，并通过 MediaWiki Bot 增量写入 wiki。
- **slave（从机）**：沿用原逻辑，从 wiki 的 `verified-players` / `revoked-players` 接口抓取。

同一套部署中，只有主机执行同步；从机读 wiki 即可跟随。

## 配置

`src/main/resources/config.yml` 只有占位符；真实值写入**本地** `config.local.yml`（已 gitignore，不提交）。
部署时把它复制到服务器的 `plugins/CargoWhitelist/config.yml`。jar 内**不含任何密钥**。

关键项：

```yaml
mode: master            # master | slave

tencent:                # 仅 master
  client-id: ""
  open-id: ""
  access-token: ""      # 会过期；配 refresh-token + client-secret 可自动刷新
  source: auto          # auto | range | smartsheet | export
  file-id: ""           # 或 encoded-id
  encoded-id: ""
  header-row: 1
  columns: { id: "", premium: "", bedrock: "", date: "", note: "",
             issued: "", valid: "", revoke-reason: "", revoke-time: "" }
  status: { active-values: [], revoked-values: [] }

wiki:                   # 仅 master
  enabled: true
  api-url: "https://wiki.example.com/w/api.php"
  username: "用户名@机器人名"
  password: ""
  page: "项目:页面名"
  auto-revoke: true
```

- **来源读取**：`auto` 依次尝试 `range`(普通表格) → `smartsheet`(智能表) → `export`(导出 xlsx，每天 9 次)。
  “收集表结果”通常是智能表，走 `smartsheet`，无次数限制，可 `update-period: 3600`。
- **手动白名单**：`manual-whitelist` 列表中的玩家**始终放行**，不受腾讯文档/wiki 同步影响，也不会被同步移除。
- **限频冷却**：触发 `Request Is Over Rate`（`9998`/`400007`/HTTP 429）后，冷却至**下一个自然日 23:59（北京时间）**；开始时间记在 `tencent-ratelimit.txt`，冷却期内**重复触发不重置**，期间跳过同步（不删数据）。
- **状态判定**（本表）：`是否发放白名单=是` 且 `白名单是否仍然有效=是` → 在册；发放=是但有效≠是 → 撤销；发放=否 → 忽略。同一 ID 多次提交时，任一在册即视为在册。

## Wiki 同步（增量）

- 在册、wiki 无 → 追加 `{{ VerifiedPlayer }}`；在册、wiki 原为 `RevokedPlayer` → 改回。
- 撤销、wiki 现为 `VerifiedPlayer` → 改为 `{{ RevokedPlayer }}` 并在备注追加撤销说明（**才叫撤销**）。
- **撤销只作用于已存在的 wiki 条目**：从没加入过 wiki 的 ID 标了撤销也不产生改动；撤销不删行。
- 表格中“缺失”不等于撤销，不会误删；无变更时不写入。

## 构建

```bash
./build.sh          # = mvn clean package；产出的 jar 内 config.yml 为占位，不含密钥
# 产物：target/CargoWhitelist-*.jar
```
