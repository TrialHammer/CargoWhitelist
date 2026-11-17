package ink.ziip.cargowhitelist;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /cw 管理命令：
 * <pre>
 *   /cw add <玩家>    添加到强制白名单（本服立即生效），并写 wiki 条目（从机据此放行）
 *   /cw remove <玩家> 从强制白名单移除（不影响同步白名单）
 *   /cw list          查看强制白名单
 *   /cw reload        重载 config.yml / data.yml
 * </pre>
 */
public class WhitelistCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("add", "remove", "list", "reload");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender.isOp() || sender.hasPermission("cargowhitelist.admin"))) {
            sender.sendMessage("§c你没有权限。");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "add":
                return add(sender, label, args);
            case "remove":
                return remove(sender, args);
            case "list":
                sender.sendMessage("§e强制白名单(" + CargoWhitelist.getManualWhitelist().size() + "): "
                        + String.join(", ", CargoWhitelist.getManualWhitelist()));
                return true;
            case "reload":
                CargoWhitelist.reloadAll();
                sender.sendMessage("§a配置已重载。");
                return true;
            default:
                help(sender, label);
                return true;
        }
    }

    private boolean add(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /" + label + " add <玩家>");
            return true;
        }
        final String name = args[1].trim();
        if (!isValidName(name)) {
            sender.sendMessage("§c非法玩家名: " + name);
            return true;
        }

        // 本服立刻生效（强制白名单）
        CargoWhitelist.addManual(name);
        sender.sendMessage("§a已加入强制白名单: §f" + name + " §a（本服立即生效）");

        // 写 wiki 条目（从机读 wiki，因此在 wiki 上添加后所有从机都会放行）
        final Config.Wiki w = CargoWhitelist.wiki;
        if (!w.enabled || w.page == null || w.page.isEmpty()) {
            sender.sendMessage("§7wiki 未启用，未写 wiki 条目。");
            return true;
        }
        final CargoWhitelist plugin = CargoWhitelist.getInstance();
        final WhitelistEntry entry = new WhitelistEntry(name, "", "",
                LocalDate.now().toString(), "cw", "手动添加（/cw 命令）", true);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String msg;
            try {
                boolean changed = new MediaWikiClient(w).addEntry(entry);
                msg = changed ? "§a已写入 wiki 条目: §f" + name : "§7wiki 中已存在该条目: §f" + name;
            } catch (Exception ex) {
                plugin.getLogger().warning("[cw add] 写 wiki 失败: " + ex.getMessage());
                msg = "§cwiki 写入失败（本地已生效）: " + ex.getMessage();
            }
            final String fm = msg;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(fm));
        });
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /cw remove <玩家>");
            return true;
        }
        String name = args[1].trim();
        CargoWhitelist.removeManual(name);
        sender.sendMessage("§a已从强制白名单移除: §f" + name + "§a（不影响同步白名单）");
        return true;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§e/cw add <玩家> §7- 加入强制白名单并写 wiki");
        sender.sendMessage("§e/cw remove <玩家> §7- 从强制白名单移除");
        sender.sendMessage("§e/cw list §7- 查看强制白名单");
        sender.sendMessage("§e/cw reload §7- 重载配置");
    }

    private static boolean isValidName(String s) {
        return s.matches("^#?[A-Za-z0-9_]{1,32}$");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
