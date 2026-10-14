package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户页资料卡（{@code .wiki-profile}）的验收断言 —— **core 未加载插件**时的静态 HTML：
 * <ol>
 *     <li>组徽章：只列显式组、≤3 个、不含隐式组（用户 / 自动确定用户 / 邮箱确认用户），
 *     一个显式组都没有时整段不渲染；</li>
 *     <li>资料卡里**不再有社交入口**（私信 / 留言板 / 好友 / 礼物 / 积分排行 / 编辑资料）——
 *     它们只在插件面板（{@code .wiki-profile-panel}）里出现；</li>
 *     <li>资料卡里没有「上传头像」入口（唯一入口是插件面板操作按钮行的
 *     {@code .sp-button-avatar}，所以 core 单测里整页 0 次：插件没加载就没有上传页）。</li>
 * </ol>
 * 插件加载后的 HTML（面板 / 按钮行 / 上传头像按钮）由 {@code plugin/social-profile} 的测试与
 * 真实启动实测覆盖。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class UserProfileCardTest {

    /** 资料卡整块（从 {@code <div class="wiki-profile" } 到它的收尾 clear 元素）。 */
    private static final Pattern PROFILE_BLOCK = Pattern.compile(
            "<div class=\"wiki-profile\"[\\s\\S]*?<div class=\"wiki-profile-clear\"[^>]*></div>");
    /** 资料卡里的组标签 span（**不含**「+N」那个 {@code wiki-profile-group-more}）。 */
    private static final Pattern GROUP_TAG = Pattern.compile(
            "<span class=\"wiki-profile-group\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    // ==================== ① 组徽章：≤3 + 过滤隐式组 ====================

    @Test
    void groupBadgesListOnlyExplicitGroupsAndCapAtThree() throws Exception {
        WikiUser user = insertUser("资料卡组徽章" + System.nanoTime());
        // 显式组 5 个 + 隐式组 3 个（用户 / 自动确定用户 / 邮箱确认用户）
        permissionService.replaceFor(user.getId(), List.of(
                PermissionService.GROUP_USER,
                PermissionService.GROUP_AUTOCONFIRMED,
                PermissionService.GROUP_EMAIL_CONFIRMED,
                PermissionService.GROUP_ADMIN,
                PermissionService.GROUP_BUREAUCRAT,
                PermissionService.GROUP_CHECKUSER,
                PermissionService.GROUP_INTERFACE_ADMIN,
                PermissionService.GROUP_COMMENT_ADMIN));
        assertEquals(5, explicitGroupCount(user.getId()), "3 个隐式组之外应有 5 个显式组");
        openUserPage(user);

        String profile = profileBlock(page("用户:" + user.getUsername(), null));

        List<String> badges = all(profile, GROUP_TAG);
        assertTrue(badges.size() <= 3, "资料卡最多 3 个组标签，实际 " + badges.size() + "：" + profile);
        assertTrue(profile.contains("wiki-profile-group-more"), "第 4 个显式组折成「+N」：" + profile);
        assertTrue(profile.contains("+2"), "5 个显式组 - 3 个标签 = +2：" + profile);
        for (String implicit : List.of("用户", "自动确定用户", "邮箱确认用户")) {
            assertFalse(profile.contains(">" + implicit + "</span>"),
                    "隐式组「" + implicit + "」不应出现在资料卡里：" + profile);
        }
        // 全量组名（含被折进「+N」的）在 data-summary 里
        String summary = dataSummary(profile);
        assertEquals(5, summary.split(", ").length, "全量列表覆盖 5 个显式组：" + summary);
        for (String explicit : List.of("管理员", "行政员", "核查员", "界面管理员", "评论管理员")) {
            assertTrue(summary.contains(explicit), "全量列表里有「" + explicit + "」：" + summary);
        }
    }

    // ==================== ④ 没有显式组 → 整段不渲染 ====================

    @Test
    void groupBadgeSectionIsNotRenderedWithoutExplicitGroups() throws Exception {
        WikiUser user = insertUser("资料卡无显式组" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(
                PermissionService.GROUP_USER,
                PermissionService.GROUP_AUTOCONFIRMED,
                PermissionService.GROUP_EMAIL_CONFIRMED));
        openUserPage(user);

        String profile = profileBlock(page("用户:" + user.getUsername(), null));

        assertTrue(profile.contains("class=\"wiki-profile\"") || !profile.isEmpty(),
                "资料卡本身仍然渲染：" + profile);
        assertFalse(profile.contains("wiki-profile-group"), "没有显式组就不渲染组标签段：" + profile);
        assertFalse(profile.contains("wiki-profile-group-more"), profile);
    }

    // ==================== ②③ 资料卡里没有社交入口 / 上传头像 ====================

    @Test
    void profileCardKeepsCoreLinksOnlyAndNoAvatarUploadEntry() throws Exception {
        WikiUser user = insertUser("资料卡链接" + System.nanoTime());
        WikiUser viewer = insertUser("资料卡链接访客" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_USER,
                PermissionService.GROUP_ADMIN));
        permissionService.replaceFor(viewer.getId(), List.of(PermissionService.GROUP_USER));
        openUserPage(user);

        // 本人视角（「上传头像」过去就是在这里的头像下方重复出现的）
        String selfPage = page("用户:" + user.getUsername(), sessionOf(user));
        String profile = profileBlock(selfPage);
        assertFalse(profile.isEmpty(), "资料卡应渲染：" + selfPage);

        // ② 社交入口不在资料卡里（只在插件面板里；core 未加载插件时整页都没有这些链接）
        for (String social : List.of("特殊:私信", "特殊:留言板", "特殊:好友", "特殊:礼物", "特殊:积分排行", "特殊:编辑资料")) {
            assertFalse(profile.contains(social), "资料卡里不应出现社交入口「" + social + "」：" + profile);
        }
        // ③ 上传头像入口不在资料卡里（唯一入口是插件面板的 .sp-button-avatar）
        assertFalse(profile.contains("特殊:上传头像"), profile);
        assertFalse(profile.contains(">上传头像<"), "资料卡里没有「上传头像」链接文案：" + profile);
        assertFalse(profile.contains("wiki-profile-avatar-link"), "头像下方不再放「上传头像」小链接：" + profile);
        assertFalse(selfPage.contains(">上传头像<"),
                "没有插件时整页没有「上传头像」入口（上传页由插件提供）：" + selfPage);

        // 资料卡保留 core 自己的链接：贡献（本人 / 别人都显示）；「用户页」只给看别人的访客
        assertTrue(profile.contains("特殊:用户贡献"), "本人资料卡也保留「贡献」链接：" + profile);
        assertTrue(profile.contains(">贡献<"), profile);
        // 访客视角：给一个回「用户页」的入口（抓取顺序放在本人之前，排除会话串味）
        String other = profileBlock(page("用户:" + user.getUsername(), sessionOf(viewer)));
        assertTrue(other.contains(">用户页<"), "别人看时给一个回用户页的入口：" + other);
        assertFalse(profile.contains(">用户页<"),
                "本人看自己的资料卡时不重复「用户页」链接；本人视图=" + profile);

        // 社交入口文案在整个资料卡块里也不出现（标题 / title 属性都算）
        assertFalse(profile.contains(">留言板"), profile);
        assertFalse(profile.contains("积分排行"), profile);
    }

    // ==================== 工具 ====================

    private String page(String title, MockHttpSession session) throws Exception {
        var request = get("/wiki/{title}", title);
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 取出资料卡整块 HTML；页面上没有资料卡时返回空串。 */
    private static String profileBlock(String html) {
        Matcher matcher = PROFILE_BLOCK.matcher(html == null ? "" : html);
        return matcher.find() ? matcher.group() : "";
    }

    private static List<String> all(String html, Pattern pattern) {
        Matcher matcher = pattern.matcher(html);
        java.util.List<String> result = new java.util.ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    /** 资料卡里「+N」的 {@code data-summary}（全量显式组展示名，逗号分隔）。 */
    private static String dataSummary(String profile) {
        Matcher matcher = Pattern.compile("data-summary=\"([^\"]*)\"").matcher(profile);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 用户页必须有内容才会按阅读页渲染（资料卡 + 正文）；内容不影响资料卡本身。 */
    private void openUserPage(WikiUser user) {
        wikiPageService.editPage(user, "用户:" + user.getUsername(), "资料卡测试正文");
    }

    /** 该用户的**显式**组数量（隐式组：用户 / 自动确定用户 / 邮箱确认用户，见 core 的 UserGroupBadges）。 */
    private long explicitGroupCount(long userId) {
        return permissionService.namesOf(userId).stream()
                .filter(name -> !List.of(PermissionService.GROUP_USER, PermissionService.GROUP_AUTOCONFIRMED,
                        PermissionService.GROUP_EMAIL_CONFIRMED).contains(name))
                .count();
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
