package cc.thonly.wikikernel.plugin.comments.service;

import cc.thonly.wikikernel.plugin.comments.data.WikiComment;

import java.util.List;
import java.util.Optional;

/**
 * 评论数据访问（评论插件自己的表 {@code wiki_comment}）。
 * <p>
 * 只管数据（{@code wiki_comment} 表的读写），发表 / 删除的规则（谁能删、写日志、通知）由服务层 /
 * 页面层自己决定。实现是插件子容器里的 {@link CommentStoreJdbc}（走
 * {@code common.service.PluginDatabase} 裸 SQL）；表结构由 {@code CommentsPlugin.schemaEntities()}
 * 声明、core 的 Auto-DDL 建表 / 补列（SQLite / MySQL / H2 通用）。
 * <p>
 * 方法签名与迁移前的 {@code common.service.CommentStore} 完全一致。
 */
public interface CommentStore {

    /** 某页面的全部评论（含已删除占位），按发表时间升序。 */
    List<WikiComment> list(String pageTitle);

    /** 按 ID 查评论。 */
    Optional<WikiComment> find(long commentId);

    /** 插入评论（返回带自增 ID 的实体）。 */
    WikiComment insert(WikiComment comment);

    /** 更新评论（软删除标记等）。 */
    void update(WikiComment comment);

    /** 某页面的评论条数（不含已删除）。 */
    long count(String pageTitle);
}
