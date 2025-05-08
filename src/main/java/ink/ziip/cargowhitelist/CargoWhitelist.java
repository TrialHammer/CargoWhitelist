package ink.ziip.cargowhitelist;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;

public final class CargoWhitelist extends JavaPlugin {
    private static final HashSet<String> whitelist = new HashSet<>();

    private static WhitelistUpdateTask whitelistUpdateTask;
    private static CargoWhitelist instance;
    private static YamlConfiguration config;
    private static File file;

    public static String verifiedPlayers;
    public static String revokedPlayers;
    public static String kickedMessage;
    public static int updatePeriod;
    public static boolean enabled;

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

        whitelist.addAll(configWhitelist);

        whitelistUpdateTask = WhitelistUpdateTask.getInstance();
        whitelistUpdateTask.start();

        if (CargoWhitelist.enabled)
            Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        whitelistUpdateTask.stop();
    }

    public void addWhitelist(String name) {
        whitelist.add(name);
    }

    public void removeWhitelist(String name) {
        whitelist.remove(name);
    }

    public boolean isWhitelisted(String name) {
        return whitelist.contains(name);
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
}
