package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserProfileHookService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.HandlerMapping;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 用户页资料卡的 core 侧接线（**不在** {@code WikiPageController} 里硬编码插件逻辑）：
 * 从请求 URI 的标题（{@code 用户:X} / {@code User:X}）解析出资料卡用户，取核心数据并问插件
 * {@code UserProfileHook} 要头像、额外链接与面板，把结果放进模型：
 * <ul>
 *     <li>{@code userPageName}：资料卡用户名（null = 不是用户页 / 用户交互关闭 → 模板不渲染资料卡）；</li>
 *     <li>{@code userAvatarUrl}：头像地址（插件返回的优先，没有插件 / 插件返回空则用默认头像）；</li>
 *     <li>{@code userProfileLinks}：用户名下方「贡献 | …」一行的额外链接 HTML（插件贡献，可缺省）；</li>
 *     <li>{@code userAvatarUploadUrl}：插件提供的头像上传入口地址（**资料卡不再渲染它**：本人在
 *     SocialProfile 面板的操作按钮行里已有「上传头像」按钮，避免整页出现两个入口；
 *     模型键保留给仍想用它的模板 / 插件）；</li>
 *     <li>{@code userProfilePanelHtml}：资料卡**下方、正文之前**的整宽面板 HTML（插件贡献：SocialProfile
 *     式的「关于」「统计栏」「内嵌留言板」；见 {@code UserProfileHook.panelHtml}，可缺省）；</li>
 *     <li>{@code userExists} / {@code userGroupBadges}（{@link UserGroupBadges.Result}：过滤掉隐式组、
 *     最多 3 个徽章 + 「+N」） / {@code userEditCount} / {@code userRegisteredAt}：资料卡右栏的用户组徽章与
 *     「注册时间 / 编辑数」统计（账号不存在时 {@code userExists=false}，
 *     这些键一律不放进模型，模板不显示那一行）。</li>
 * </ul>
 * 「用户交互」功能关闭（站点设置 {@code features.social-profile}）时资料卡整体隐藏，与迁移前一致；
 * 插件未加载时用默认头像、没有额外链接与面板（插件行为见 {@code plugin/social-profile}）。
 */
@ControllerAdvice
public class UserProfileAdvice {

    private final UserStore userStore;
    private final CurrentUser currentUser;
    private final UserProfileHookService userProfileHooks;
    private final PermissionService permissionService;
    private final WikiUserMapper userMapper;
    private final WikiRevisionMapper revisionMapper;
    private final UserGroupBadges groupBadges;

    public UserProfileAdvice(UserStore userStore, CurrentUser currentUser,
                             UserProfileHookService userProfileHooks, PermissionService permissionService,
                             WikiUserMapper userMapper,
                             WikiRevisionMapper revisionMapper, UserGroupBadges groupBadges) {
        this.userStore = userStore;
        this.currentUser = currentUser;
        this.userProfileHooks = userProfileHooks;
        this.permissionService = permissionService;
        this.userMapper = userMapper;
        this.revisionMapper = revisionMapper;
        this.groupBadges = groupBadges;
    }

