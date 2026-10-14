package cc.thonly.wikikernel.common.extension;

/**
 * 一次「用户页资料卡」渲染的上下文（供 {@link UserProfileHook} 使用，仿 MediaWiki SocialProfile 的
 * {@code SocialProfilePageCreate} / {@code SkinTemplateNavigation} 一类钩子的数据面）。
 * <p>
 * core 在渲染 {@code 用户:X} / {@code User:X} 页面时构造它：标题里的用户名就是资料卡所属用户，
 * 账号不存在时 {@link #userId()} 为 0（此时只有默认头像，插件通常也不该显示「仅本人」的入口）。
 */
public interface UserProfileContext {

    /** 资料卡所属用户的 ID；账号不存在（页面存在但没这个账号）为 0。 */
    long userId();

    /** 资料卡所属用户名（标题 {@code 用户:X} 里的 X，不区分大小写保留原样）。 */
    String username();

    /** 看资料卡的当前用户是不是资料卡本人。 */
    boolean self();

    /** 当前用户（匿名时为 {@link Viewer#ANONYMOUS}）。 */
    Viewer viewer();

    /** core 的默认头像地址（{@code /img/avatar-default.svg}）。 */
    String defaultAvatarUrl();
}
