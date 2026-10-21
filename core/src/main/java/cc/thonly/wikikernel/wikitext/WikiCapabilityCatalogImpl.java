package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.extension.MagicWord;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.service.WikiCapabilityCatalog;
import cc.thonly.wikikernel.i18n.PluginI18n;
import cc.thonly.wikikernel.i18n.PluginMessageSource;
import cc.thonly.wikikernel.plugin.LoadedPlugin;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.SpecialWikiPageService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 由 core 汇总的「本站扩展语法目录」，供 AI Agent 的系统提示使用。
 * <p>
 * 数据不是写死在提示词里：每次新会话都从 {@link PluginManager} 读取当前实际加载的插件，
 * 从 {@link SpecialWikiPageService} 读取当前特殊页，从 core 消息读取标签用法，因此新增 / 删除
 * 插件、安装问答等都会自动反映。输出按「标签 → 解析器函数 → 魔术字 → 特殊页 → 扩展」排列。
 */
@Service
public class WikiCapabilityCatalogImpl implements WikiCapabilityCatalog {

    private final PluginManager pluginManager;
    private final SpecialWikiPageService specialWikiPageService;
    private final MessageSource messageSource;
    private final PluginMessageSource pluginMessageSource;
    private final PluginI18n pluginI18n;

    public WikiCapabilityCatalogImpl(PluginManager pluginManager,
                                     SpecialWikiPageService specialWikiPageService,
                                     MessageSource messageSource,
                                     PluginMessageSource pluginMessageSource,
                                     PluginI18n pluginI18n) {
        this.pluginManager = pluginManager;
        this.specialWikiPageService = specialWikiPageService;
        this.messageSource = messageSource;
        this.pluginMessageSource = pluginMessageSource;
        this.pluginI18n = pluginI18n;
    }

    @Override
    public String promptSection() {
        StringBuilder out = new StringBuilder(4096);
        out.append("## 本站扩展语法目录（实时汇总，写内容时优先使用）\n");
        out.append("以下能力来自当前加载的 core / 插件；不确定参数时先用 read 查看对应的模板或模块源码。\n");

        appendTags(out);
        appendFunctions(out);
        appendMagicWords(out);
        appendSpecialPages(out);
        appendPlugins(out);
        return out.toString();
    }

    private void appendTags(StringBuilder out) {
        out.append("\n### 解析器标签\n");
        Set<String> seen = new LinkedHashSet<>();
        // 与 VersionSpecialPage 的 core 标签表保持一致；ref/references 也直接由 WikitextRenderer
        // 实现（旧版本只在版本页里列出了一部分），这里一并告诉 AI。
        List<String> coreTags = List.of("ref", "references", "nowiki", "pre", "poem", "gallery",
                "syntaxhighlight", "markdown", "indicator");
        for (String name : coreTags) {
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            appendTag(out, name, text("version.tag." + name + ".usage",
                    text("version.tag." + name + ".description", "")));
        }
        for (ParserTag tag : pluginManager.extensions(ParserTag.class)) {
            if (!seen.add(tag.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String usage = pluginText("version.tag." + tag.name() + ".usage",
                    pluginText("version.tag." + tag.name() + ".description", ""));
            appendTag(out, tag.name(), usage);
        }
    }

    private void appendFunctions(StringBuilder out) {
        out.append("\n### 解析器函数\n");
        Set<String> seen = new LinkedHashSet<>();
        // 内置函数的签名（插件函数会自动追加在后面）。
        for (String usage : List.of(
                "{{#if:条件|真|假}}",
                "{{#ifeq:甲|乙|相等|不相等}}",
                "{{#switch:值|情况=结果|#default=默认}}",
                "{{#widget:微件名|参数=值}}",
                "{{#invoke:模块名|函数名|参数}}")) {
            if (seen.add(usage)) {
                out.append("- ").append(usage).append('\n');
            }
        }
        for (ParserFunction function : pluginManager.extensions(ParserFunction.class)) {
            String usage = "{{#" + function.name() + ":...}}";
            if (!seen.add(usage.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String extra = pluginText("version.function." + function.name() + ".usage", "");
            out.append("- ").append(usage);
            if (!extra.isBlank()) {
                out.append("：").append(shorten(extra, 180));
            }
            out.append('\n');
        }
    }

    private void appendMagicWords(StringBuilder out) {
        out.append("\n### 魔术字（示例）\n");
        Set<String> seen = new LinkedHashSet<>();
        for (String word : List.of("PAGENAME", "FULLPAGENAME", "NAMESPACE", "CURRENTYEAR",
                "CURRENTMONTH", "CURRENTDAY", "NUMBEROFPAGES", "NUMBEROFVIEWS")) {
            if (seen.add(word)) {
                out.append("- {{").append(word).append("}}\n");
            }
        }
        for (MagicWord word : pluginManager.extensions(MagicWord.class)) {
            if (!seen.add(word.name().toUpperCase(Locale.ROOT))) {
                continue;
            }
            out.append("- {{").append(word.name()).append("}}\n");
        }
    }

    private void appendSpecialPages(StringBuilder out) {
        out.append("\n### 特殊页面（可直接链接，如 [[特殊:页面名]]）\n");
        List<String> names = new ArrayList<>();
        for (SpecialPage page : specialWikiPageService.listRegisteredPages()) {
            if (!page.enabled()) {
                continue;
            }
            String name = page.name();
            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        out.append("- ").append(String.join("、", names.stream().limit(160).toList()));
        if (names.size() > 160) {
            out.append("…");
        }
        out.append('\n');
    }

    private void appendPlugins(StringBuilder out) {
        out.append("\n### 已安装扩展 / 插件（对应功能与语法）\n");
        for (LoadedPlugin loaded : pluginManager.loadedPlugins()) {
            PluginDescriptor descriptor = loaded.descriptor();
            String description = pluginI18n.description(descriptor);
            out.append("- **").append(pluginI18n.name(descriptor))
                    .append("**（`").append(descriptor.id()).append("`）：")
                    .append(shorten(description == null ? "" : description, 260))
                    .append('\n');
        }
    }

    private void appendTag(StringBuilder out, String name, String usage) {
        out.append("- <").append(name).append('>');
        if (usage != null && !usage.isBlank()) {
            out.append("：").append(shorten(usage, 220));
        }
        out.append('\n');
    }

    private String text(String key, String fallback) {
        try {
            String value = messageSource.getMessage(key, null, "", LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String pluginText(String key, String fallback) {
        try {
            String value = pluginMessageSource.resolveOrNull(key, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String shorten(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