    @ModelAttribute
    public void userProfile(HttpServletRequest request, Model model) {
        if (isNonPageRequest(request) || !this.userProfileHooks.userProfileEnabled()) {
            return;
        }
        String username = WikiNamespaces.userPageName(currentTitle(request));
        if (username == null || username.isEmpty()) {
            return;
        }

        Viewer viewer = this.currentUser.viewer();
        Optional<UserStore.UserRef> account = this.userStore.byName(username);
        long userId = account.map(UserStore.UserRef::id).orElse(0L);
        // 本人判断：优先按用户 ID（账号存在时）；账号不存在时退回用户名比较（与迁移前一致）
        boolean self = viewer.loggedIn()
                && (userId > 0 ? viewer.id() == userId : username.equalsIgnoreCase(viewer.name()));

        String defaultAvatar = this.userStore.defaultAvatarUrl();
        UserProfileContext context = new RequestUserProfileContext(userId, username, self, viewer, defaultAvatar);
        String avatar = this.userProfileHooks.avatarUrl(context);
        model.addAttribute("userPageName", username);
        model.addAttribute("userAvatarUrl",
                avatar == null || avatar.isBlank() ? defaultAvatar : avatar);

        String links = this.userProfileHooks.linksHtml(context);
        if (links != null && !links.isBlank()) {
            model.addAttribute("userProfileLinks", links);
        }
        // 资料卡整体交给插件（SocialProfile 版式）时，模板用它的 HTML 替代内置资料卡
        String profileTop = this.userProfileHooks.profileTopHtml(context);
        if (profileTop != null && !profileTop.isBlank()) {
            model.addAttribute("userProfileTopHtml", profileTop);
        }
        // 「上传头像」入口：只有提供头像上传能力的插件才给（core 自己没有上传页，避免死链接）
        String avatarUpload = this.userProfileHooks.avatarUploadUrl(context);
        if (avatarUpload != null && !avatarUpload.isBlank()) {
            model.addAttribute("userAvatarUploadUrl", avatarUpload);
        }

        // 资料卡右栏的核心数据（用户组 / 注册时间 / 编辑数）：账号不存在时全部留空
        boolean exists = userId > 0 && this.userStore.byId(userId).isPresent();
        model.addAttribute("userExists", exists);
        model.addAttribute("userSelf", self);
        if (exists) {
            Set<String> groups = this.permissionService.namesOf(userId);
            // 资料卡只显示**显式 / 特权组**（隐式组「用户 / 自动确定用户 / 邮箱确认用户」等被过滤掉），
            // 最多 3 个徽章 + 「+N」；一个显式组都没有时模板整段不渲染（见 UserGroupBadges）
            model.addAttribute("userGroupBadges",
                    this.groupBadges.summarize(groups == null ? List.of() : List.copyOf(groups)));
            model.addAttribute("userEditCount", this.revisionMapper.selectCount(
                    Wrappers.<WikiRevision>lambdaQuery().eq(WikiRevision::getUserId, userId)));
            LocalDateTime registeredAt = this.registeredAt(userId);
            if (registeredAt != null) {
                model.addAttribute("userRegisteredAt", registeredAt);
            }
        }

        // 资料卡下方、正文之前的整宽面板（插件贡献：SocialProfile 的「关于 / 统计栏 / 内嵌留言板」）。
        // 未创建的用户页（missing-user.html）也走这里：userId = 0，插件自行决定渲染什么（留言 / 加好友入口）。
        String panel = this.userProfileHooks.panelHtml(context);
        if (panel != null && !panel.isBlank()) {
            model.addAttribute("userProfilePanelHtml", panel);
        }
    }

    /** 注册时间（{@code wiki_user.create_at}）；查不到返回 null（模板不显示那一段）。 */
    private LocalDateTime registeredAt(long userId) {
        try {
            WikiUser user = this.userMapper == null ? null : this.userMapper.selectById(userId);
            return user == null ? null : user.getCreateAt();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** API / 插件静态资源 / 头像文件请求不渲染模板，直接跳过（钩子不必为它们做数据库查询）。 */
    private static boolean isNonPageRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || uri.startsWith("/api/") || uri.startsWith("/api.php")
                || uri.startsWith("/plugins/") || uri.startsWith("/avatars/");
    }

    /** 从 URI 模板变量里取当前页面标题（路由为 {@code /wiki/{title}} 与 {@code /wiki/{title}/{*rest}}）。 */
    private static String currentTitle(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return "";
        }
        Object title = variables.get("title");
        if (title == null) {
            return "";
        }
        String fullTitle = title.toString();
        Object rest = variables.get("rest");
        if (rest != null && !rest.toString().isEmpty()) {
            fullTitle = fullTitle + "/" + rest;
        }
        return fullTitle;
    }
}
