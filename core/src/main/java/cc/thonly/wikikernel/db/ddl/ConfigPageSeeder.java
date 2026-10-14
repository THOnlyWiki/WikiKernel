package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.ConfigPages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WikiKernel 命名空间的配置页种子：{@code WikiKernel:Titleblacklist} / {@code WikiKernel:Titlewhitelist} /
 * {@code WikiKernel:Email-whitelist} / {@code WikiKernel:Email-blacklist} 不存在时在启动时创建（**已有内容不覆盖**）。
 * <p>
 * 默认内容全部是注释（没有生效规则），方便管理员照格式填写；
 * 解释见 {@code TitleBlacklistService} / {@code EmailWhitelistService} / {@code EmailBlacklistService}。
 */
@Component
@Order(7)
public class ConfigPageSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigPageSeeder.class);

    /** {@code WikiKernel:Titleblacklist} 默认内容（全部注释；去掉示例行首的 # 即可启用）。 */
    static final String TITLE_BLACKLIST_CONTENT = """
            # 本页面为「禁用标题名单」（仿 MediaWiki:Titleblacklist）。
            # 任何匹配名单中正则表达式的页面标题都会被禁止创建 / 移动；带 <noedit> 的规则也会禁止编辑已存在的页面；
            # 注册用户名也会与名单比对（<moveonly> 规则不影响注册）。
            # 本页默认忽略大小写；管理员 / 行政员不受本名单限制（可用 WikiKernel:Titlewhitelist 设置例外）。
            #
            # 格式：每行一条规则；行内 # 之后为注释；行尾可用尖括号写参数（多个参数用 | 分隔）：
            #     正则表达式 [<参数|参数>]
            #
            # 参数：
            #     noedit          也禁止编辑已存在的页面
            #     moveonly        只禁止移动（不影响创建与注册）
            #     newaccountonly  只对注册用户名生效（不影响页面标题）
            #     casesensitive   区分大小写（默认不区分）
            #     autoconfirmed   自动确定用户不受该规则限制
            #     errmsg=消息键   用自定义消息作为错误提示
            #
            # 正则表达式对整个标题做完全匹配（想「包含匹配」请写 .*关键词.*，例如 .*测试标题.*）；
            # 正则里的下划线会被当作空格。
            #
            # 示例（去掉行首的 # 即可启用）：
            # .*测试标题.*
            # 完全禁止的例子 <noedit|autoconfirmed>
            """;

    /** {@code WikiKernel:Titlewhitelist} 默认内容。 */
    static final String TITLE_WHITELIST_CONTENT = """
            # 本页面为「标题白名单」（仿 MediaWiki:Titlewhitelist）：命中本名单的标题不受 WikiKernel:Titleblacklist 限制。
            # 语法与「禁用标题名单」相同（每行一条正则；行尾可写 <参数>；# 之后为注释）。
            #
            # 示例（去掉行首的 # 即可启用）：
            # .*测试标题.*
            """;

    /** {@code WikiKernel:Email-whitelist} 默认内容（全部注释 = 白名单未启用）。 */
    static final String EMAIL_WHITELIST_CONTENT = """
            # 本页面为「编辑邮箱白名单」（仿 MediaWiki:Email-whitelist）：
            # 只要本页面存在至少一条规则，就只有电子邮箱与规则匹配的用户才能保存页面；
            # 规则按「包含」匹配（不区分大小写），每行一条；以 # 开头的行是注释。
            # 管理员 / 行政员不受限制；页面里没有规则时，任何人都能保存。
            #
            # 示例（去掉行首的 # 即可启用）：
            # gmail\\.com
            # .*@example\\.com
            """;

    /** {@code WikiKernel:Email-blacklist} 默认内容（全部注释 = 黑名单未启用）。 */
    static final String EMAIL_BLACKLIST_CONTENT = """
            # 本页面为「编辑邮箱黑名单」（仿 MediaWiki:Email-blacklist）：
            # 电子邮箱命中任一规则的用户不能保存页面（规则按「包含」匹配，不区分大小写，
            # 每行一条；以 # 开头的行是注释）。
            # 与「编辑邮箱白名单」同时启用时：先要求命中白名单，再要求不命中黑名单。
            # 管理员 / 行政员不受限制；页面里没有规则时，黑名单不生效。
            #
            # 示例（去掉行首的 # 即可启用）：
            # example\\.com
            # .*@spam\\.net
            """;

    private final WikiPageService wikiPageService;

    public ConfigPageSeeder(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(ConfigPages.TITLE_BLACKLIST, TITLE_BLACKLIST_CONTENT);
        pages.put(ConfigPages.TITLE_WHITELIST, TITLE_WHITELIST_CONTENT);
        pages.put(ConfigPages.EMAIL_WHITELIST, EMAIL_WHITELIST_CONTENT);
        pages.put(ConfigPages.EMAIL_BLACKLIST, EMAIL_BLACKLIST_CONTENT);
        for (Map.Entry<String, String> entry : pages.entrySet()) {
            if (wikiPageService.getByTitle(entry.getKey()).isPresent()) {
                continue;
            }
            wikiPageService.editPage(null, entry.getKey(), entry.getValue(), "初始化默认配置页");
            log.info("已创建默认配置页 {}", entry.getKey());
        }
    }
}
