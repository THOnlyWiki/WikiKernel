package cc.thonly.wikikernel.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标题规则单测（仿 MediaWiki）：
 * 首字母自动大写（{@code $wgCapitalLinks}，可配置）、非法字符校验（{@code title-invalid-*} 错误）。
 */
class WikiTitlesTest {

    @AfterEach
    void restoreCapitalization() {
        WikiTitles.setCapitalizeFirstLetter(true);
    }

    @Test
    void capitalizesFirstLetterAfterNamespaceOnly() {
        assertEquals("Java 开发教程", WikiTitles.normalize("java 开发教程"));
        assertEquals("模板:Infobox", WikiTitles.normalize("模板:infobox"));
        assertEquals("模板:Infobox", WikiTitles.normalize("template:infobox"),
                "英文别名先归一到规范前缀（模板:），再大写命名空间后的首字符");
        // 子页面的后续片段不自动大写（与 MediaWiki 的 ucfirst 行为一致）
        assertEquals("Foo/bar/BAZ", WikiTitles.normalize("foo/bar/BAZ"));
        // 开头的冒号表示主命名空间
        assertEquals("Foo", WikiTitles.normalize(":foo"));
        // 非命名空间的前缀（time:）属于主命名空间标题
        assertEquals("Time: now", WikiTitles.normalize("time: now"));
    }

    @Test
    void canonicalizesNamespaceAliasesToLocalizedPrefix() {
        // 别名 / 大小写变体 → 规范（本地化）前缀；前缀以外的部分不受影响
        assertEquals("用户:张三", WikiTitles.normalize("User:张三"));
        assertEquals("用户:张三", WikiTitles.normalize("user:张三"));
        assertEquals("用户讨论:张三", WikiTitles.normalize("USER_TALK:张三"));
        assertEquals("分类:示例", WikiTitles.normalize("Category:示例"));
        assertEquals("文件:图片.png", WikiTitles.normalize("File:图片.png"));
        assertEquals("文件:图片.png", WikiTitles.normalize("Image:图片.png"), "Image: 是 File: 的别名");
        assertEquals("特殊:版本", WikiTitles.normalize("Special:版本"));
        assertEquals("WikiKernel:侧栏", WikiTitles.normalize("wikikernel:侧栏"));
        // 规范前缀自身保持不变（幂等）
        assertEquals("用户:张三", WikiTitles.normalize("用户:张三"));
        assertEquals("模板:信息框", WikiTitles.normalize(WikiTitles.normalize("tEmPlAtE:信息框")));
        // 只有前缀本身（无标题部分）不归一出多余内容，交给校验报「缺少标题」
        assertEquals("empty", WikiTitles.validate("User:").orElseThrow().code());
    }

    @Test
    void unicodeWhitespaceFoldsToSpace() {
        assertEquals("A b", WikiTitles.normalize("a\u3000b"));
        assertEquals("A b", WikiTitles.normalize("a\u00A0b"));
        assertEquals("A b", WikiTitles.normalize("a\t\tb"));
    }

    @Test
    void capitalizationCanBeDisabled() {
        WikiTitles.setCapitalizeFirstLetter(false);
        assertEquals("java 开发教程", WikiTitles.normalize("java 开发教程"));
        assertEquals("模板:infobox", WikiTitles.normalize("模板:infobox"));
    }

    @Test
    void rejectsIllegalCharacters() {
        for (String c : new String[]{"#", "<", ">", "[", "]", "|", "{", "}"}) {
            assertTrue(WikiTitles.validate("坏" + c + "标题").isPresent(), c + " 应被拒绝");
        }
        assertEquals("characters", WikiTitles.validate("a[b").orElseThrow().code());
        assertEquals("characters", WikiTitles.validate("100%25.png").orElseThrow().code());
        assertEquals("characters", WikiTitles.validate("A&amp;B").orElseThrow().code());
        assertEquals("[", WikiTitles.validate("a[b").orElseThrow().detail());
    }

    @Test
    void rejectsRelativeMagicTildeLeadingColonAndOversize() {
        assertEquals("relative", WikiTitles.validate("a/../b").orElseThrow().code());
        assertEquals("relative", WikiTitles.validate("..").orElseThrow().code());
        assertEquals("magic-tilde", WikiTitles.validate("签到~~~").orElseThrow().code());
        assertEquals("leading-colon", WikiTitles.validate("::foo").orElseThrow().code());
        assertEquals("too-long", WikiTitles.validate("长".repeat(100)).orElseThrow().code());
        assertEquals("empty", WikiTitles.validate("   ").orElseThrow().code());
        assertEquals("empty", WikiTitles.validate("模板:").orElseThrow().code());
        assertEquals("empty", WikiTitles.validate("分类:").orElseThrow().code());
    }

    @Test
    void acceptsNormalTitles() {
        assertTrue(WikiTitles.validate("Java 开发教程").isEmpty());
        assertTrue(WikiTitles.validate("1+1=2").isEmpty());
        assertTrue(WikiTitles.validate("C++ 开发教程").isEmpty());
        assertTrue(WikiTitles.validate("首页/样式.css").isEmpty());
        assertTrue(WikiTitles.validate("签到~").isEmpty(), "单个波浪号可以");
        assertTrue(WikiTitles.validate("版本 1.2.3").isEmpty(), "普通点号可以");
        assertTrue(WikiTitles.validate("time: now").isEmpty(), "非命名空间的冒号可以");
        assertTrue(WikiTitles.validate(":foo").isEmpty(), "开头单个冒号表示主命名空间");
    }
}
