package ink.ziip.cargowhitelist;

/**
 * 一条白名单记录（来自腾讯文档的一行）。
 */
public final class WhitelistEntry {

    private final String id;
    private final String premium;   // Yes / No
    private final String bedrock;   // Yes / No
    private final String date;      // yyyy-MM-dd
    private final String operator;
    private final String note;
    private final boolean active;   // true = 在册（白名单），false = 撤销

    public WhitelistEntry(String id, String premium, String bedrock, String date,
                          String operator, String note, boolean active) {
        this.id = id == null ? "" : id.trim();
        this.premium = premium == null ? "" : premium.trim();
        this.bedrock = bedrock == null ? "" : bedrock.trim();
        this.date = date == null ? "" : date.trim();
        this.operator = operator == null ? "" : operator.trim();
        this.note = note == null ? "" : note.trim();
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public String getPremium() {
        return premium;
    }

    public String getBedrock() {
        return bedrock;
    }

    public String getDate() {
        return date;
    }

    public String getOperator() {
        return operator;
    }

    public String getNote() {
        return note;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isValid() {
        return !id.isEmpty();
    }
}
