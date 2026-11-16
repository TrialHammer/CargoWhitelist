package ink.ziip.cargowhitelist;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 腾讯文档在线表格客户端。
 * <p>
 * 支持两种读取方式：
 * <ul>
 *   <li><b>range</b>：在线表格 V3 接口（普通表格，按区域读取）；</li>
 *   <li><b>export</b>：异步导出为 xlsx 后本地解析（适用于“收集表结果”这类 V3 接口无法枚举的特殊表格）。</li>
 * </ul>
 * 配置 {@code tencent.source} 为 auto/range/export；auto 时优先 range，失败则回退 export。
 */
public class TencentDocsClient {

    private static final String BASE = "https://docs.qq.com";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ROWS_PER_REQ = 1000;
    private static final int MAX_COLS_PER_REQ = 200;
    private static final int MAX_CELLS_PER_REQ = 10000;

    private final OkHttpClient client;
    private final Config.Tencent cfg;

    public TencentDocsClient(Config.Tencent cfg) {
        this.cfg = cfg;
        this.client = new OkHttpClient();
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    public List<WhitelistEntry> fetchWhitelist() throws IOException {
        if (RateLimitGuard.inCooldown()) {
            long ms = RateLimitGuard.remainingMs();
            warn("腾讯文档处于限频冷却中，剩余约 " + (ms / 3600000) + " 小时 "
                    + (ms % 3600000 / 60000) + " 分，本轮跳过");
            return new ArrayList<>();
        }
        ensureToken();
        String fileId = resolveFileId();
        String source = cfg.source == null ? "auto" : cfg.source.toLowerCase();
        List<Raw> raws = new ArrayList<>();

        // 1) 普通在线表格（range）
        if (!"export".equals(source) && !"smartsheet".equals(source)) {
            List<SheetRef> sheets = listSheets(fileId);
            if (!sheets.isEmpty()) {
                if (!cfg.sheets.isEmpty()) {
                    for (Config.SheetSpec spec : cfg.sheets) {
                        SheetRef ref = findSheet(sheets, spec);
                        if (ref == null) {
                            warn("未找到子表: " + (spec.title.isEmpty() ? spec.id : spec.title));
                            continue;
                        }
                        raws.addAll(parseMatrix(readRangeMatrix(fileId, ref.sheetId, ref.rowCount, ref.columnCount)));
                    }
                } else if (!cfg.sheetId.isEmpty()) {
                    SheetRef ref = findSheet(sheets, null);
                    raws.addAll(parseMatrix(readRangeMatrix(fileId,
                            ref != null ? ref.sheetId : cfg.sheetId,
                            ref != null ? ref.rowCount : 0,
                            ref != null ? ref.columnCount : 0)));
                } else {
                    for (SheetRef ref : sheets) {
                        raws.addAll(parseMatrix(readRangeMatrix(fileId, ref.sheetId, ref.rowCount, ref.columnCount)));
                    }
                }
                return aggregate(raws);
            }
            if ("range".equals(source)) {
                throw new IOException("range 接口未返回任何子表");
            }
        }

        // 2) 智能表（smartsheet）—— 收集表结果等通常是这类，无导出次数限制
        if (!"export".equals(source)) {
            List<SmartRef> smart = listSmartSheets(fileId);
            if (!smart.isEmpty()) {
                for (SmartRef ref : smart) {
                    if (!cfg.sheetId.isEmpty() && !cfg.sheetId.equals(ref.sheetId)) continue;
                    raws.addAll(readSmartRecords(fileId, ref.sheetId));
                }
                return aggregate(raws);
            }
            if ("smartsheet".equals(source)) {
                throw new IOException("未找到任何智能表子表");
            }
        }

        // 3) 回退：导出 xlsx 解析
        CargoWhitelist.getInstance().getLogger().info("[Tencent] 使用导出方式读取（每用户每天限 9 次）");
        for (List<List<String>> m : exportMatrices(fileId)) {
            raws.addAll(parseMatrix(m));
        }
        return aggregate(raws);
    }

    /** 汇总：同一 ID 多行时，在册优先，其次撤销，仅被拒绝的行忽略。 */
    private List<WhitelistEntry> aggregate(List<Raw> raws) {
        Map<String, List<Raw>> byId = new LinkedHashMap<>();
        for (Raw raw : raws) {
            if (raw.id.isEmpty()) continue;
            byId.computeIfAbsent(raw.id, k -> new ArrayList<>()).add(raw);
        }
        List<WhitelistEntry> out = new ArrayList<>();
        for (Map.Entry<String, List<Raw>> en : byId.entrySet()) {
            Raw active = null;
            Raw revoked = null;
            for (Raw r : en.getValue()) {
                if (r.status == Status.ACTIVE && active == null) active = r;
                if (r.status == Status.REVOKED && revoked == null) revoked = r;
            }
            if (active != null) {
                out.add(toEntry(active, true));
            } else if (revoked != null) {
                out.add(toEntry(revoked, false));
            }
            // 仅 SKIP（被拒绝/空行）不输出
        }
        return out;
    }

    private WhitelistEntry toEntry(Raw r, boolean active) {
        String note;
        if (active) {
            note = r.note;
        } else {
            StringBuilder sb = new StringBuilder();
            if (!r.reason.isEmpty()) sb.append(r.reason);
            if (!r.revokeTime.isEmpty()) {
                if (sb.length() > 0) sb.append("，");
                sb.append(r.revokeTime);
            }
            note = sb.toString();
        }
        return new WhitelistEntry(r.id, r.premium, r.bedrock, r.date, cfg.operatorDefault, note, active);
    }

    // ------------------------------------------------------------------
    // 解析矩阵 -> 原始行
    // ------------------------------------------------------------------

    private enum Status {ACTIVE, REVOKED, SKIP}

    private static final class Raw {
        String id = "";
        String premium = "";
        String bedrock = "";
        String date = "";
        String note = "";
        String reason = "";
        String revokeTime = "";
        Status status = Status.ACTIVE;
    }

    private List<Raw> parseMatrix(List<List<String>> matrix) {
        List<Raw> out = new ArrayList<>();
        int hr = Math.max(0, cfg.headerRow - 1);
        if (matrix.size() <= hr) return out;
        List<String> header = matrix.get(hr);
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            if (h != null && !h.trim().isEmpty()) headerIndex.put(h.trim(), i);
        }

        int idIdx = resolveColumn(headerIndex, "id", "ID", "玩家ID", "游戏ID", "用户名");
        int premiumIdx = resolveColumn(headerIndex, "premium", "是否正版", "正版");
        int bedrockIdx = resolveColumn(headerIndex, "bedrock", "是否基岩版", "基岩版");
        int dateIdx = resolveColumn(headerIndex, "date", "添加时间", "添加日期", "提交时间", "时间", "日期");
        int noteIdx = resolveColumn(headerIndex, "note", "备注", "附加信息", "说明");
        int statusIdx = resolveColumn(headerIndex, "status", "状态", "白名单状态", "是否白名单");
        int issuedIdx = resolveColumn(headerIndex, "issued", "是否发放白名单");
        int validIdx = resolveColumn(headerIndex, "valid", "白名单是否仍然有效");
        int reasonIdx = resolveColumn(headerIndex, "revoke-reason", "白名单回收理由", "回收理由");
        int revokeTimeIdx = resolveColumn(headerIndex, "revoke-time", "白名单回收时间", "回收时间");

        if (idIdx < 0) idIdx = 0;

        boolean combined = issuedIdx >= 0 || validIdx >= 0;

        for (int r = hr + 1; r < matrix.size(); r++) {
            List<String> row = matrix.get(r);
            String id = cell(row, idIdx).trim();
            if (id.isEmpty()) continue;

            Raw raw = new Raw();
            raw.id = id;
            raw.premium = normalizeBool(cell(row, premiumIdx));
            raw.bedrock = normalizeBool(cell(row, bedrockIdx));
            raw.date = normalizeDate(cell(row, dateIdx));
            String n = cell(row, noteIdx).trim();
            raw.note = "无".equals(n) ? "" : n;
            raw.reason = cell(row, reasonIdx).trim();
            raw.revokeTime = normalizeDate(cell(row, revokeTimeIdx));

            if (combined) {
                String issued = cell(row, issuedIdx).trim();
                String valid = cell(row, validIdx).trim();
                if (isYes(issued)) {
                    raw.status = isYes(valid) ? Status.ACTIVE : Status.REVOKED;
                } else {
                    // 未发放（被拒绝）或空 -> 忽略
                    raw.status = Status.SKIP;
                }
            } else if (statusIdx >= 0) {
                String v = cell(row, statusIdx).trim();
                raw.status = resolveStatusValue(v) ? Status.ACTIVE : Status.REVOKED;
            } else {
                raw.status = Status.ACTIVE;
            }
            out.add(raw);
        }
        return out;
    }

