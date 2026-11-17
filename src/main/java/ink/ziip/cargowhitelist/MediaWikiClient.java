package ink.ziip.cargowhitelist;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 MediaWiki API（Bot 账号）增量同步「玩家列表」页面。
 * <p>
 * 增量策略：
 * <ul>
 *   <li>腾讯文档中“在册”但页面没有的玩家 -> 追加一条 {{ VerifiedPlayer }}；</li>
 *   <li>腾讯文档中“撤销”、页面仍为在册的玩家 -> 改为 {{ RevokedPlayer }} 并在备注追加撤销说明；</li>
 *   <li>不会因为“玩家没出现在表格里”而撤销，避免表格不完整时误删。</li>
 * </ul>
 */
public class MediaWikiClient {

    private static final String UA = "CargoWhitelist/1.0 (wiki-sync)";
    private static final Pattern ROW_RE =
            Pattern.compile("^\\s*\\{\\{\\s*(VerifiedPlayer|RevokedPlayer)\\s*\\|(.*)\\}\\}\\s*$");

    private final OkHttpClient client;
    private final Config.Wiki cfg;
    private String csrfToken;

    public MediaWikiClient(Config.Wiki cfg) {
        this.cfg = cfg;
        this.client = new OkHttpClient.Builder()
                .cookieJar(new SimpleCookieJar())
                .followRedirects(true)
                .build();
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** @return 是否实际修改了页面 */
    public boolean sync(List<WhitelistEntry> entries) throws IOException {
        login();

        Map<String, WhitelistEntry> active = new LinkedHashMap<>();
        Map<String, String> revoked = new LinkedHashMap<>();
        for (WhitelistEntry e : entries) {
            if (!e.isValid()) continue;
            if (e.isActive()) {
                active.put(e.getId(), e);
            } else {
                revoked.put(e.getId(), e.getNote());
            }
        }

        String wikitext = getWikitext();
        DiffResult diff = applyDiff(wikitext, active, revoked, cfg.autoRevoke);
        if (!diff.changed) {
            CargoWhitelist.getInstance().getLogger().info("[Wiki] 无变更，跳过写入");
            return false;
        }
        save(diff.text);
        CargoWhitelist.getInstance().getLogger().info("[Wiki] 已同步玩家列表页面");
        return true;
    }

    /**
     * 确保该玩家在页面上是“在册”（用于 /cw add）：
     * 不存在则追加 {{VerifiedPlayer}}，若原为 {{RevokedPlayer}} 则改回。
     * 不会影响其它条目。
     */
    public boolean addEntry(WhitelistEntry e) throws IOException {
        login();
        String wikitext = getWikitext();
        Map<String, WhitelistEntry> active = new LinkedHashMap<>();
        active.put(e.getId(), e);
        DiffResult diff = applyDiff(wikitext, active, new LinkedHashMap<>(), cfg.autoRevoke);
        if (!diff.changed) {
            return false;
        }
        save(diff.text);
        return true;
    }

    // ------------------------------------------------------------------
    // 差异计算
    // ------------------------------------------------------------------

    static final class DiffResult {
        final String text;
        final boolean changed;

        DiffResult(String text, boolean changed) {
            this.text = text;
            this.changed = changed;
        }
    }

    static DiffResult applyDiff(String content, Map<String, WhitelistEntry> active,
                                Map<String, String> revoked, boolean autoRevoke) {
        String[] lines = content.split("\n", -1);
        int firstRow = -1;
        for (int i = 0; i < lines.length; i++) {
            if (parseRow(lines[i]) != null) {
                firstRow = i;
                break;
            }
        }
        if (firstRow < 0) {
            return new DiffResult(content, false);
        }
        int closeIdx = -1;
        for (int i = firstRow; i < lines.length; i++) {
            if (lines[i].trim().equals("|}")) {
                closeIdx = i;
                break;
            }
        }
        if (closeIdx < 0) {
            return new DiffResult(content, false);
        }

        Map<String, Integer> verifiedIdx = new LinkedHashMap<>();
        Map<String, Integer> revokedIdx = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            Row r = parseRow(lines[i]);
            if (r == null) continue;
            String id = r.params.get("ID");
            if (id == null) continue;
            if ("VerifiedPlayer".equals(r.type)) {
                verifiedIdx.putIfAbsent(id, i);
            } else {
                revokedIdx.putIfAbsent(id, i);
            }
        }

        boolean changed = false;
        String today = LocalDate.now().toString();

        // 0) 自愈：VerifiedPlayer 行不该带有“自动撤销”备注（历史遗留的错配）
        for (Map.Entry<String, Integer> e : verifiedIdx.entrySet()) {
            Row r = parseRow(lines[e.getValue()]);
            if (r == null) continue;
            String note = r.params.get("备注");
            if (note != null && note.contains(AUTO_REVOKE_MARK)) {
                Map<String, String> p = new LinkedHashMap<>(r.params);
                String stripped = stripAutoRevoke(note);
                if (stripped.isEmpty()) {
                    p.remove("备注");
                } else {
                    p.put("备注", stripped);
                }
                lines[e.getValue()] = render("VerifiedPlayer", p);
                changed = true;
            }
        }

        // 1) 撤销：腾讯文档标记撤销、但页面仍是 Verified
        if (autoRevoke) {
            for (Map.Entry<String, String> rv : revoked.entrySet()) {
                String id = rv.getKey();
                Integer idx = verifiedIdx.get(id);
                if (idx == null) continue;
                Row r = parseRow(lines[idx]);
                if (r == null) continue;
                Map<String, String> p = new LinkedHashMap<>(r.params);
                String addNum = today + " 自动撤销（腾讯文档同步）";
                if (rv.getValue() != null && !rv.getValue().trim().isEmpty()) {
                    addNum += "：" + rv.getValue().trim();
                }
                String oldNote = stripAutoRevoke(p.get("备注"));
                p.put("备注", oldNote.isEmpty() ? addNum : oldNote + "；" + addNum);
                lines[idx] = render("RevokedPlayer", p);
                changed = true;
            }
        }

        // 2) 新增 / 重新在册
        List<String> newLines = new ArrayList<>();
        for (Map.Entry<String, WhitelistEntry> en : active.entrySet()) {
            String id = en.getKey();
            if (verifiedIdx.containsKey(id)) {
                continue; // 已在册
            }
            Integer rIdx = revokedIdx.get(id);
            if (rIdx != null) {
                // 之前已撤销，现重新在册：改回 Verified，并移除历史“自动撤销”备注
                Row r = parseRow(lines[rIdx]);
                if (r != null) {
                    Map<String, String> p = new LinkedHashMap<>(r.params);
                    String stripped = stripAutoRevoke(p.get("备注"));
                    if (stripped.isEmpty()) {
                        p.remove("备注");
                    } else {
                        p.put("备注", stripped);
                    }
                    lines[rIdx] = render("VerifiedPlayer", p);
                    changed = true;
                }
                continue;
            }
            newLines.add(render("VerifiedPlayer", entryToParams(en.getValue())));
        }
        if (!newLines.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (i == closeIdx) out.addAll(newLines);
                out.add(lines[i]);
            }
            lines = out.toArray(new String[0]);
            changed = true;
        }

