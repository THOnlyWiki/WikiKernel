package cc.thonly.wikikernel.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封禁期限（仿 MediaWiki Special:Block 的期限下拉）：
 * id 稳定（表单 / 接口使用），展示文案走消息键 {@code block.expiry.<id>}。
 */
public final class BlockExpiry {

    /** 全部期限 id（infinite = 永久）。 */
    public static final List<String> IDS = List.of(
            "infinite", "1h", "1d", "3d", "1w", "2w", "1m", "3m", "6m", "1y");

    private BlockExpiry() {
    }

    /** id 是否合法（null / 空视为合法 = 永久）。 */
    public static boolean isKnown(String id) {
        return id == null || id.isEmpty() || IDS.contains(id);
    }

    /** 把期限 id 解析成到期时间；{@code infinite}（或空 / 未知）→ null（永久）。 */
    public static LocalDateTime resolve(String id, LocalDateTime now) {
        return switch (id == null || id.isEmpty() ? "infinite" : id) {
            case "1h" -> now.plusHours(1);
            case "1d" -> now.plusDays(1);
            case "3d" -> now.plusDays(3);
            case "1w" -> now.plusWeeks(1);
            case "2w" -> now.plusWeeks(2);
            case "1m" -> now.plusMonths(1);
            case "3m" -> now.plusMonths(3);
            case "6m" -> now.plusMonths(6);
            case "1y" -> now.plusYears(1);
            default -> null;
        };
    }
}
