package cc.thonly.wikikernel.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 讨论命名空间映射（TalkPages）。 */
class TalkPagesTest {

    @Test
    void mainNamespaceMapsToDiscussionPrefix() {
        assertEquals("讨论:首页", TalkPages.talkTitle("首页"));
        assertEquals("首页", TalkPages.subjectTitle("讨论:首页"));
        assertTrue(TalkPages.isTalk("讨论:首页"));
        assertFalse(TalkPages.isTalk("首页"));
        assertNull(TalkPages.talkTitle("讨论:首页"), "讨论页不再有讨论页");
    }

    @Test
    void namespacesMapToTheirTalkNamespaces() {
        assertEquals("用户讨论:稀神灵梦", TalkPages.talkTitle("用户:稀神灵梦"));
        assertEquals("用户讨论:稀神灵梦", TalkPages.talkTitle("User:稀神灵梦"));
        assertEquals("用户:稀神灵梦", TalkPages.subjectTitle("用户讨论:稀神灵梦"));
        assertEquals("分类讨论:地理", TalkPages.talkTitle("分类:地理"));
        assertEquals("分类:地理", TalkPages.subjectTitle("分类讨论:地理"));
        assertEquals("模板讨论:问候", TalkPages.talkTitle("模板:问候"));
        assertEquals("文件讨论:示例.png", TalkPages.talkTitle("文件:示例.png"));
        assertEquals("帮助讨论:入门", TalkPages.talkTitle("帮助:入门"));
        assertEquals("WikiKernel讨论:Wiki.js", TalkPages.talkTitle("WikiKernel:Wiki.js"));
        assertTrue(TalkPages.isTalk("用户讨论:X"));
        assertFalse(TalkPages.isTalk("用户:X"));
    }
}