        return new DiffResult(String.join("\n", lines), changed);
    }

    private static Map<String, String> entryToParams(WhitelistEntry e) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ID", e.getId());
        if (!e.getPremium().isEmpty()) p.put("是否正版", e.getPremium());
        if (!e.getBedrock().isEmpty()) p.put("是否基岩版", e.getBedrock());
        if (!e.getDate().isEmpty()) p.put("添加日期", e.getDate());
        if (!e.getOperator().isEmpty()) p.put("添加人", e.getOperator());
        if (!e.getNote().isEmpty()) p.put("备注", e.getNote());
        return p;
    }

    private static final String AUTO_REVOKE_MARK = "自动撤销（腾讯文档同步）";

    /** 从备注中剔除所有“自动撤销（腾讯文档同步）…”片段，恢复为原始人工备注。 */
    private static String stripAutoRevoke(String note) {
        if (note == null || note.isEmpty()) return "";
        String[] parts = note.split("[；;]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.contains(AUTO_REVOKE_MARK)) continue;
            String s = part.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(s);
        }
        return sb.toString();
    }

    private static final class Row {
        final String type;
        final Map<String, String> params;

        Row(String type, Map<String, String> params) {
            this.type = type;
            this.params = params;
        }
    }

    private static Row parseRow(String line) {
        Matcher m = ROW_RE.matcher(line);
        if (!m.matches()) return null;
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : m.group(2).split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if (!k.isEmpty()) params.put(k, v);
            }
        }
        return new Row(m.group(1), params);
    }

    private static String render(String type, Map<String, String> params) {
        StringBuilder sb = new StringBuilder("{{ ").append(type);
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sb.append(" | ").append(e.getKey()).append(" = ").append(e.getValue());
        }
        sb.append(" }}");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 登录 / 读取 / 保存
    // ------------------------------------------------------------------

    private void login() throws IOException {
        JSONObject tokenResp = httpGet(params(
                "action", "query", "meta", "tokens", "type", "login", "format", "json"));
        String loginToken = tokenResp.getJSONObject("query").getJSONObject("tokens").getString("logintoken");

        FormBody body = new FormBody.Builder()
                .add("action", "login")
                .add("lgname", cfg.username)
                .add("lgpassword", cfg.password)
                .add("lgtoken", loginToken)
                .add("format", "json")
                .build();
        JSONObject resp = httpPost(body);
        JSONObject login = resp.getJSONObject("login");
        String result = login == null ? null : login.getString("result");
        if (!"Success".equals(result) && !"PASS".equals(result)) {
            throw new IOException("Wiki 登录失败: " + resp);
        }

        JSONObject csrf = httpGet(params(
                "action", "query", "meta", "tokens", "type", "csrf", "format", "json"));
        csrfToken = csrf.getJSONObject("query").getJSONObject("tokens").getString("csrftoken");
        if (csrfToken == null || csrfToken.isEmpty()) {
            throw new IOException("获取 CSRF token 失败");
        }
    }

    private String getWikitext() throws IOException {
        JSONObject resp = httpGet(params(
                "action", "parse", "page", cfg.page, "prop", "wikitext",
                "format", "json", "formatversion", "2"));
        JSONObject parse = resp.getJSONObject("parse");
        if (parse == null || parse.getString("wikitext") == null) {
            throw new IOException("读取页面失败: " + resp);
        }
        return parse.getString("wikitext");
    }

    private void save(String text) throws IOException {
        FormBody body = new FormBody.Builder()
                .add("action", "edit")
                .add("title", cfg.page)
                .add("text", text)
                .add("summary", cfg.summary)
                .add("token", csrfToken)
                .add("format", "json")
                .build();
        JSONObject resp = httpPost(body);
        JSONObject edit = resp.getJSONObject("edit");
        String result = edit == null ? null : edit.getString("result");
        if (!"Success".equals(result)) {
            throw new IOException("保存页面失败: " + resp);
        }
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private JSONObject httpGet(Map<String, String> query) throws IOException {
        HttpUrl.Builder ub = HttpUrl.parse(cfg.apiUrl).newBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            ub.addQueryParameter(e.getKey(), e.getValue());
        }
        Request req = new Request.Builder().url(ub.build()).header("User-Agent", UA).get().build();
        return execute(req);
    }

    private JSONObject httpPost(FormBody body) throws IOException {
        Request req = new Request.Builder().url(cfg.apiUrl).header("User-Agent", UA).post(body).build();
        return execute(req);
    }

    private JSONObject execute(Request req) throws IOException {
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            try {
                return JSON.parseObject(body);
            } catch (Exception e) {
                throw new IOException("Wiki 返回非 JSON(" + resp.code() + "): " + body, e);
            }
        }
    }

    /** 简单的内存 CookieJar，保存 MediaWiki 会话 Cookie。 */
    private static final class SimpleCookieJar implements CookieJar {
        private final List<Cookie> store = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            store.addAll(cookies);
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (store) {
                for (Cookie c : store) {
                    if (c.expiresAt() >= now && c.matches(url)) {
                        out.add(c);
                    }
                }
            }
            return out;
        }
    }
}
