package ink.ziip.cargowhitelist;

import org.bukkit.scheduler.BukkitRunnable;

public abstract class BaseTask extends BukkitRunnable {

    protected boolean started;
    protected int period;

    public BaseTask(int period) {
        this.started = false;
        this.period = period;
    }

    public void start() {
        this.runTaskTimerAsynchronously(CargoWhitelist.getInstance(), 0, period);
        started = true;
    }

    public abstract void stop();
}