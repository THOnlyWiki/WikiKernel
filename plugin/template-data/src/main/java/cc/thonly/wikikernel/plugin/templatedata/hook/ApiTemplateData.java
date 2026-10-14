package cc.thonly.wikikernel.plugin.templatedata.hook;

import cc.thonly.wikikernel.common.extension.ActionApiException;
import cc.thonly.wikikernel.common.extension.ActionApiModule;
import cc.thonly.wikikernel.common.extension.ActionApiRequest;
import cc.thonly.wikikernel.common.extension.ApiPageRef;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataConfig;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import cc.thonly.wikikernel.plugin.templatedata.service.TemplateDataBlob;
import cc.thonly.wikikernel.plugin.templatedata.service.Values;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code action=templatedata}（移植 {@code ApiTemplateData}，REL1_43）。
 * <p>
 * 参数：{@code titles}（{@code |} 分隔）、{@code includeMissingTitles}、
 * {@code doNotIgnoreMissingTitles}（原版的废弃别名）、{@code lang}（接口文本语言）。
 * 输出：
 * <pre>
 * {"pages":{"1":{"title":"模板:X","description":…,"params":{…},"sets":[],"format":null,"maps":{}},
 *           "-1":{"title":"模板:不存在","missing":true},
 *           "2":{"title":"模板:无数据","notemplatedata":true,"params":{"1":{},"foo":{}}}}}
 * </pre>
 * 语义与原版一致：
 * <ul>
 *     <li>默认只返回「存在且有 templatedata」的页面；{@code includeMissingTitles} 打开后，
 *     缺失页面返回 {@code missing}，存在但没有数据的页面返回 {@code notemplatedata}，
 *     并从 wikitext 里按原版正则提取参数名（先去掉注释 / {@code nowiki} / {@code pre}，
 *     参数名小写化去重，取第一次出现的写法）；</li>
 *     <li>页面属性里的 JSON 解析失败 → {@code templatedata-corrupt} 错误（整份响应失败）；</li>
 *     <li>{@code lang} 缺省时返回完整的多语言对象。</li>
 * </ul>
 * 与原版的差异：不移植 {@code pageids} / {@code redirects} / generator 与 EventLogging；
 * 页面按请求标题的顺序输出（原版按 page_props 的 pp_page 升序）；
 * {@code formatversion=1} 下布尔值仍是 JSON 布尔（core 统一序列化，没有原版的 BC 转换）。
 */
public final class ApiTemplateData implements ActionApiModule {

    /** action 名。 */
    public static final String ACTION_NAME = "templatedata";

    /** 从 wikitext 提取参数名（与原版 / JS SourceHandler 完全相同的正则）。 */
    private static final Pattern RAW_PARAMS =
            Pattern.compile("\\{\\{\\{+([^\n#={|}]*?)([<|]|\\}\\}\\})");

    private static final Pattern COMMENTS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern NOWIKI = Pattern.compile("<nowiki\\s*>.*?</nowiki\\s*>", Pattern.DOTALL);
    private static final Pattern PRE = Pattern.compile("<pre\\s*>.*?</pre\\s*>", Pattern.DOTALL);
    private static final Pattern SPACES = Pattern.compile("[-_ ]+");

    private final TemplateDataConfig config;
    private final TemplateDataMessages fixedMessages;

    public ApiTemplateData(TemplateDataConfig config) {
        this(config, null);
    }

    /**
     * @param config        插件配置
     * @param fixedMessages 固定消息表（测试用）；为 null 时按系统语言解析
     */
    public ApiTemplateData(TemplateDataConfig config, TemplateDataMessages fixedMessages) {
        this.config = config;
        this.fixedMessages = fixedMessages;
    }

    @Override
    public String action() {
        return ACTION_NAME;
    }

