package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：用户页资料卡的槽位（仿 MediaWiki 的 {@code SkinTemplateNavigation} / {@code PersonalUrls} /
 * SocialProfile 的用户页钩子）。
 * <p>
 * core 渲染 {@code 用户:X} / {@code User:X} 页面（含「尚未创建」的用户页）时按 {@link #order()} 升序调用：
 * <ol>
 *     <li>{@link #avatarUrl}：资料卡头像地址（可换成上传后的头像 / 头像服务）；返回 null / 空串用默认头像；</li>
 *     <li>{@link #linksHtml}：资料卡用户名下方「贡献 | …」那行里 <b>「贡献」之后的额外链接</b>
 *     （如「私信」「上传头像」）；返回 null / 空串表示不插入；</li>
 *     <li>{@link #panelHtml}：资料卡**下方、页面正文之前**的整宽面板（SocialProfile 式的
 *     「关于 / 统计栏 / 内嵌留言板」这类内容区）；返回 null / 空串表示不插入。</li>
 * </ol>
 * 约定：返回的 HTML 由 core 原样插入（{@code th:utext}），插件必须自行转义用户输入；
 * 抛异常只影响该插件，core 会跳过并继续其它插件（页面不 500）；
 * 没有任何插件注册时，资料卡用默认头像且没有额外链接（与迁移前一致）。
 */
public interface UserProfileHook extends Extension {

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 0;
    }

    /**
     * 用户页资料卡整体是否启用（缺省 {@code true}）。
     * <p>
     * 用于「插件关了功能开关就别渲染资料卡」这类场景：**任一**插件返回 {@code false} 时 core 不收集
     * 资料卡钩子并隐藏内置资料卡；没有插件时照常渲染（开关由插件自己声明与读取，core 不认识它）。
     */
    default boolean enabled() {
        return true;
    }

    /**
     * 资料卡头像地址。
     *
     * @return 头像 URL（站内路径或完整地址）；{@code null} / 空串 = 用 {@link UserProfileContext#defaultAvatarUrl()}
     */
    default String avatarUrl(UserProfileContext context) {
        return null;
    }

    /**
     * 资料卡用户名下方「贡献 | …」一行的额外链接 HTML（含插件自己需要的前后分隔符）。
     *
     * @return HTML（如 {@code <span class="wiki-profile-sep">|</span><a …>私信</a>}）；
     * {@code null} / 空串 = 不插入
     */
    default String linksHtml(UserProfileContext context) {
        return null;
    }

    /**
     * 资料卡**整体** HTML（可选）：返回非空时 core 用它替代内置资料卡
     * （头像 / 用户名 / 用户组徽章 / 等级积分 / 快捷链接那一块）。
     * <p>
     * 用于把资料卡版式完全交给插件（例如 SocialProfile 的 {@code #profile-top}：75×75 头像、
     * 大号用户名、红色积分徽章、{@code .profile-actions} 操作链接）。返回 null / 空串时 core 仍用
     * 内置资料卡（插件未加载 / 站点关闭用户交互时页面照常）。
     * <p>
     * 多个插件都返回时按 {@link #order()} 升序取**第一个非空**；抛异常只跳过该插件。
     *
     * @return HTML（由 core 原样插入 {@code th:utext}：插件必须自行转义用户输入）；null / 空串 = 用内置资料卡
     */
    default String profileTopHtml(UserProfileContext context) {
        return null;
    }

    /**
     * 资料卡下方、页面正文之前的**整宽面板** HTML（用户页专用槽位，仿 MediaWiki SocialProfile 的
     * {@code #profile-top} 之后那一整块内容区）。
     * <p>
     * core 的渲染顺序是：资料卡（头像 / 用户名 / 用户组 / 等级积分 / 操作按钮）→ 本方法返回的面板
     * → 页面正文。插件据此把「关于 / 资料字段」「统计栏」「内嵌留言板」这类**属于资料页**的内容
     * 放到正文之前，而不是像 {@code PageDisplayHook.afterContent} 那样堆在正文之后。
     * <p>
     * 与 {@link #linksHtml} 的差别：那个只有一行链接的位置，这里是一整块；与
     * {@code PageDisplayHook.beforeContent} 的差别：那个对**所有**页面生效，这里只对
     * {@code 用户:X} 用户页生效，并且已经拿到资料卡上下文（{@link UserProfileContext}）。
     * <p>
     * 多个插件都返回时按 {@link #order()} 升序依次拼接（与 {@link #linksHtml} 一致）；
     * 返回 null / 空串 / 抛异常都只是这一块不出现，页面照常渲染。
     * 未创建的用户页（{@code wiki/missing-user.html}）同样会调用本方法，此时
     * {@link UserProfileContext#userId()} 为 0，插件应自行决定要不要渲染（例如只给公开的留言板入口）。
     *
     * @return HTML（由 core 原样插入，{@code th:utext}：插件必须自行转义用户输入）；null / 空串 = 不插入
     */
    default String panelHtml(UserProfileContext context) {
        return null;
    }

    /**
     * 资料卡头像下方「上传头像」小链接的地址（**只有提供头像上传能力的插件才返回**）。
     * <p>
     * core 在 {@link #avatarUrl} 之外单独问一次这个方法：没有插件接管时（例如插件未加载 / 站点关闭
     * 用户交互）「上传头像」入口整条不渲染，不会出现点了 404 的死链接
     * （core 自己不再有头像上传页面，见 {@code core/src/test/.../AvatarTest}）。
     *
     * @return 上传页地址（如 {@code /wiki/特殊:上传头像}）；{@code null} / 空串 = 不显示这个入口
     */
    default String avatarUploadUrl(UserProfileContext context) {
        return null;
    }
}
