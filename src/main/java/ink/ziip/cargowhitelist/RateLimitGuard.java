package ink.ziip.cargowhitelist;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 腾讯文档限频冷却守卫。
 * <p>
 * 触发 "Request Is Over Rate" 后进入冷却，冷却到**下一个自然日 23:59**（按服务器本地时区）。
 * 冷却开始时间记录到 {@code plugins/CargoWhitelist/tencent-ratelimit.txt}（重启后仍有效）。
 * 冷却期内重复触发**不会重置**开始时间。
 */
public final class RateLimitGuard {

    /** 每日额度重置时刻：23:59（腾讯文档按北京时间）。 */
    private static final LocalTime RESET_TIME = LocalTime.of(23, 59, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static volatile long memStart = 0L;

    private RateLimitGuard() {
    }

    private static File file() {
        try {
            CargoWhitelist p = CargoWhitelist.getInstance();
            if (p != null && p.getDataFolder() != null) {
                return new File(p.getDataFolder(), "tencent-ratelimit.txt");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static synchronized long cooldownStart() {
        File f = file();
        if (f != null && f.isFile()) {
            try {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                return Long.parseLong(s);
            } catch (Exception ignored) {
            }
        }
        return memStart;
    }

    /** 冷却到期时刻（从 start 起的下一个 23:59）。 */
    public static long resetTime(long start) {
        ZoneId zone = ZONE;
        LocalDateTime dt = Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime();
        LocalDateTime reset = LocalDateTime.of(dt.toLocalDate(), RESET_TIME);
        if (!reset.isAfter(dt)) {
            reset = LocalDateTime.of(dt.toLocalDate().plusDays(1), RESET_TIME);
        }
        return reset.atZone(zone).toInstant().toEpochMilli();
    }

    public static synchronized boolean inCooldown() {
        long s = cooldownStart();
        return s > 0 && System.currentTimeMillis() < resetTime(s);
    }

    public static synchronized long remainingMs() {
        long s = cooldownStart();
        if (s <= 0) return 0;
        return Math.max(0, resetTime(s) - System.currentTimeMillis());
    }

    /** 触发冷却：仅当当前不在冷却期（或已过重置时刻）时记录开始时间；冷却期内重复调用不重置。 */
    public static synchronized void trigger() {
        long s = cooldownStart();
        long now = System.currentTimeMillis();
        if (s > 0 && now < resetTime(s)) {
            return; // 仍在冷却，保持原开始时间
        }
        memStart = now;
        File f = file();
        if (f != null) {
            try {
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                Files.write(f.toPath(), Long.toString(now).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        try {
            long reset = resetTime(now);
            CargoWhitelist.getInstance().getLogger().warning(
                    "[Tencent] 触发限频(Request Is Over Rate)，进入冷却至 "
                            + Instant.ofEpochMilli(reset).atZone(ZONE).toLocalDateTime()
                            + "（重复触发不重置）。");
        } catch (Throwable ignored) {
        }
    }
}
