package ink.ziip.cargowhitelist;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WhitelistUpdateTask extends BaseTask {
    private static WhitelistUpdateTask instance;

    private WhitelistUpdateTask() {
        super(CargoWhitelist.updatePeriod);
    }

    public static WhitelistUpdateTask getInstance() {
        if (instance == null) {
            instance = new WhitelistUpdateTask();
        }
        return instance;
    }

    /** 重载时丢弃单例，以便用新的 update-period 重建任务。 */
    public static void reset() {
        if (instance != null) {
            try {
                instance.cancel();
            } catch (Exception ignored) {
            }
            instance = null;
        }
    }

    @Override
    public void run() {
        if (!started) {
            return;
        }
        try {
            if ("master".equalsIgnoreCase(CargoWhitelist.mode)) {
                runMaster();
            } else {
                runSlave();
            }
        } catch (Exception e) {
            CargoWhitelist.getInstance().getLogger()
                    .warning("[Whitelist] 更新任务异常: " + e.getMessage());
        }
    }

    // ==================================================================
    // 主机：腾讯文档 -> 本地 + wiki
    // ==================================================================

    private void runMaster() {
        if (!CargoWhitelist.tencent.enabled) {
            CargoWhitelist.getInstance().getLogger()
                    .warning("[Master] mode=master 但 tencent.enabled=false，跳过");
            return;
        }

        List<WhitelistEntry> entries;
        try {
            entries = new TencentDocsClient(CargoWhitelist.tencent).fetchWhitelist();
        } catch (Exception e) {
            CargoWhitelist.getInstance().getLogger()
                    .warning("[Master] 读取腾讯文档失败: " + e.getMessage());
            return;
        }

        // 关键安全保护：若本轮未拿到任何数据（权限/网络异常等），不做任何删除
        if (entries.isEmpty()) {
            CargoWhitelist.getInstance().getLogger()
                    .warning("[Master] 腾讯文档未返回任何数据，跳过本轮以避免误删");
            return;
        }

        Set<String> activeIds = new HashSet<>();
        Set<String> revokedIds = new HashSet<>();
        for (WhitelistEntry e : entries) {
            if (!e.isValid()) continue;
            if (e.isActive()) {
                activeIds.add(e.getId());
            } else {
                revokedIds.add(e.getId());
            }
        }

        CargoWhitelist plugin = CargoWhitelist.getInstance();
        for (String id : activeIds) {
            plugin.addWhitelist(id);
        }
        for (String id : revokedIds) {
            plugin.removeWhitelist(id);
        }
        CargoWhitelist.saveWhitelist(CargoWhitelist.snapshotWhitelist());

        plugin.getLogger().info("[Master] 本地白名单已同步：在册 " + activeIds.size()
                + "，撤销 " + revokedIds.size());

        if (CargoWhitelist.wiki.enabled && !CargoWhitelist.wiki.page.isEmpty()) {
            try {
                new MediaWikiClient(CargoWhitelist.wiki).sync(entries);
            } catch (Exception e) {
                plugin.getLogger().warning("[Master] Wiki 同步失败: " + e.getMessage());
            }
        }
    }

    // ==================================================================
    // 从机：保留原有 wiki 抓取逻辑
    // ==================================================================

    private void runSlave() {
        try {
            List<String> whitelist = new ArrayList<>();

            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder().url(CargoWhitelist.verifiedPlayers).build();
            String responseData;
            Response response = client.newCall(request).execute();
            if (response.body() != null) {
                responseData = response.body().string();
                response.close();
                JSONArray jsonArray = JSONArray.parseArray(responseData);
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    String name = jsonObject.getString("ID");
                    whitelist.add(name);
                    CargoWhitelist.getInstance().addWhitelist(name);
                }
            }

            request = new Request.Builder().url(CargoWhitelist.revokedPlayers).build();
            response = client.newCall(request).execute();
            if (response.body() != null) {
                responseData = response.body().string();
                response.close();
                JSONArray jsonArray = JSONArray.parseArray(responseData);
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    String name = jsonObject.getString("ID");
                    CargoWhitelist.getInstance().removeWhitelist(name);
                }
            }

            CargoWhitelist.saveWhitelist(whitelist);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void stop() {
        cancel();
    }
}
