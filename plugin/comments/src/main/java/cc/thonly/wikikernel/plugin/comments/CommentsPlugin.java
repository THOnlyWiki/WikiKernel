package cc.thonly.wikikernel.plugin.comments;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.plugin.comments.data.WikiComment;
import cc.thonly.wikikernel.plugin.comments.hook.CommentMessages;
import cc.thonly.wikikernel.plugin.comments.hook.CommentSectionHook;
import cc.thonly.wikikernel.plugin.comments.hook.CommentTag;
import cc.thonly.wikikernel.plugin.comments.hook.CommentsHeadProvider;
import cc.thonly.wikikernel.plugin.comments.hook.CommentsSettingsSection;
import cc.thonly.wikikernel.plugin.comments.service.CommentSection;
import cc.thonly.wikikernel.plugin.comments.service.CommentStore;
import cc.thonly.wikikernel.plugin.comments.service.CommentStoreJdbc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.util.List;

/**
 * Comments 扩展移植（对应 MediaWiki 的 {@code Comments} 扩展）：
 * 正文里写 {@code <comment />}（或 {@code <comments />}）的页面，底部显示评论区：
 * 发表 / 回复（支持 wikitext）、软删除、作者本人或「评论管理员」/ 管理员 / 行政员可删除。
 * <p>
 * 迁移后的分工：core 不再内置评论（标签由插件剥离、评论区由插件渲染、API 由插件提供），
 * 表 {@code wiki_comment} 与实体 {@link WikiComment} **属于插件自己**：插件用
 * {@code schemaEntities()} 声明、core 的 Auto-DDL 建表 / 补列，数据访问走
 * {@code common.service.PluginDatabase}（见 {@link CommentStoreJdbc}）；
 * 开关沿用 {@code features.comments}（默认开启，可在 {@code /init} 或站点设置里关闭）。
 */
public class CommentsPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = "features.comments";

    /** 自动显示键：没有标签的页面是否也显示评论区（默认开启）。 */
    public static final String AUTO_DISPLAY_KEY = "comments.auto-display";

    @Autowired
    private CommentStore commentStore;

    @Autowired
    private UserStore userStore;

    @Autowired
    private WikiText wikiText;

    @Autowired
    private AccessCheck accessCheck;

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private LogWriter logWriter;

    /** 评论区渲染（Spring Bean：@RestController 与钩子共用同一个实例）。 */
    @Autowired
    private CommentSection section;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("comments", "Comments", "1.0.0",
                "Page comment sections via the <comment /> tag, with posting, replies"
                        + " and soft deletion (wikitext supported)");
    }

    /**
     * 插件自己的表 {@code wiki_comment}：core 在 {@code onStart} 之前按与 core 相同的
     * Auto-DDL 规则建表 / 补列（SQLite / MySQL / H2），数据访问见
     * {@link CommentStoreJdbc}（走 JDBC 桥）。
     */
    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(WikiComment.class);
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new CommentMessages());
        context.register(ParserTag.class, new CommentTag());
        context.register(PageDisplayHook.class, new CommentSectionHook(section));
        context.register(HtmlHeadProvider.class, new CommentsHeadProvider(section));
        context.register(SiteSettingsSection.class, new CommentsSettingsSection(section));
        // 日志类型（发表 / 删除评论）：core 的 特殊:日志 与最近更改按注册中心识别，
        // 展示名走 core 已有的消息键 log.typeName.comment / log.typeName.commentdelete
        context.register(LogType.class, new CommentLogType("comment"));
        context.register(LogType.class, new CommentLogType("commentdelete"));
        context.logger().log(System.Logger.Level.INFO, section.enabled()
                ? "已启用评论：默认自动显示评论区；<comment /> 手动开启，<comment disabled /> 关闭本站页面（/plugins/comments/）"
                : "评论已关闭（features.comments=false）：标签仍会剥离，但不显示评论区");
    }

    /** 评论相关日志类型（仿 MediaWiki Comments 的 comment / commentdelete）。 */
    record CommentLogType(String id) implements LogType {

        @Override
        public TargetKind targetKind() {
            return TargetKind.PAGE;
        }

        @Override
        public int order() {
            return 60;
        }
    }
}
