package ink.ziip.cargowhitelist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置对象。由 {@link CargoWhitelist} 从 config.yml 读取后填充。
 */
public final class Config {

    private Config() {
    }

    /** 腾讯文档相关配置（仅主机 master 模式使用）。 */
    public static final class Tencent {
        public boolean enabled;
        public String clientId = "";
        public String clientSecret = "";
        public String accessToken = "";
        public String refreshToken = "";
        public String openId = "";

        /** 可通过 encodedId（即文档 URL /sheet/ 后的那一段）自动解析，二者填一即可。 */
        public String fileId = "";
        public String encodedId = "";

        /** 指定工作表；留空则在未配置 sheets 时自动读取所有子表。 */
        public String sheetId = "";

        /** 读取方式：auto（range 优先，失败回退 export）/ range / export。 */
        public String source = "auto";

        /** 新增到 wiki 时填写的“添加人”。 */
        public String operatorDefault = "腾讯文档同步";

        /** 表头所在行（从 1 开始）。 */
        public int headerRow = 1;

        /**
         * 逻辑字段 -> 表头文字 的映射。
         * 键固定为：id / premium / bedrock / date / operator / note / status
         */
        public Map<String, String> columns = new LinkedHashMap<>();

        /** 状态列的“白名单/在册”取值。 */
        public List<String> activeValues = new ArrayList<>();
        /** 状态列的“撤销/移除”取值。 */
        public List<String> revokedValues = new ArrayList<>();

        /** 多子表模式：若配置则按子表逐个读取，并用 status 指定该表整体含义。 */
        public List<SheetSpec> sheets = new ArrayList<>();
    }

    /** 多子表描述：一个工作表整体代表“在册”或“撤销”，或按状态列自动判断。 */
    public static final class SheetSpec {
        public String title = "";
        public String id = "";
        /** active / revoked / auto */
        public String status = "auto";
    }

    /** Wiki bot 同步配置（仅主机 master 模式使用）。 */
    public static final class Wiki {
        public boolean enabled;
        public String apiUrl = "https://wiki.hammer.moe/w/api.php";
        public String username = "";
        public String password = "";
        public String page = "";
        public String summary = "Bot: 同步白名单（来源：腾讯文档）";

        /**
         * 是否对“腾讯文档中标记为撤销、且 wiki 仍为在册”的玩家执行撤销。
         * 注意：不会撤销“仅未出现在表格中”的玩家，避免表格不完整时误删。
         */
        public boolean autoRevoke = true;
    }
}