    private boolean resolveStatusValue(String v) {
        if (v.isEmpty()) return true;
        for (String a : cfg.activeValues) {
            if (v.equalsIgnoreCase(a.trim())) return true;
        }
        for (String rv : cfg.revokedValues) {
            if (v.equalsIgnoreCase(rv.trim())) return false;
        }
        for (String rv : cfg.revokedValues) {
            if (!rv.trim().isEmpty() && v.contains(rv.trim())) return false;
        }
        for (String a : cfg.activeValues) {
            if (!a.trim().isEmpty() && v.contains(a.trim())) return true;
        }
        return true;
    }

    private int resolveColumn(Map<String, Integer> headerIndex, String key, String... aliases) {
        String configured = cfg.columns.get(key);
        if (configured != null && !configured.trim().isEmpty()) {
            Integer idx = headerIndex.get(configured.trim());
            if (idx != null) return idx;
        }
        for (String alias : aliases) {
            Integer idx = headerIndex.get(alias);
            if (idx != null) return idx;
        }
        return -1;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || row == null || idx >= row.size()) return "";
        String v = row.get(idx);
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------------
    // range 方式读取
    // ------------------------------------------------------------------

    private List<List<String>> readRangeMatrix(String fileId, String sheetId, int rowCount, int colCount) throws IOException {
        int rows = rowCount > 0 ? rowCount : 2000;
        int cols = colCount > 0 ? colCount : 40;
        cols = Math.min(Math.max(cols, 1), MAX_COLS_PER_REQ);
        List<List<String>> matrix = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            List<String> line = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) line.add("");
            matrix.add(line);
        }
        int rowChunk = Math.min(MAX_ROWS_PER_REQ, Math.max(1, MAX_CELLS_PER_REQ / cols));
        int colChunk = Math.min(MAX_COLS_PER_REQ, cols);
        for (int r0 = 0; r0 < rows; r0 += rowChunk) {
            int r1 = Math.min(rows, r0 + rowChunk);
            for (int c0 = 0; c0 < cols; c0 += colChunk) {
                int c1 = Math.min(cols, c0 + colChunk);
                String range = colLetter(c0) + (r0 + 1) + ":" + colLetter(c1 - 1) + r1;
                fillRange(fileId, sheetId, range, matrix);
            }
        }
        return matrix;
    }

    private void fillRange(String fileId, String sheetId, String range, List<List<String>> matrix) throws IOException {
        String path = "/openapi/spreadsheet/v3/files/" + encode(fileId) + "/" + encode(sheetId) + "/" + range;
        JSONObject root = apiGet(path);
        JSONObject grid = extractGrid(root);
        if (grid == null) return;
        int startRow = grid.getIntValue("startRow");
        int startCol = grid.getIntValue("startColumn");
        JSONArray rows = grid.getJSONArray("rows");
        if (rows == null) return;
        for (int i = 0; i < rows.size(); i++) {
            int tr = startRow + i;
            if (tr < 0 || tr >= matrix.size()) continue;
            JSONObject rowObj = rows.getJSONObject(i);
            JSONArray values = rowObj.getJSONArray("values");
            if (values == null) continue;
            for (int j = 0; j < values.size(); j++) {
                int tc = startCol + j;
                if (tc < 0 || tc >= matrix.get(tr).size()) continue;
                matrix.get(tr).set(tc, cellText(values.getJSONObject(j)));
            }
        }
    }

    private JSONObject extractGrid(JSONObject root) {
        JSONObject data = root.getJSONObject("data");
        if (data != null && data.getJSONObject("gridData") != null) return data.getJSONObject("gridData");
        return root.getJSONObject("gridData");
    }

    private String cellText(JSONObject cellObj) {
        if (cellObj == null) return "";
        JSONObject cv = cellObj.getJSONObject("cellValue");
        if (cv == null) return "";
        if (cv.containsKey("text")) {
            String t = cv.getString("text");
            return t == null ? "" : t;
        }
        if (cv.containsKey("number")) return cv.getString("number");
        if (cv.containsKey("string")) return cv.getString("string");
        return "";
    }

    // ------------------------------------------------------------------
    // export 方式读取
    // ------------------------------------------------------------------

    private List<List<List<String>>> exportMatrices(String fileId) throws IOException {
        // 导出接口每用户每天限 9 次：用本地缓存避免重启/重试时重复消耗额度。
        java.io.File cache = new java.io.File(
                CargoWhitelist.getInstance().getDataFolder(), "tencent-export-cache.xlsx");
        long ttlMs = Math.max(600, CargoWhitelist.updatePeriod) * 1000L;
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < ttlMs) {
            try {
                return XlsxReader.read(java.nio.file.Files.readAllBytes(cache.toPath()));
            } catch (Exception ignored) {
                // 缓存损坏则继续重新导出
            }
        }
        try {
            String opId = startExport(fileId);
            String url = waitExport(fileId, opId);
            byte[] xlsx = download(url);
            try {
                java.nio.file.Files.write(cache.toPath(), xlsx);
            } catch (Exception ignored) {
            }
            return XlsxReader.read(xlsx);
        } catch (IOException e) {
            if (cache.exists()) {
                warn("导出失败，改用本地缓存: " + e.getMessage());
                return XlsxReader.read(java.nio.file.Files.readAllBytes(cache.toPath()));
            }
            throw e;
        }
    }

    private String startExport(String fileId) throws IOException {
        String url = BASE + "/openapi/drive/v2/files/" + encode(fileId) + "/async-export";
        FormBody body = new FormBody.Builder().build();
        Request req = authRequest(url).post(body)
                .header("Content-Type", "application/x-www-form-urlencoded").build();
        JSONObject root = executeJson(req);
        JSONObject data = root.getJSONObject("data");
        String opId = data == null ? null : data.getString("operationID");
        if (opId == null) throw new IOException("发起导出失败: " + root);
        return opId;
    }

    private String waitExport(String fileId, String opId) throws IOException {
        String url = BASE + "/openapi/drive/v2/files/" + encode(fileId)
                + "/export-progress?operationID=" + encode(opId);
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            JSONObject root = apiGetAbsolute(url);
            JSONObject data = root.getJSONObject("data");
            if (data != null) {
                int progress = data.getIntValue("progress");
                String link = data.getString("url");
                if (progress >= 100 && link != null && !link.isEmpty()) {
                    return link;
                }
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("导出被中断");
            }
        }
        throw new IOException("导出超时");
    }

    private byte[] download(String url) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("导出下载为空");
            return resp.body().bytes();
        }
    }

    // ------------------------------------------------------------------
    // 子表 / fileId 解析
    // ------------------------------------------------------------------

    private String resolveFileId() throws IOException {
        if (!cfg.fileId.trim().isEmpty()) return cfg.fileId.trim();
        if (cfg.encodedId.trim().isEmpty()) throw new IOException("未配置 tencent.file-id 或 tencent.encoded-id");
        String path = "/openapi/drive/v2/util/converter?type=2&value=" + encode(cfg.encodedId.trim());
        JSONObject root = apiGet(path);
        JSONObject data = root.getJSONObject("data");
        String fileId = data == null ? null : data.getString("fileID");
        if (fileId == null || fileId.isEmpty()) throw new IOException("无法解析 file-id: " + cfg.encodedId);
        return fileId;
    }

    private List<SheetRef> listSheets(String fileId) throws IOException {
        String path = "/openapi/spreadsheet/v3/files/" + encode(fileId) + "?concise=1";
        JSONObject root = apiGet(path);
        JSONArray props = root.getJSONArray("properties");
        if (props == null) {
            JSONObject data = root.getJSONObject("data");
            if (data != null) props = data.getJSONArray("properties");
        }
        List<SheetRef> refs = new ArrayList<>();
        if (props != null) {
            for (int i = 0; i < props.size(); i++) {
                JSONObject p = props.getJSONObject(i);
                String sid = p.getString("sheetId");
                if (sid == null) sid = p.getString("sheetID");
                refs.add(new SheetRef(sid, p.getString("title"),
                        p.getIntValue("rowCount"), p.getIntValue("columnCount")));
            }
        }
        return refs;
    }

    private SheetRef findSheet(List<SheetRef> sheets, Config.SheetSpec spec) {
        if (spec == null) {
            for (SheetRef ref : sheets) {
                if (cfg.sheetId.trim().isEmpty() || cfg.sheetId.trim().equals(ref.sheetId)) return ref;
            }
            return null;
        }
        for (SheetRef ref : sheets) {
            if (!spec.id.isEmpty() && spec.id.equals(ref.sheetId)) return ref;
            if (!spec.title.isEmpty() && spec.title.equals(ref.title)) return ref;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 智能表（smartsheet）读取
    // ------------------------------------------------------------------

    private List<SmartRef> listSmartSheets(String fileId) {
        List<SmartRef> refs = new ArrayList<>();
        try {
            JSONObject root = apiGet("/openapi/smartbook/v2/files/" + encode(fileId) + "/sheets");
            JSONObject data = root.getJSONObject("data");
            JSONArray arr = data == null ? null : data.getJSONArray("getSheet");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject s = arr.getJSONObject(i);
                    refs.add(new SmartRef(s.getString("sheetID"), s.getString("title")));
                }
            }
        } catch (RateLimitException e) {
            throw e; // 限频需向上传播以终止本轮
        } catch (Exception e) {
            warn("查询智能表失败: " + e.getMessage());
        }
        return refs;
    }

    /** 腾讯文档限频（不可重试，触发 24h 冷却）。 */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }

    private List<Raw> readSmartRecords(String fileId, String sheetId) throws IOException {
        List<Raw> out = new ArrayList<>();
        String path = "/openapi/smartbook/v2/files/" + encode(fileId) + "/sheets/" + encode(sheetId);
        int offset = 0;
        final int limit = 100;
        for (int guard = 0; guard < 10000; guard++) {
            JSONObject req = new JSONObject();
            JSONObject gr = new JSONObject();
            gr.put("offset", offset);
            gr.put("limit", limit);
            req.put("getRecords", gr);

            JSONObject root = apiPost(path, req.toJSONString());
            JSONObject data = root.getJSONObject("data");
            JSONObject get = data == null ? null : data.getJSONObject("getRecords");
            if (get == null) {
                if (offset == 0) warn("读取智能表记录失败: " + root.getString("msg"));
                break;
            }
            JSONArray recs = get.getJSONArray("records");
            if (recs != null) {
                for (int i = 0; i < recs.size(); i++) {
                    out.add(rawFromRecord(recs.getJSONObject(i)));
                }
            }
            boolean hasMore = get.getBooleanValue("hasMore");
            int next = get.getIntValue("next");
            if (!hasMore) break;
            offset = next > offset ? next : offset + limit;
        }
        return out;
    }

    private Raw rawFromRecord(JSONObject rec) {
        Raw raw = new Raw();
        JSONObject v = rec.getJSONObject("values");
        if (v == null) return raw;

        raw.id = smartText(fieldValue(v, "id", "请填写你的 Minecraft ID（必填）", "Minecraft ID", "ID")).trim();
        raw.premium = normalizeBool(smartText(fieldValue(v, "premium", "你的 Minecraft ID 是否为正版？（必填）", "是否正版")));
        raw.bedrock = normalizeBool(smartText(fieldValue(v, "bedrock", "当前 ID 是否为基岩版 ID？（必填）", "是否基岩版")));
        raw.date = smartDate(fieldValue(v, "date", "提交时间（自动）", "添加时间", "添加日期"));
        String n = smartText(fieldValue(v, "note", "请在此输入附加信息", "附加信息", "备注")).trim();
        raw.note = "无".equals(n) ? "" : n;
        raw.reason = smartText(fieldValue(v, "revoke-reason", "白名单回收理由", "回收理由")).trim();
        raw.revokeTime = smartDate(fieldValue(v, "revoke-time", "白名单回收时间", "回收时间"));

        Object issuedV = fieldValue(v, "issued", "是否发放白名单");
        Object validV = fieldValue(v, "valid", "白名单是否仍然有效");
        if (issuedV != null || validV != null) {
            if (isYes(smartText(issuedV))) {
                raw.status = isYes(smartText(validV)) ? Status.ACTIVE : Status.REVOKED;
            } else {
                raw.status = Status.SKIP;
            }
        } else {
            Object statusV = fieldValue(v, "status", "状态", "白名单状态", "是否白名单");
            if (statusV != null) {
                raw.status = resolveStatusValue(smartText(statusV)) ? Status.ACTIVE : Status.REVOKED;
            } else {
                // 配置了 issued/valid 语义、但该记录缺少这些字段 -> 视为未确认，跳过
                raw.status = (hasColumn("issued") || hasColumn("valid")) ? Status.SKIP : Status.ACTIVE;
            }
        }
        return raw;
    }

    private boolean hasColumn(String key) {
        String t = cfg.columns.get(key);
        return t != null && !t.isEmpty();
    }

    private Object fieldValue(JSONObject values, String key, String... aliases) {
        String t = cfg.columns.get(key);
        if (t != null && !t.isEmpty() && values.containsKey(t)) return values.get(t);
        for (String a : aliases) {
            if (values.containsKey(a)) return values.get(a);
        }
        return null;
    }

    private static String smartText(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                Object o = a.get(i);
                if (o instanceof JSONObject) {
                    String t = ((JSONObject) o).getString("text");
                    if (t != null) parts.add(t);
                } else if (o != null) {
                    parts.add(String.valueOf(o));
                }
            }
            return String.join("", parts);
        }
        if (v instanceof JSONObject) {
            String t = ((JSONObject) v).getString("text");
            return t == null ? "" : t;
        }
        return String.valueOf(v);
    }

    private static String smartDate(Object v) {
        String s = smartText(v).trim();
        if (s.isEmpty()) return "";
        if (s.matches("\\d{10,13}")) {
            long t = Long.parseLong(s);
            if (s.length() == 10) t *= 1000L;
            return Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate().toString();
        }
        return normalizeDate(s);
    }

    // ------------------------------------------------------------------
    // Token
    // ------------------------------------------------------------------

    private void ensureToken() throws IOException {
        if (cfg.accessToken.trim().isEmpty()) throw new IOException("未配置 tencent.access-token");
        long exp = jwtExp(cfg.accessToken);
        if (exp > 0 && exp < System.currentTimeMillis() / 1000 + 60 && canRefresh()) {
            refreshToken();
        }
    }

    private boolean canRefresh() {
        return !cfg.refreshToken.trim().isEmpty() && !cfg.clientSecret.trim().isEmpty();
    }

    private void refreshToken() throws IOException {
        String url = BASE + "/oauth/v2/token?client_id=" + encode(cfg.clientId)
                + "&client_secret=" + encode(cfg.clientSecret)
                + "&grant_type=refresh_token&refresh_token=" + encode(cfg.refreshToken);
        JSONObject root;
        try (Response resp = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            root = JSON.parseObject(body);
        }
        if (root == null || root.getString("access_token") == null) {
            throw new IOException("刷新 Access Token 失败: " + root);
        }
        cfg.accessToken = root.getString("access_token");
        String nr = root.getString("refresh_token");
        if (nr != null && !nr.isEmpty()) cfg.refreshToken = nr;
        CargoWhitelist.persistTencentTokens(cfg.accessToken, cfg.refreshToken);
        CargoWhitelist.getInstance().getLogger().info("[Tencent] Access Token 已刷新");
    }

    private long jwtExp(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return 0;
            String b64 = parts[1];
            while (b64.length() % 4 != 0) b64 += "=";
            String payload = new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
            return JSON.parseObject(payload).getLongValue("exp");
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private Request.Builder authRequest(String url) {
        return new Request.Builder().url(url)
                .addHeader("Access-Token", cfg.accessToken)
                .addHeader("Client-Id", cfg.clientId)
                .addHeader("Open-Id", cfg.openId)
                .addHeader("Accept", "application/json");
    }

    private JSONObject apiGet(String pathAndQuery) throws IOException {
        return apiGetAbsolute(BASE + pathAndQuery);
    }

    private JSONObject apiGetAbsolute(String url) throws IOException {
        JSONObject root = executeJson(authRequest(url).get().build());
        if (isTokenError(root) && canRefresh()) {
            refreshToken();
            root = executeJson(authRequest(url).get().build());
        }
        return root;
    }

    private JSONObject apiPost(String path, String json) throws IOException {
        Request req = authRequest(BASE + path).post(RequestBody.create(JSON_TYPE, json)).build();
        JSONObject root = executeJson(req);
        if (isTokenError(root) && canRefresh()) {
            refreshToken();
            req = authRequest(BASE + path).post(RequestBody.create(JSON_TYPE, json)).build();
            root = executeJson(req);
        }
        return root;
    }

    private JSONObject executeJson(Request req) throws IOException {
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (resp.code() == 429) {
                RateLimitGuard.trigger();
                throw new RateLimitException("HTTP 429");
            }
            if (body.isEmpty()) return new JSONObject();
            JSONObject obj;
            try {
                obj = JSON.parseObject(body);
            } catch (Exception e) {
                throw new IOException("腾讯文档返回非 JSON(" + resp.code() + "): " + body, e);
            }
            if (isRateLimit(obj)) {
                RateLimitGuard.trigger();
                throw new RateLimitException("Request Is Over Rate");
            }
            return obj;
        }
    }

    private boolean isRateLimit(JSONObject root) {
        if (root == null) return false;
        int ret = root.getIntValue("ret");
        int code = root.getIntValue("code");
        if (ret == 9998 || ret == 400007 || code == 9998 || code == 400007) return true;
        String msg = root.getString("msg");
        if (msg == null) msg = root.getString("message");
        return msg != null && msg.toLowerCase().contains("over rate");
    }

    private boolean isTokenError(JSONObject root) {
        if (root == null) return false;
        if (root.getIntValue("ret") == 37019 || root.getIntValue("code") == 37019) return true;
        String msg = root.getString("msg");
        if (msg == null) msg = root.getString("message");
        return msg != null && msg.toLowerCase().contains("token");
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void warn(String msg) {
        CargoWhitelist.getInstance().getLogger().warning("[Tencent] " + msg);
    }

    private static String colLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 值归一化
    // ------------------------------------------------------------------

    private static boolean isYes(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.isEmpty()) return false;
        if (s.charAt(0) == '是') return true;
        String lower = s.toLowerCase();
        return lower.equals("yes") || lower.equals("y") || lower.equals("true")
                || lower.equals("1") || s.equals("在册") || s.equals("白名单") || s.equals("通过");
    }

    private static String normalizeBool(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.isEmpty()) return "";
        if (isYes(s)) return "Yes";
        String lower = s.toLowerCase();
        if (s.charAt(0) == '否' || lower.equals("no") || lower.equals("n")
                || lower.equals("false") || lower.equals("0")) {
            return "No";
        }
        return s;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static String normalizeDate(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.isEmpty()) return "";
        Matcher iso = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (iso.find()) return iso.group(1) + "-" + pad2(iso.group(2)) + "-" + pad2(iso.group(3));
        Matcher slash = Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})").matcher(s);
        if (slash.find()) return slash.group(1) + "-" + pad2(slash.group(2)) + "-" + pad2(slash.group(3));
        Matcher cn = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日").matcher(s);
        if (cn.find()) return cn.group(1) + "-" + pad2(cn.group(2)) + "-" + pad2(cn.group(3));
        if (s.matches("\\d{5}(\\.\\d+)?")) {
            try {
                long serial = (long) Double.parseDouble(s);
                return LocalDate.of(1899, 12, 30).plusDays(serial).format(ISO);
            } catch (Exception ignored) {
                return s;
            }
        }
        return s;
    }

    private static String pad2(String s) {
        return s.length() == 1 ? "0" + s : s;
    }

    private static final class SheetRef {
        final String sheetId;
        final String title;
        final int rowCount;
        final int columnCount;

        SheetRef(String sheetId, String title, int rowCount, int columnCount) {
            this.sheetId = sheetId == null ? "" : sheetId;
            this.title = title == null ? "" : title;
            this.rowCount = rowCount;
            this.columnCount = columnCount;
        }
    }

    private static final class SmartRef {
        final String sheetId;
        final String title;

        SmartRef(String sheetId, String title) {
            this.sheetId = sheetId == null ? "" : sheetId;
            this.title = title == null ? "" : title;
        }
    }

    // ==================================================================
    // 极简 XLSX 解析（zip + xml，仅依赖 JDK）
    // ==================================================================

    static final class XlsxReader {
        private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

        static List<List<List<String>>> read(byte[] data) throws IOException {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry e;
                byte[] buf = new byte[8192];
                while ((e = zin.getNextEntry()) != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                    entries.put(e.getName(), bos.toByteArray());
                }
            }

            List<String> shared = new ArrayList<>();
            if (entries.containsKey("xl/sharedStrings.xml")) {
                Document d = parse(entries.get("xl/sharedStrings.xml"));
                NodeList sis = d.getElementsByTagNameNS(NS, "si");
                for (int i = 0; i < sis.getLength(); i++) {
                    shared.add(textOf((Element) sis.item(i)));
                }
            }

            // workbook sheet order + rels
            List<String> targets = new ArrayList<>();
            if (entries.containsKey("xl/workbook.xml") && entries.containsKey("xl/_rels/workbook.xml.rels")) {
                Document wb = parse(entries.get("xl/workbook.xml"));
                Document rels = parse(entries.get("xl/_rels/workbook.xml.rels"));
                Map<String, String> ridTarget = new LinkedHashMap<>();
                NodeList relList = rels.getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < relList.getLength(); i++) {
                    Element r = (Element) relList.item(i);
                    ridTarget.put(r.getAttribute("Id"), r.getAttribute("Target"));
                }
                NodeList sheetList = wb.getElementsByTagNameNS(NS, "sheet");
                for (int i = 0; i < sheetList.getLength(); i++) {
                    Element s = (Element) sheetList.item(i);
                    String rid = s.getAttributeNS(R_NS, "id");
                    String tgt = ridTarget.get(rid);
                    if (tgt != null) {
                        tgt = tgt.startsWith("/") ? tgt.substring(1) : "xl/" + tgt;
                        targets.add(tgt);
                    }
                }
            }
            if (targets.isEmpty()) {
                for (String name : entries.keySet()) {
                    if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) targets.add(name);
                }
            }

            List<List<List<String>>> sheets = new ArrayList<>();
            for (String tgt : targets) {
                byte[] xml = entries.get(tgt);
                if (xml == null) continue;
                sheets.add(readSheet(xml, shared));
            }
            return sheets;
        }

        private static List<List<String>> readSheet(byte[] xml, List<String> shared) throws IOException {
            Document doc = parse(xml);
            List<List<String>> rows = new ArrayList<>();
            NodeList rowList = doc.getElementsByTagNameNS(NS, "row");
            for (int i = 0; i < rowList.getLength(); i++) {
                Element rowEl = (Element) rowList.item(i);
                int rowNum = parseInt(rowEl.getAttribute("r"), rows.size() + 1);
                while (rows.size() < rowNum) rows.add(new ArrayList<>());
                List<String> line = rows.get(rowNum - 1);
                NodeList cells = rowEl.getElementsByTagNameNS(NS, "c");
                for (int j = 0; j < cells.getLength(); j++) {
                    Element c = (Element) cells.item(j);
                    int col = colIndex(c.getAttribute("r"));
                    while (line.size() < col) line.add("");
                    String t = c.getAttribute("t");
                    String val = "";
                    NodeList vList = c.getElementsByTagNameNS(NS, "v");
                    NodeList isList = c.getElementsByTagNameNS(NS, "is");
                    if ("inlineStr".equals(t) && isList.getLength() > 0) {
                        val = textOf((Element) isList.item(0));
                    } else if ("s".equals(t) && vList.getLength() > 0) {
                        int idx = parseInt(vList.item(0).getTextContent(), -1);
                        if (idx >= 0 && idx < shared.size()) val = shared.get(idx);
                    } else if (vList.getLength() > 0) {
                        val = vList.item(0).getTextContent();
                    }
                    line.set(col - 1, val);
                }
            }
            return rows;
        }

        private static String textOf(Element el) {
            StringBuilder sb = new StringBuilder();
            NodeList ts = el.getElementsByTagNameNS(NS, "t");
            for (int i = 0; i < ts.getLength(); i++) {
                Node n = ts.item(i);
                sb.append(n.getTextContent());
            }
            return sb.toString();
        }

        private static int colIndex(String ref) {
            int c = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
                else break;
            }
            return Math.max(c, 1);
        }

        private static Document parse(byte[] xml) throws IOException {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            } catch (Exception e) {
                throw new IOException("解析 xlsx XML 失败", e);
            }
        }

        private static int parseInt(String s, int dflt) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return dflt;
            }
        }
    }
}
