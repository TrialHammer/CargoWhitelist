package ink.ziip.cargowhitelist;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;

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

    @Override
    public void run() {
        if (started) {
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
    }

    @Override
    public void stop() {
        cancel();
    }
}
