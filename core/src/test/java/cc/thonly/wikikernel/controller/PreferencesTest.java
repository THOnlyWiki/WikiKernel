package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 特殊:参数设置（仿 MediaWiki Special:Preferences）：
 * 匿名提示登录；页面含全部标签与分区；保存后偏好落库、语言/最近更改/搜索默认值实际生效；重置还原默认。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PreferencesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void anonymousSeesLoginPrompt() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:参数设置"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请先登录后再修改参数设置")));
    }

    @Test
    void pageShowsAllTabsAndSections() throws Exception {
        WikiUser user = userService.createUser("参数设置用户" + System.nanoTime(), "secret123", "");
        mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"preferences-form\"")))
                .andExpect(content().string(containsString("用户资料")))
                .andExpect(content().string(containsString("外观")))
                .andExpect(content().string(containsString("最近更改")))
                .andExpect(content().string(containsString("监视列表")))
                .andExpect(content().string(containsString("搜索")))
                .andExpect(content().string(containsString("基本信息")))
                .andExpect(content().string(containsString("国际化")))
                .andExpect(content().string(containsString("签名")))
                .andExpect(content().string(containsString("电子邮件选项")))
                .andExpect(content().string(containsString(user.getUsername())))
                .andExpect(content().string(containsString("注册时间")))
                .andExpect(content().string(containsString("重置设置")))
                // 用户组是蓝链（链到 特殊:权限列表?group=…）
                .andExpect(content().string(containsString("group=")))
                .andExpect(content().string(not(containsString("class=\"wiki-tag\""))))
                // 外观标签里列出可用皮肤
                .andExpect(content().string(containsString("Vector")));
    }

    @Test
    void savingPreferencesPersistsAndWiresLanguageRecentChangesAndSearch() throws Exception {
        WikiUser user = userService.createUser("参数保存用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);

        mockMvc.perform(post("/api/user/preferences").session(session)
                        .param("realName", "测试真名")
                        .param("gender", "female")
                        .param("language", "en")
                        .param("signature", "~测试签名")
                        .param("signatureWikitext", "1")
                        .param("email", "test@example.com")
                        .param("emailAllowOthers", "1")
                        .param("rcLimit", "100")
                        .param("rcDays", "30")
                        .param("searchLimit", "5"))
                .andExpect(jsonPath("$.code").value(200))
                // 语言偏好即时生效（Spring 懒解析 LocaleContextHolder）：已选 en → 响应文案为英文
                .andExpect(jsonPath("$.message").value("Preferences saved"));

        WikiUser fresh = userService.findById(user.getId()).orElseThrow();
        assertEquals("测试真名", fresh.getRealName());
        assertEquals("female", fresh.getGender());
        assertEquals("en", fresh.getLanguage());
        assertEquals("~测试签名", fresh.getSignature());
        assertEquals(true, fresh.getSignatureWikitext());
        assertEquals("test@example.com", fresh.getEmail());
        assertEquals(100, fresh.getRcLimit());
        assertEquals(30, fresh.getRcDays());
        assertEquals(5, fresh.getSearchLimit());

        // 语言偏好立即生效（会话副本已刷新）：参数设置页变英文
        mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(session))
                .andExpect(content().string(containsString("Basic information")))
                .andExpect(content().string(containsString("Preferences")));

        // 最近更改默认值生效
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").session(session))
                .andExpect(content().string(containsString("100 changes, 30 days")));

        // 搜索条数生效：6 个匹配页面只显示 5 条
        String keyword = "搜索上限" + System.nanoTime();
        for (int i = 0; i < 6; i++) {
            wikiPageService.editPage(user, keyword + "页" + i, keyword + " 内容 " + i);
        }
        MvcResult result = mockMvc.perform(get("/wiki/{title}", "特殊:搜索")
                        .param("q", keyword).session(session))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        int count = html.split("class=\"search-snippet\"", -1).length - 1;
        assertEquals(5, count, "搜索结果条数应受参数设置限制");
    }

    @Test
    void invalidValuesAreRejected() throws Exception {
        WikiUser user = userService.createUser("参数校验用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);

        mockMvc.perform(post("/api/user/preferences").session(session).param("language", "fr"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/user/preferences").session(session).param("gender", "alien"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/user/preferences").session(session).param("email", "not-an-email"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/user/preferences").session(session).param("rcLimit", "999"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/user/preferences").session(session).param("searchLimit", "99"))
                .andExpect(jsonPath("$.code").value(400));
        // 匿名 401
        mockMvc.perform(post("/api/user/preferences"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void resetRestoresDefaults() throws Exception {
        WikiUser user = userService.createUser("参数重置用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);
        mockMvc.perform(post("/api/user/preferences").session(session)
                        .param("gender", "male").param("language", "en").param("country", "CN")
                        .param("rcLimit", "250").param("searchLimit", "10"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/user/preferences/reset").session(session))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("已还原默认设置"));

        WikiUser fresh = userService.findById(user.getId()).orElseThrow();
        assertEquals("", fresh.getGender());
        assertTrue(fresh.getLanguage() == null || fresh.getLanguage().isEmpty());
        assertEquals("", fresh.getCountry());
        assertEquals(0, fresh.getRcLimit());
        assertEquals(0, fresh.getSearchLimit());
        assertTrue(fresh.getSkin() == null || fresh.getSkin().isEmpty());
    }

    @Test
    void topPersonalBarHasTalkPreferencesAndContributions() throws Exception {
        WikiUser user = userService.createUser("顶栏用户" + System.nanoTime(), "secret123", "");
        // Thymeleaf 链接：中文路径段与会话名都做 URL 编码，冒号保留
        String talkLink = URLEncoder.encode("用户讨论", StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(user.getUsername(), StandardCharsets.UTF_8);
        mockMvc.perform(get("/wiki/{title}", "特殊:版本").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(anyOf(
                        containsString(talkLink),
                        containsString("用户讨论:" + user.getUsername()))))
                .andExpect(content().string(containsString(">参数设置</a>")))
                .andExpect(content().string(containsString(">贡献</a>")));
    }

    @Test
    void changesPasswordWithValidation() throws Exception {
        WikiUser user = userService.createUser("改密用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);

        // 页面含「修改密码」表单
        mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("preferences-password-form")))
                .andExpect(content().string(containsString("修改密码")));

        // 旧密码错误
        mockMvc.perform(post("/api/user/preferences/password").session(session)
                        .param("oldPassword", "wrong").param("newPassword", "newpass123")
                        .param("confirmPassword", "newpass123"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("旧密码不正确。"));
        // 两次输入不一致
        mockMvc.perform(post("/api/user/preferences/password").session(session)
                        .param("oldPassword", "secret123").param("newPassword", "newpass123")
                        .param("confirmPassword", "other123"))
                .andExpect(jsonPath("$.code").value(400));
        // 新密码过短
        mockMvc.perform(post("/api/user/preferences/password").session(session)
                        .param("oldPassword", "secret123").param("newPassword", "123")
                        .param("confirmPassword", "123"))
                .andExpect(jsonPath("$.code").value(400));
        // 正确修改：新密码可登录、旧密码失效
        mockMvc.perform(post("/api/user/preferences/password").session(session)
                        .param("oldPassword", "secret123").param("newPassword", "newpass123")
                        .param("confirmPassword", "newpass123"))
                .andExpect(jsonPath("$.code").value(200));
        assertNotNull(userService.login(user.getUsername(), "newpass123"));
        assertNull(userService.login(user.getUsername(), "secret123"));
    }

    /** 国家 / 地区下拉框：249 个 ISO 3166-1 地区 + 「未指定」，插在语言之后、时区之前。 */
    @Test
    void countryDropdownListsAllIsoRegionsInChinese() throws Exception {
        WikiUser user = userService.createUser("国家下拉用户" + System.nanoTime(), "secret123", "");
        String html = mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"country\"")))
                .andExpect(content().string(containsString("国家/地区")))
                // 输入搜索的钩子：data-country-search 的值就是下拉框的 id（见 countrySearchHook…）
                .andExpect(content().string(containsString("data-country-search=\"pref-country\"")))
                .andReturn().getResponse().getContentAsString();
        // 位置：语言下拉 → 国家 / 地区下拉 → 时区下拉
        int language = html.indexOf("id=\"pref-language\"");
        int country = html.indexOf("id=\"pref-country\"");
        int timeZone = html.indexOf("id=\"pref-timezone\"");
        assertTrue(language >= 0 && country > language, "国家 / 地区下拉框应在语言之后");
        assertTrue(timeZone > country, "国家 / 地区下拉框应在时区之前");
        // 搜索钩子挂在下拉框所在的字段上（在 <select> 之前，脚本据此把搜索框插到下拉框上方）
        int hook = html.indexOf("data-country-search=\"pref-country\"");
        assertTrue(hook >= 0 && hook < country, "输入搜索的钩子应在下拉框之前");

        String select = countrySelect(html);
        List<String> values = optionValues(select);
        List<String> names = optionNames(select);
        assertEquals(250, values.size(), "下拉框应有 249 个地区 + 1 个「未指定」");
        assertEquals(Locale.getISOCountries().length + 1, values.size(), "地区数应等于 JDK 的 ISO 3166-1 列表");
        assertEquals("", values.get(0));
        assertEquals("未指定", names.get(0));
        assertTrue(optionTag(select, "").contains("selected"), "未指定应默认选中");

        List<String> codes = values.subList(1, values.size());
        List<String> regionNames = names.subList(1, names.size());
        assertEquals(249, codes.size());
        assertEquals(249, Set.copyOf(codes).size(), "地区代码不应重复");
        assertTrue(Set.of(Locale.getISOCountries()).containsAll(codes), "选项必须都是 ISO 3166-1 代码");
        // 名称：TW / HK / MO 用消息键覆盖，其余 246 个用 JDK 的 CLDR 名称（没有一条退化成代码）
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            String expected = switch (code) {
                case "TW" -> "台湾（中国）";
                case "HK" -> "香港特别行政区";
                case "MO" -> "澳门特别行政区";
                default -> Locale.of("", code).getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
            };
            assertEquals(expected, regionNames.get(i), code + " 的地区名不正确");
        }
        // 按当前语言下的名称排序（中文按拼音）
        assertSortedByCollator(regionNames, Locale.SIMPLIFIED_CHINESE);
    }

    /** {@code ?uselang=en} 下国家 / 地区名与提示文案都是英文。 */
    @Test
    void countryDropdownIsLocalizedInEnglish() throws Exception {
        WikiUser user = userService.createUser("国家英文用户" + System.nanoTime(), "secret123", "");
        String html = mockMvc.perform(get("/wiki/{title}", "特殊:参数设置")
                        .param("uselang", "en").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Country or region")))
                .andReturn().getResponse().getContentAsString();

        String select = countrySelect(html);
        List<String> values = optionValues(select);
        List<String> names = optionNames(select);
        assertEquals(250, values.size());
        assertEquals("Not specified", names.get(0));
        assertEquals("Taiwan (China)", nameOf(values, names, "TW"));
        assertEquals("Hong Kong SAR", nameOf(values, names, "HK"));
        assertEquals("Macao SAR", nameOf(values, names, "MO"));
        // 其余地区用 JDK 的英文名（含 & 的名字带 HTML 转义，已还原）
        List<String> codes = values.subList(1, values.size());
        List<String> regionNames = names.subList(1, names.size());
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            String expected = switch (code) {
                case "TW" -> "Taiwan (China)";
                case "HK" -> "Hong Kong SAR";
                case "MO" -> "Macao SAR";
                default -> Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
            };
            assertEquals(expected, regionNames.get(i), code + " 的英文地区名不正确");
        }
        assertEquals("Antigua & Barbuda", nameOf(values, names, "AG"));
        assertSortedByCollator(regionNames, Locale.ENGLISH);
    }

    /**
     * 国家 / 地区输入搜索是渐进增强：模板只留钩子（{@code data-country-search="pref-country"}），
     * 搜索框与匹配数提示由 {@code /js/preferences.js} 注入，所以无 JS 时页面仍是原生下拉框
     * （不是 {@code input + datalist}），表单照常提交。
     */
    @Test
    void countrySearchIsProgressiveEnhancement() throws Exception {
        WikiUser user = userService.createUser("国家搜索用户" + System.nanoTime(), "secret123", "");
        String html = mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("for=\"pref-country\"")))
                .andExpect(content().string(containsString("data-country-search=\"pref-country\"")))
                // 原生下拉框：不用 input + datalist（那会丢掉地区代码的语义）
                .andExpect(content().string(not(containsString("<datalist"))))
                .andExpect(content().string(not(containsString("list=\"pref-country\""))))
                // 搜索输入与匹配数提示由脚本注入：无 JS 时它们不存在，页面不受影响
                .andExpect(content().string(not(containsString("pref-country-search"))))
                .andReturn().getResponse().getContentAsString();
        // 文案注入 WIKI_MESSAGES（脚本用 MESSAGES.prefCountrySearch* 读取，JS 里不写死语言）；
        // Thymeleaf 的 JS 内联会把 / 转义成 \/、把引号转义成 \"，断言前先还原
        String script = unescapeJs(html);
        assertTrue(script.contains("prefCountrySearch: \"搜索国家/地区\""), "WIKI_MESSAGES 应带中文搜索标签");
        assertTrue(script.contains("输入名称或代码筛选（如 台湾 / Taiwan / TW）"), "WIKI_MESSAGES 应带中文占位提示");
        assertTrue(script.contains("找到 {0} 个地区"), "WIKI_MESSAGES 应带占位符形式的计数文案");
        assertTrue(script.contains("没有匹配的地区"), "WIKI_MESSAGES 应带无匹配提示");

        // 文案随语言切换（同一页面 uselang=en）
        String english = mockMvc.perform(get("/wiki/{title}", "特殊:参数设置")
                        .param("uselang", "en").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String englishScript = unescapeJs(english);
        assertTrue(englishScript.contains("prefCountrySearch: \"Search country or region\""),
                "英文界面应有英文搜索标签");
        assertTrue(englishScript.contains("Type a name or code to filter (e.g. Taiwan / TW)"),
                "英文界面应有英文占位提示");
        assertTrue(englishScript.contains("{0} regions found"), "英文界面应有英文计数文案");
        assertTrue(englishScript.contains("No matching region"), "英文界面应有英文无匹配提示");
    }

    /** 保存国家 / 地区：小写转大写落库、空值清空、非法代码被拒绝且不改动已存值。 */
    @Test
    void savingCountryNormalizesClearsAndRejectsInvalid() throws Exception {
        WikiUser user = userService.createUser("国家保存用户" + System.nanoTime(), "secret123", "");
        MockHttpSession session = sessionOf(user);

        mockMvc.perform(post("/api/user/preferences").session(session).param("country", " tw "))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("TW", userService.findById(user.getId()).orElseThrow().getCountry());

        // 非法代码：拒绝并返回本地化错误（不静默丢弃，也不改动已保存的值）
        mockMvc.perform(post("/api/user/preferences").session(session).param("country", "ZZ"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("无效的国家或地区代码。"));
        assertEquals("TW", userService.findById(user.getId()).orElseThrow().getCountry());
        // 英文界面下的错误提示
        mockMvc.perform(post("/api/user/preferences").session(session)
                        .param("country", "中国").param("uselang", "en"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid country or region code."));
        assertEquals("TW", userService.findById(user.getId()).orElseThrow().getCountry());

        // 保存后页面上该项处于选中状态
        String html = mockMvc.perform(get("/wiki/{title}", "特殊:参数设置").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(optionTag(countrySelect(html), "TW").contains("selected"), "已保存的地区应选中");

        // 空值 = 未指定（合法）
        mockMvc.perform(post("/api/user/preferences").session(session).param("country", ""))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("", userService.findById(user.getId()).orElseThrow().getCountry());
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    /** 页面里国家 / 地区下拉框的 HTML（{@code id="pref-country"} 到对应的 {@code </select>}）。 */
    private static String countrySelect(String html) {
        int start = html.indexOf("id=\"pref-country\"");
        assertTrue(start >= 0, "参数设置页应包含国家 / 地区下拉框");
        int end = html.indexOf("</select>", start);
        assertTrue(end > start, "国家 / 地区下拉框应渲染成 <select>");
        return html.substring(start, end);
    }

    /** 下拉框选项的 value（按渲染顺序，第 0 项是「未指定」的空值）。 */
    private static List<String> optionValues(String select) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("<option[^>]*value=\"([^\"]*)\"").matcher(select);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    /** 下拉框选项的显示名（按渲染顺序，HTML 转义已还原）。 */
    private static List<String> optionNames(String select) {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("<option[^>]*>([^<]*)</option>").matcher(select);
        while (matcher.find()) {
            names.add(unescapeHtml(matcher.group(1)));
        }
        return names;
    }

    /** 下拉框里某个 value 对应的完整开始标签（用于判断是否选中）。 */
    private static String optionTag(String select, String value) {
        Matcher matcher = Pattern.compile("<option[^>]*value=\"" + Pattern.quote(value) + "\"[^>]*>")
                .matcher(select);
        assertTrue(matcher.find(), "下拉框应包含 value=" + value + " 的选项");
        return matcher.group();
    }

    /** 指定地区代码的显示名（values 与 names 一一对应）。 */
    private static String nameOf(List<String> values, List<String> names, String code) {
        int index = values.indexOf(code);
        assertTrue(index >= 0, "下拉框应包含地区 " + code);
        return names.get(index);
    }

    /** 名称按指定语言排序（中文按拼音、英文按字母；同名的按代码排序由服务端保证）。 */
    private static void assertSortedByCollator(List<String> names, Locale locale) {
        Collator collator = Collator.getInstance(locale);
        for (int i = 1; i < names.size(); i++) {
            assertTrue(collator.compare(names.get(i - 1), names.get(i)) <= 0,
                    "地区名未按 " + locale + " 排序：" + names.get(i - 1) + " 在 " + names.get(i) + " 之前");
        }
    }

    /** 还原 Thymeleaf 输出的 HTML 转义（地区名里有 & 等字符，如 Antigua &amp; Barbuda）。 */
    private static String unescapeHtml(String text) {
        return text.replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /**
     * 还原 Thymeleaf 在 {@code <script th:inline="javascript">} 里做的 JS 字符串转义
     * （{@code /} → {@code \/}、{@code "} → {@code \"}、{@code <} → {@code \u003C} 等），
     * 这样断言的是脚本运行时的文案，而不是被转义后的字面量。
     */
    private static String unescapeJs(String text) {
        return text.replace("\\/", "/").replace("\\\"", "\"")
                .replace("\\u003C", "<").replace("\\u003E", ">").replace("\\u0026", "&");
    }
}
