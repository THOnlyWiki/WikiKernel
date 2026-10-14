package cc.thonly.wikikernel.plugin.comments.service;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.plugin.comments.CommentsPlugin;
import cc.thonly.wikikernel.plugin.comments.data.WikiComment;
import cc.thonly.wikikernel.plugin.comments.hook.CommentSectionHook;
import cc.thonly.wikikernel.plugin.comments.hook.CommentTag;
import cc.thonly.wikikernel.plugin.comments.hook.CommentsHeadProvider;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评论插件：{@code <comment />} 标签（属性写入 + 标签剥离）、评论区渲染
 * （缩进 / 已删除占位 / 头像开关 / 删除权限）与 head 注入。
 */
class CommentSectionTest {

    private static final MessageSource MESSAGES = new MessageSource() {
        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return defaultMessage == null ? code : defaultMessage;
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return code;
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            return resolvable.getDefaultMessage();
        }
    };

    @Test
    void tagStripsItselfAndWritesProperty() {
        Map<String, String> properties = new LinkedHashMap<>();
        ParserTagCall call = tagCall(properties);
        assertEquals("", new CommentTag().render(call));
        assertEquals("1", properties.get(CommentTag.PROPERTY));
        assertEquals(List.of("comment", "comments"), new CommentTag().names());
        assertFalse(new CommentTag().expandBody());
    }

    @Test
    void rendersRowsWithDepthDeletedPlaceholderAndPermissions() {
        Fixture fixture = new Fixture();
        fixture.store.put(1L, comment(1, "首页", null, 7L, "第一条 **粗体**", false));
        fixture.store.put(2L, comment(2, "首页", 1L, 8L, "回复", false));
        fixture.store.put(3L, comment(3, "首页", 2L, 7L, "旧内容", true));
        fixture.store.put(4L, comment(4, "首页", null, null, "匿名评论", false));

        CommentSection section = fixture.section(true, true);
        String html = section.render("首页", fixture.viewer(7L, "作者"));

        assertTrue(html.contains("<div class=\"wiki-comments\" data-api=\"/api/comments\" data-page=\"首页\">"), html);
        assertTrue(html.contains("data-id=\"1\" data-depth=\"0\""), html);
        assertTrue(html.contains("data-id=\"2\" data-depth=\"1\""), html);
        assertTrue(html.contains("data-id=\"3\" data-depth=\"2\""), html);
        assertTrue(html.contains("comment-tombstone"), html);
        assertFalse(html.contains("旧内容"), "已删除的评论不渲染内容");
        assertTrue(html.contains("匿名"), html);
        // 作者本人可以删除自己的评论（第 1 条），别人的（第 2 条）不行
        assertEquals(1, countOccurrences(html, "comment-delete-link"), html);
        assertTrue(html.contains("/avatars/u7-1.png"), html);
        assertTrue(html.contains("<strong>粗体</strong>"), "评论内容按 wikitext 渲染：" + html);
    }

    @Test
    void commentAdminCanDeleteOthersComments() {
        Fixture fixture = new Fixture();
        fixture.store.put(1L, comment(1, "首页", null, 7L, "别人写的", false));
        CommentSection section = fixture.section(true, true);

        assertFalse(section.canDelete(fixture.viewer(9L, "路人"), fixture.store.get(1L)));
        assertTrue(section.canDelete(fixture.viewer(9L, "评论管理员", "评论管理员"), fixture.store.get(1L)));
        assertTrue(section.canDelete(fixture.viewer(9L, "管理员", "管理员"), fixture.store.get(1L)));
        assertTrue(section.canDelete(fixture.viewer(7L, "作者"), fixture.store.get(1L)));
    }

    @Test
    void anonymousCannotCommentAndAvatarsFollowSwitch() {
        Fixture fixture = new Fixture();
        fixture.store.put(1L, comment(1, "首页", null, 7L, "内容", false));
        CommentSection section = fixture.section(true, false);
        String html = section.render("首页", Viewer.ANONYMOUS);

        assertFalse(html.contains("<form"), "未登录时不显示发表表单：" + html);
        assertTrue(html.contains("comment-intro"), html);
        assertFalse(html.contains("comment-avatar"), "关闭用户交互时不显示评论头像：" + html);
    }

    @Test
    void emptyListShowsPlaceholderAndForm() {
        Fixture fixture = new Fixture();
        CommentSection section = fixture.section(true, true);
        String html = section.render("空评论页", fixture.viewer(7L, "作者"));
        assertTrue(html.contains("comment-empty"), html);
        assertTrue(html.contains("<form class=\"wiki-form comment-form\""), html);
    }

    @Test
    void hookOnlyRendersWhenPropertyPresentAndEnabled() {
        Fixture fixture = new Fixture();
        fixture.store.put(1L, comment(1, "首页", null, 7L, "内容", false));
        CommentSectionHook hook = new CommentSectionHook(fixture.section(true, true));
        Viewer viewer = fixture.viewer(7L, "作者");

        // 没有 <comment />（页面属性没有标记）→ 不注入评论区
        assertNull(hook.afterContent(context("首页", Map.of(), viewer)));
        // 有标记 → 注入评论区
        String html = hook.afterContent(context("首页", Map.of(CommentTag.PROPERTY, "1"), viewer));
        assertTrue(html != null && html.contains("wiki-comments"), String.valueOf(html));
        // 功能关闭 → 不注入
        CommentSectionHook disabled = new CommentSectionHook(fixture.section(false, true));
        assertNull(disabled.afterContent(context("首页", Map.of(CommentTag.PROPERTY, "1"), viewer)));
    }

    @Test
    void headProviderInjectsAssetsWhenEnabled() {
        Fixture fixture = new Fixture();
        CommentsHeadProvider provider = new CommentsHeadProvider(fixture.section(true, true));
        assertTrue(provider.siteWide());
        String joined = String.join("\n", provider.headItems(null));
        assertTrue(joined.contains("/plugins/comments/comments.css"), joined);
        assertTrue(joined.contains("/plugins/comments/comments.js"), joined);
        assertTrue(joined.contains("WIKI_CONFIG.comments = true"), joined);

        CommentsHeadProvider disabled = new CommentsHeadProvider(fixture.section(false, true));
        assertEquals(List.of(), disabled.headItems(null));
    }

    // ==================== 测试替身 ====================

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static WikiComment comment(long id, String page, Long parentId, Long userId, String content,
                                       boolean deleted) {
        WikiComment comment = new WikiComment();
        comment.setId(id);
        comment.setPageTitle(page);
        comment.setParentId(parentId);
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setDeleted(deleted);
        comment.setCreateAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        return comment;
    }

    private static ParserTagCall tagCall(Map<String, String> properties) {
        return new ParserTagCall() {
            private final WikiRenderContext context = new WikiRenderContext() {
                private final Map<String, Object> store = new LinkedHashMap<>();

                @Override
                public String pageTitle() {
                    return "首页";
                }

                @Override
                public Map<String, Object> store() {
                    return store;
                }

                @Override
                public void setProperty(String name, String value) {
                    properties.put(name, value);
                }

                @Override
                public String property(String name, String defaultValue) {
                    return properties.getOrDefault(name, defaultValue);
                }

                @Override
                public Map<String, String> properties() {
                    return Map.copyOf(properties);
                }

                @Override
                public String deferWikitext(Supplier<String> resolver) {
                    return resolver.get();
                }
            };

            @Override
            public WikiRenderContext context() {
                return context;
            }

            @Override
            public String tag() {
                return "comment";
            }

            @Override
            public Map<String, String> attributes() {
                return Map.of();
            }

            @Override
            public String body() {
                return "";
            }

            @Override
            public String expand(String wikitext) {
                return wikitext;
            }
        };
    }

    private static PageDisplayContext context(
            String title, Map<String, String> properties, Viewer viewer) {
        return new PageDisplayContext() {
            @Override
            public long pageId() {
                return 1;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public String content() {
                return "正文";
            }

            @Override
            public boolean oldRevision() {
                return false;
            }

            @Override
            public String pageUrl() {
                return "/wiki/" + title;
            }

            @Override
            public Map<String, String> properties() {
                return properties;
            }

            @Override
            public Map<String, String> query() {
                return Map.of();
            }

            @Override
            public Viewer viewer() {
                return viewer;
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }
        };
    }

    /** 内存数据替身：评论表 + 用户 + 头像 + 渲染 + 访问控制。 */
    private static final class Fixture {

        private final Map<Long, WikiComment> store = new LinkedHashMap<>();

        CommentSection section(boolean enabled, boolean userInteraction) {
            SiteConfig config = new SiteConfig() {
                @Override
                public String get(String key, String defaultValue) {
                    return defaultValue;
                }

                @Override
                public boolean getBoolean(String key, boolean defaultValue) {
                    if (CommentsPlugin.ENABLED_KEY.equals(key)) {
                        return enabled;
                    }
                    if ("features.social-profile".equals(key) || "features.user-interaction".equals(key)) {
                        // 插件改名（user-interaction → social-profile）前后的两个键在替身里返回同一个值：
                        // 真实 core 也是新键优先、旧键兜底（见 FeatureService.SOCIAL_PROFILE_KEY / USER_INTERACTION_KEY）
                        return userInteraction;
                    }
                    return defaultValue;
                }
            };
            CommentStore comments = new CommentStore() {
                @Override
                public List<WikiComment> list(String pageTitle) {
                    return store.values().stream().filter(row -> pageTitle.equals(row.getPageTitle())).toList();
                }

                @Override
                public Optional<WikiComment> find(long commentId) {
                    return Optional.ofNullable(store.get(commentId));
                }

                @Override
                public WikiComment insert(WikiComment comment) {
                    store.put(comment.getId(), comment);
                    return comment;
                }

                @Override
                public void update(WikiComment comment) {
                    store.put(comment.getId(), comment);
                }

                @Override
                public long count(String pageTitle) {
                    return list(pageTitle).stream().filter(row -> !Boolean.TRUE.equals(row.getDeleted())).count();
                }
            };
            UserStore users = new UserStore() {
                @Override
                public Optional<UserRef> byId(long userId) {
                    return Optional.of(new UserRef(userId, "用户" + userId, "u" + userId + "-1.png", false));
                }

                @Override
                public Optional<UserRef> byName(String username) {
                    return Optional.empty();
                }

                @Override
                public Map<Long, String> names(Collection<Long> userIds) {
                    Map<Long, String> names = new LinkedHashMap<>();
                    userIds.forEach(id -> names.put(id, "用户" + id));
                    return names;
                }

                @Override
                public Map<Long, String> avatars(Collection<Long> userIds) {
                    Map<Long, String> files = new LinkedHashMap<>();
                    userIds.forEach(id -> files.put(id, "u" + id + "-1.png"));
                    return files;
                }

                @Override
                public Set<String> groups(long userId) {
                    return Set.of();
                }

                @Override
                public boolean exists(String username) {
                    return false;
                }
            @Override
            public List<UserRef> randomWithAvatar(int limit) {
                return List.of();
            }

            };
            WikiText wikiText = (wikitext, pageTitle) -> wikitext
                    .replace("**粗体**", "<strong>粗体</strong>");
            AccessCheck access = new AccessCheck() {
                @Override
                public boolean can(AccessAction action, String target,
                                   Viewer viewer) {
                    return true;
                }

                @Override
                public Optional<String> blockNotice(Viewer viewer) {
                    return Optional.empty();
                }
            };
            return new CommentSection(comments, users, wikiText, access, config, MESSAGES);
        }

        Viewer viewer(long id, String name, String... groups) {
            return new Viewer(id, name, false, Set.of(groups), false);
        }
    }
}
