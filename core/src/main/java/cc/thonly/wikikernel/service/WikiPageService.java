package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.List;
import java.util.Optional;

/**
 * 页面与历史版本服务。
 * <p>
 * 模型：一行 {@code wiki_page} = 一个页面身份；一行 {@code wiki_revision} = 一次编辑。
 * 编辑 = 插入新版本并更新页面的 latestRevisionId；查询默认只看最新可见版本。
 */
public interface WikiPageService {

    /** 创建页面（已存在则返回 null，调用方应改用 {@link #editPage}）。 */
    WikiPage createPage(WikiUser user, String title, String content);

    /** 创建页面（带编辑摘要）。 */
    WikiPage createPage(WikiUser user, String title, String content, String comment);

    /** 编辑页面（页面不存在时等价于创建）。 */
    WikiPage editPage(WikiUser user, String title, String content);

    /** 编辑页面（带编辑摘要）。 */
    WikiPage editPage(WikiUser user, String title, String content, String comment);

    /** 保存编辑，并写入机器人标记与变更标签（标签逗号分隔，可空）。 */
    WikiPage editPage(WikiUser user, String title, String content, String comment, boolean bot, String tags);

    WikiPage editPage(WikiUser user, String title, String content, String comment,
                      boolean bot, boolean minor, String tags);

    /**
     * 带页面最新版本乐观锁的编辑。返回 {@code null} 表示 expectedRevisionId 已过期，且不会留下孤立版本。
     */
    WikiPage editPageIfLatest(WikiUser user, String title, String content, String comment,
                              boolean bot, String tags, Long expectedRevisionId);

    WikiPage editPageIfLatest(WikiUser user, String title, String content, String comment,
                              boolean bot, boolean minor, String tags, Long expectedRevisionId);

    /** 删除页面（软删除：deleted=true，历史保留），不存在返回空。 */
    WikiPage deletePage(WikiUser user, String title);

    /**
     * 按标题查**已删除**的页面（普通的 {@code getByTitle} 会过滤掉删除的页面）。
     * 用于「访问被删页面时给提示」与「特殊:已删除页面」里查看 / 恢复被删内容。
     */
    Optional<WikiPage> findDeletedByTitle(String title);

    /** 已删除的页面（删除时间倒序，最多 {@code limit} 条；{@code limit <= 0} 时给默认上限）。 */
    List<WikiPage> deletedPages(int limit);

    /**
     * 恢复已删除的页面（{@code deleted=true → false}）并写一条 {@code restore} 日志。
     * 页面不存在 / 没有被删除时返回 {@code null}。
     */
    WikiPage restorePage(WikiUser user, String title);

    /**
     * 移动页面（改标题，编辑历史保留在同一个页面 ID 下）。
     * 调用方需先校验：目标标题不存在、受保护页面只有管理员能移动。
     * 源页面不存在返回 null。旧标题不留重定向（等价于 {@code leaveRedirect=false}）。
     */
    default WikiPage movePage(WikiUser user, String fromTitle, String toTitle) {
        return movePage(user, fromTitle, toTitle, false);
    }

    /**
     * 移动页面；{@code leaveRedirect} 为 true 时在旧标题创建 {@code #REDIRECT [[新标题]]}
     * 页面（仿 MediaWiki 移动页面的「留下重定向」选项，不额外记创建日志）。
     */
    WikiPage movePage(WikiUser user, String fromTitle, String toTitle, boolean leaveRedirect);

    /**
     * 设置 / 解除页面保护（只有管理员可编辑），并记保护日志。
     * 页面不存在返回空。
     */
    Optional<WikiPage> setProtected(WikiUser user, String title, boolean protectedPage);

    /** 按标题查可见页面（未隐藏、未删除）。 */
    Optional<WikiPage> getVisibleByTitle(String title);

    /** 按标题查页面（仅排除已删除，隐藏页也可拿到，供编辑等场景）。 */
    Optional<WikiPage> getByTitle(String title);

    /** 最新未被删除的版本。 */
    Optional<WikiRevision> getLatestRevision(Long pageId);

    /** 最新未被删除版本的内容（模板、侧边栏、搜索摘要等用）。 */
    Optional<String> getLatestContent(Long pageId);

    /** 按版本 ID 查版本（含已删除版本，展示前自行判断）。 */
    Optional<WikiRevision> getRevision(Long revisionId);

