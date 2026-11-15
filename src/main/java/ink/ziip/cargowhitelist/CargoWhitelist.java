package ink.ziip.cargowhitelist;

import org.bukkit.Bukkit;
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
    // 额外手动白名单：始终生效，不受同步影响（永不被同步移除）
    private static final Set<String> manualWhitelist = ConcurrentHashMap.newKeySet();

    private static WhitelistUpdateTask whitelistUpdateTask;
    private static CargoWhitelist instance;
    private static YamlConfiguration config;
    private static File file;

    // 原 wiki 抓取（从机 / 兜底）
    public static String verifiedPlayers;
    public static String revokedPlayers;
    public static String kickedMessage;
    public static int updatePeriod;
    public static boolean enabled;

    // 新增：主从模式与腾讯文档 / wiki 配置
    public static String mode;              // master | slave
    public static Config.Tencent tencent = new Config.Tencent();
    public static Config.Wiki wiki = new Config.Wiki();

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();

        instance = this;
        file = new File(getDataFolder().getPath() + "/config.yml");
        config = YamlConfiguration.loadConfiguration(file);

        List<String> configWhitelist = config.getStringList("whitelist");

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

        whitelist.addAll(configWhitelist);
        manualWhitelist.addAll(config.getStringList("manual-whitelist"));

        whitelistUpdateTask = WhitelistUpdateTask.getInstance();
        whitelistUpdateTask.start();

        if (CargoWhitelist.enabled)
            Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);

        getLogger().info("CargoWhitelist 已启用，模式: " + mode
                + (CargoWhitelist.enabled ? "" : "（白名单拦截已关闭）"));
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

    @Override
    public void onDisable() {
        // Plugin shutdown logic
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

    /** 当前内存白名单快照。 */
    public static List<String> snapshotWhitelist() {
        return new ArrayList<>(whitelist);
    }

    public static CargoWhitelist getInstance() {
        return instance;
    }

    public static void saveWhitelist(List<String> whitelist) {
        config.set("whitelist", whitelist);
        try {
            config.save(CargoWhitelist.file);
        } catch (IOException ignored) {
        }
    }

    /** 刷新后的腾讯 Token 写回 config.yml。 */
    public static void persistTencentTokens(String accessToken, String refreshToken) {
        if (config == null || file == null) return;
        config.set("tencent.access-token", accessToken);
        if (refreshToken != null && !refreshToken.isEmpty()) {
            config.set("tencent.refresh-token", refreshToken);
        }
        try {
            config.save(file);
        } catch (IOException ignored) {
        }
    }
}
