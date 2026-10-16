package cc.thonly.wikikernel.plugin.echo.service;

import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EchoNotifier} 的纯函数测试：正文里的提及提取（{@code [[用户:名]]} / {@code @名}），
 * 外加 {@link EchoSupport} 的标题判定 / 转义 / 路径工具。
 */
class EchoNotifierMentionTest {

    @Test
    void extractsWikiLinkMentions() {
        String content = "你好 [[用户:稀神灵梦]]，请看 [[User:Alice|讨论]] 与 [[用户:Bob#反馈|Bob]]。";
        Set<String> names = EchoNotifier.extractMentionedNames(content);
        assertTrue(names.contains("稀神灵梦"));
        assertTrue(names.contains("Alice"));
        assertTrue(names.contains("Bob"));
        assertEquals(3, names.size());
    }

    @Test
    void extractsAtMentions() {
        Set<String> names = EchoNotifier.extractMentionedNames("谢谢 @稀神灵梦 和 @Bob_2 的帮忙");
        assertTrue(names.contains("稀神灵梦"));
        assertTrue(names.contains("Bob_2"));
    }

    @Test
    void ignoresNonUserLinksAndDeduplicates() {
        String content = "[[页面:示例]] [[用户:甲]] [[用户:甲]] [[文件:X.png]] @甲";
        Set<String> names = EchoNotifier.extractMentionedNames(content);
        assertEquals(Set.of("甲"), names);
    }

    @Test
    void userTalkTitleParsing() {
        assertEquals("稀神灵梦", EchoSupport.userNameFromTalk("用户讨论:稀神灵梦"));
        assertEquals("甲", EchoSupport.userNameFromTalk("用户讨论:甲/子页面"));
        assertNull(EchoSupport.userNameFromTalk("用户:甲"));
        assertTrue(EchoSupport.isUserTalk("用户讨论:甲"));
        assertTrue(EchoSupport.isUserTalk("User talk:Alice"));
        assertEquals("甲", EchoSupport.userNameFromUserPage("用户:甲"));
    }

    @Test
    void escapeAndPathHelpers() {
        assertEquals("&lt;a&gt;", EchoSupport.escape("<a>"));
        // 冒号会被 URL 编码（与 social-profile 的 pagePath 行为一致，链接仍然可用）
        assertEquals("/wiki/%E7%94%A8%E6%88%B7%3A%E7%94%B2", EchoSupport.pagePath("用户:甲"));
        assertEquals("/wiki/%E7%89%B9%E6%AE%8A%3A%E9%80%9A%E7%9F%A5?type=mention",
                EchoSupport.pagePath("特殊:通知?type=mention"));
        // 子页面的 / 保留为路径分隔
        assertEquals("/wiki/%E7%94%A8%E6%88%B7%3A%E7%94%B2/%E9%A1%B5", EchoSupport.pagePath("用户:甲/页"));
    }
}