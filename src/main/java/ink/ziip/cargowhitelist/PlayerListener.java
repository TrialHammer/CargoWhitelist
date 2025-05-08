package ink.ziip.cargowhitelist;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public class PlayerListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        if (!CargoWhitelist.getInstance().isWhitelisted(name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, CargoWhitelist.kickedMessage);
        }
    }
}