    @Override
    public Object handle(ActionApiRequest request) {
        boolean includeMissing = booleanParam(request, "doNotIgnoreMissingTitles")
                || booleanParam(request, "includeMissingTitles");
        String language = Values.trimPhp(request.param("lang"));

        List<ApiPageRef> refs = dedupe(request.resolvePages(request.param("titles")));
        List<ApiPageRef> existing = refs.stream().filter(ref -> !ref.missing()).toList();
        List<ApiPageRef> missing = refs.stream().filter(ApiPageRef::missing).toList();

        Map<String, Object> pages = Values.newMap();
        if (existing.isEmpty() && (!includeMissing || missing.isEmpty())) {
            return single("pages", pages);
        }

        if (includeMissing) {
            long missingId = 0;
            for (ApiPageRef ref : refs) {
                Map<String, Object> entry = Values.newMap();
                entry.put("title", ref.title());
                if (ref.missing()) {
                    entry.put("missing", Boolean.TRUE);
                    pages.put(String.valueOf(--missingId), entry);
                } else {
                    entry.put("notemplatedata", Boolean.TRUE);
                    pages.put(String.valueOf(ref.id()), entry);
                }
            }
        }

        Map<Long, Map<String, String>> properties = existing.isEmpty() ? Map.of()
                : request.properties(existing.stream().map(ApiPageRef::id).toList(),
                        List.of(TemplateDataBlob.PROPERTY_NAME));

        for (ApiPageRef ref : existing) {
            String raw = properties.getOrDefault(ref.id(), Map.of()).get(TemplateDataBlob.PROPERTY_NAME);
            if (raw == null) {
                // 页面存在但没有 templatedata
                continue;
            }
            TemplateDataBlob blob = TemplateDataBlob.fromDatabase(raw, config.contentLanguage());
            if (!blob.status().isOk()) {
                throw new ActionApiException("templatedata-corrupt", corruptInfo(ref, blob));
            }
            Map<String, Object> data = language.isEmpty() ? blob.getData() : blob.getDataInLanguage(language);
            Map<String, Object> entry = Values.asMap(pages.get(String.valueOf(ref.id())));
            if (entry == null) {
                entry = Values.newMap();
                entry.put("title", ref.title());
            }
            entry.remove("notemplatedata");
            entry.putAll(data);
            pages.put(String.valueOf(ref.id()), entry);
        }

        if (includeMissing) {
            // 没有 templatedata 的页面：从正文里提取参数名
            for (ApiPageRef ref : existing) {
                Map<String, Object> entry = Values.asMap(pages.get(String.valueOf(ref.id())));
                if (entry == null || !Boolean.TRUE.equals(entry.get("notemplatedata"))) {
                    continue;
                }
                entry.put("params", rawParams(request.content(ref.id()).orElse("")));
            }
        }

        return single("pages", pages);
    }

    /** 错误信息：{@code Page #1 templatedata contains invalid data: …}（对应 {@code apierror-templatedata-corrupt}）。 */
    private String corruptInfo(ApiPageRef ref, TemplateDataBlob blob) {
        return messages().text("apierror-templatedata-corrupt",
                List.of(String.valueOf(ref.id()), blob.status().text(messages())));
    }

    private TemplateDataMessages messages() {
        return fixedMessages != null ? fixedMessages : TemplateDataMessages.forCurrentLocale();
    }

    /** 去掉重复标题（原版由 {@code ApiPageSet} 去重）。 */
    private static List<ApiPageRef> dedupe(List<ApiPageRef> refs) {
        List<ApiPageRef> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ApiPageRef ref : refs) {
            String key = ref.missing()
                    ? "missing:" + ref.title().toLowerCase(Locale.ROOT)
                    : "page:" + ref.id();
            if (seen.add(key)) {
                result.add(ref);
            }
        }
        return result;
    }

    /**
     * 布尔参数（仿 MediaWiki）：没写 → false；写了且值是 {@code 0}/{@code false}（不区分大小写）→ false；
     * 其余（含只写参数名）→ true。
     */
    private static boolean booleanParam(ActionApiRequest request, String name) {
        if (!request.params().containsKey(name)) {
            return false;
        }
        String value = Values.trimPhp(request.param(name)).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "0", "false", "no", "off" -> false;
            default -> true;
        };
    }

    /**
     * 从原始 wikitext 提取参数名（用于没有 templatedata 的模板），返回结构与 {@code params} 一致。
     * 对应原版 {@code ApiTemplateData::getRawParams()}。
     */
    static Map<String, Object> rawParams(String wikitext) {
        String text = wikitext == null ? "" : wikitext;
        // 去掉注释与转义标签里的内容
        text = COMMENTS.matcher(text).replaceAll("");
        text = NOWIKI.matcher(text).replaceAll("");
        text = PRE.matcher(text).replaceAll("");

        Map<String, Object> params = Values.newMap();
        Set<String> normalizedParams = new LinkedHashSet<>();
        Matcher matcher = RAW_PARAMS.matcher(text);
        while (matcher.find()) {
            String rawParam = matcher.group(1);
            // 与原版（以及 JS 的 SourceHandler）一致的归一化：-/_/空格 合并成一个空格、去首尾空白、ASCII 小写
            String normalized = Values.asciiLower(
                    Values.trimPhp(SPACES.matcher(rawParam).replaceAll(" ")));
            if (normalized.isEmpty() || !normalizedParams.add(normalized)) {
                // 这个参数（或同名变体）已经出现过了
                continue;
            }
            params.put(Values.trimPhp(rawParam), Values.newMap());
        }
        return params;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> result = Values.newMap();
        result.put(key, value);
        return result;
    }
}