    /**
     * 按页面 ID 取页面（**含已删除页面**，不过滤 {@code deleted} / {@code hided}）：
     * 供 Action API / REST API 这类「先拿到版本号再反查页面」的调用方做权限判定用，
     * 调用方自己判断 {@code deleted} 与读权限。
     */
    Optional<WikiPage> getById(Long pageId);

    /** 页面历史（含已删除版本，按版本 ID 倒序）。 */
    List<WikiRevision> getHistory(Long pageId);

    /** 指定版本之前最近的可见版本（同页面、未删除；差异对比页用）。 */
    Optional<WikiRevision> getPreviousRevision(Long pageId, Long revisionId);

    /** 指定版本之后最近的可见版本（同页面、未删除）。 */
    Optional<WikiRevision> getNextRevision(Long pageId, Long revisionId);

    /**
     * 把某个版本标记为「AI 代理协助编辑」（词条底部的隐藏水印与追溯用；版本不存在返回 false）。
     *
     * @param revisionId 版本 ID
     * @param aiModel    厂商 / 模型（如 {@code deepseek/deepseek-chat}）
     */
    boolean markAiAssisted(Long revisionId, String aiModel);

    /**
     * 页面自定义 CSS（按**站点默认皮肤**生效，见 {@link #getCSS(String, String)}）：
     * 全站 {@code Wiki.css} + {@code WikiKernel:Common.css} + 站点默认皮肤的 {@code WikiKernel:<皮肤 id>.css}
     * + {@code {title}/XXX.css} 子页面，返回最新可见版本的内容（按标题排序，全站样式在最前）。
     * <p>
     * 调用点若已经解析出「本次渲染真正生效的皮肤」，应改用 {@link #getCSS(String, String)} 把皮肤 id 传进来。
     */
    List<String> getCSS(String title);

    /**
     * 页面自定义 CSS（仿 MediaWiki 的 {@code MediaWiki:<皮肤>.css}，按皮肤生效）：
     * <ol>
     *     <li>全站 {@code Wiki.css}；</li>
     *     <li>全站 {@code WikiKernel:Common.css}；</li>
     *     <li><b>当前皮肤</b> {@code WikiKernel:<皮肤 id>.css}（如皮肤 {@code plain} → {@code WikiKernel:plain.css}）；</li>
     *     <li>页面级 {@code {title}/XXX.css} 子页面（按标题排序）。</li>
     * </ol>
     * 顺序即优先级：越靠后越能覆盖前面的规则。皮肤页不存在（或被隐藏 / 无内容）时**跳过、不报错**。
     *
     * @param skinId 皮肤 **id**（{@code Skin#id()} 的原样写法，如 {@code plain} / {@code vector}）；
     *               空白或含 {@code [A-Za-z0-9_-]} 之外的字符时按「没有皮肤页」处理，不再查询。
     *               标题本身仍走 {@code WikiTitles} 规范化（首字母自动大写等），所以
     *               {@code WikiKernel:plain.css} 与 {@code WikiKernel:Plain.css} 是同一个页面。
     */
    List<String> getCSS(String title, String skinId);

    /**
     * 页面自定义 JS（按**站点默认皮肤**生效，见 {@link #getJS(String, String)}）：
     * 全站 {@code WikiKernel:Wiki.js} + {@code WikiKernel:Common.js} + 站点默认皮肤的 {@code WikiKernel:<皮肤 id>.js}
     * + {@code {title}/XXX.js} 子页面，返回脚本页标题（按顺序：全站在前，子页面按标题排序；只包含存在且非空的页面）。
     * 模板用 {@code ?action=raw&ctype=text/javascript} 以 {@code <script src>} 加载。
     */
    List<String> getJS(String title);

    /**
     * 页面自定义 JS（仿 MediaWiki 的 {@code MediaWiki:<皮肤>.js}，按皮肤生效）：
     * <ol>
     *     <li>全站 {@code WikiKernel:Wiki.js}；</li>
     *     <li>全站 {@code WikiKernel:Common.js}；</li>
     *     <li><b>当前皮肤</b> {@code WikiKernel:<皮肤 id>.js}（如皮肤 {@code plain} → {@code WikiKernel:plain.js}）；</li>
     *     <li>页面级 {@code {title}/XXX.js}（TypeScript 编译开启时含 {@code .ts} / {@code .tsx}）子页面，按标题排序。</li>
     * </ol>
     * 皮肤页不存在（或被隐藏 / 无内容）时**跳过、不报错**。
     *
     * @param skinId 皮肤 **id**（{@code Skin#id()} 的原样写法）；空白或含非法字符时按「没有皮肤页」处理。
     */
    List<String> getJS(String title, String skinId);
}
