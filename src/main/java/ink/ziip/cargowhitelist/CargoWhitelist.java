package ink.ziip.cargowhitelist;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CargoWhitelist extends JavaPlugin {
    private static final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    // 额外手动/强制白名单：始终生效，不受同步影响（永不被同步移除）
    private static final Set<String> manualWhitelist = ConcurrentHashMap.newKeySet();

    private static WhitelistUpdateTask whitelistUpdateTask;
    private static CargoWhitelist instance;

    // config.yml：只读配置（运行时绝不写回，避免覆盖）
    private static YamlConfiguration config;
    private static File file;
    // data.yml：运行时可变数据（同步白名单缓存、手动白名单、刷新后的 Token）
    private static YamlConfiguration data;
    private static File dataFile;

    // 原 wiki 抓取（从机 / 兜底）
    public static String verifiedPlayers;
    public static String revokedPlayers;
    public static String kickedMessage;
    public static int updatePeriod;
    public static boolean enabled;

    // 主从模式与腾讯文档 / wiki 配置
    public static String mode;              // master | slave
    public static Config.Tencent tencent = new Config.Tencent();
    public static Config.Wiki wiki = new Config.Wiki();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        instance = this;
        file = new File(getDataFolder(), "config.yml");
        dataFile = new File(getDataFolder(), "data.yml");
        config = YamlConfiguration.loadConfiguration(file);
        data = loadData(dataFile);

        applyConfig();

        whitelistUpdateTask = WhitelistUpdateTask.getInstance();
        whitelistUpdateTask.start();

        if (CargoWhitelist.enabled)
            Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);

        PluginCommand cw = getCommand("cw");
        if (cw != null) {
            WhitelistCommand exec = new WhitelistCommand();
            cw.setExecutor(exec);
            cw.setTabCompleter(exec);
        } else {
            getLogger().warning("未在 plugin.yml 中找到 cw 命令");
        }

        getLogger().info("CargoWhitelist 已启用，模式: " + mode
                + (CargoWhitelist.enabled ? "" : "（白名单拦截已关闭）"));
    }

    /** 从 config.yml + data.yml 载入所有运行时状态。 */
    private void applyConfig() {
        enabled = config.getBoolean("enabled", true);
        verifiedPlayers = config.getString("verified-players", "");
        revokedPlayers = config.getString("revoked-players", "");
        kickedMessage = config.getString("kicked-message", "");
        updatePeriod = config.getInt("update-period", 3600);

        mode = config.getString("mode", "slave").trim().toLowerCase();
        if (!"master".equals(mode) && !"slave".equals(mode)) {
            getLogger().warning("未知的 mode=" + mode + "，回退为 slave");
            mode = "slave";
        }
        loadTencentConfig();
        loadWikiConfig();

        // Token 运行时覆盖（自动刷新后写入 data.yml，避免改写 config.yml）
        String at = data.getString("access-token", "");
        if (at != null && !at.isEmpty()) tencent.accessToken = at;
        String rt = data.getString("refresh-token", "");
        if (rt != null && !rt.isEmpty()) tencent.refreshToken = rt;

        // 白名单：优先 data.yml；若首次（data 无该键）则从 config.yml 迁移
        List<String> wl = data.contains("whitelist")
                ? data.getStringList("whitelist") : config.getStringList("whitelist");
        List<String> ml = data.contains("manual-whitelist")
                ? data.getStringList("manual-whitelist") : config.getStringList("manual-whitelist");

        whitelist.clear();
        whitelist.addAll(wl);
        manualWhitelist.clear();
        manualWhitelist.addAll(ml);

        // 确保 data.yml 落地（迁移或创建）
        data.set("whitelist", new ArrayList<>(whitelist));
        data.set("manual-whitelist", new ArrayList<>(manualWhitelist));
        saveData();
    }

    private void loadTencentConfig() {
        Config.Tencent t = new Config.Tencent();
        ConfigurationSection ts = config.getConfigurationSection("tencent");
        if (ts != null) {
            t.enabled = ts.getBoolean("enabled", false);
            t.clientId = ts.getString("client-id", "");
            t.clientSecret = ts.getString("client-secret", "");
            t.accessToken = ts.getString("access-token", "");
            t.refreshToken = ts.getString("refresh-token", "");
            t.openId = ts.getString("open-id", "");
            t.fileId = ts.getString("file-id", "");
            t.encodedId = ts.getString("encoded-id", "");
            t.sheetId = ts.getString("sheet-id", "");
            t.source = ts.getString("source", "auto");
            t.operatorDefault = ts.getString("operator-default", "腾讯文档同步");
            t.headerRow = ts.getInt("header-row", 1);

            ConfigurationSection cols = ts.getConfigurationSection("columns");
            if (cols != null) {
                for (String key : cols.getKeys(false)) {
                    t.columns.put(key, cols.getString(key, ""));
                }
            }
            t.activeValues = ts.getStringList("status.active-values");
            t.revokedValues = ts.getStringList("status.revoked-values");

            List<Map<?, ?>> sheetMaps = ts.getMapList("sheets");
            for (Map<?, ?> m : sheetMaps) {
                Config.SheetSpec spec = new Config.SheetSpec();
                spec.title = asString(m.get("title"));
                spec.id = asString(m.get("id"));
                String st = asString(m.get("status"));
                spec.status = st.isEmpty() ? "auto" : st;
                t.sheets.add(spec);
            }
        }
        tencent = t;
    }

    private void loadWikiConfig() {
        Config.Wiki w = new Config.Wiki();
        ConfigurationSection ws = config.getConfigurationSection("wiki");
        if (ws != null) {
            w.enabled = ws.getBoolean("enabled", false);
            w.apiUrl = ws.getString("api-url", w.apiUrl);
            w.username = ws.getString("username", "");
            w.password = ws.getString("password", "");
            w.page = ws.getString("page", "");
            w.summary = ws.getString("summary", w.summary);
            w.autoRevoke = ws.getBoolean("auto-revoke", true);
        }
        wiki = w;
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 重载 config.yml 与 data.yml，并重启同步任务以应用新的周期。 */
    public static void reloadAll() {
        config = YamlConfiguration.loadConfiguration(file);
        data = loadData(dataFile);
        instance.applyConfig();

        if (whitelistUpdateTask != null) {
            WhitelistUpdateTask.reset();
            whitelistUpdateTask = WhitelistUpdateTask.getInstance();
            whitelistUpdateTask.start();
        }
        instance.getLogger().info("CargoWhitelist 配置已重载");
    }

    @Override
    public void onDisable() {
        if (whitelistUpdateTask != null) {
            whitelistUpdateTask.stop();
        }
    }

    public void addWhitelist(String name) {
        whitelist.add(name);
    }

    public void removeWhitelist(String name) {
        whitelist.remove(name);
    }

    public boolean isWhitelisted(String name) {
        return whitelist.contains(name) || manualWhitelist.contains(name);
    }

    public static Set<String> getManualWhitelist() {
        return manualWhitelist;
    }

    /** 通过命令添加到“强制白名单”并立即生效（持久化到 data.yml）。 */
    public static void addManual(String name) {
        manualWhitelist.add(name);
        whitelist.add(name);
        data.set("manual-whitelist", new ArrayList<>(manualWhitelist));
        saveData();
    }

    public static void removeManual(String name) {
        manualWhitelist.remove(name);
        data.set("manual-whitelist", new ArrayList<>(manualWhitelist));
        saveData();
    }

    /** 当前内存白名单快照（含同步结果）。 */
    public static List<String> snapshotWhitelist() {
        return new ArrayList<>(whitelist);
    }

    public static CargoWhitelist getInstance() {
        return instance;
    }

    /** 同步结果缓存写入 data.yml（绝不改写 config.yml）。 */
    public static void saveWhitelist(List<String> list) {
        if (data == null) return;
        data.set("whitelist", list);
        saveData();
    }

    /** 刷新后的腾讯 Token 写入 data.yml。 */
    public static void persistTencentTokens(String accessToken, String refreshToken) {
        if (data == null) return;
        data.set("access-token", accessToken);
        if (refreshToken != null && !refreshToken.isEmpty()) {
            data.set("refresh-token", refreshToken);
        }
        saveData();
    }

    private static YamlConfiguration loadData(File f) {
        if (!f.exists()) {
            try {
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                f.createNewFile();
            } catch (IOException ignored) {
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private static void saveData() {
        if (data == null || dataFile == null) return;
        try {
            data.save(dataFile);
        } catch (IOException ignored) {
        }
    }
}
