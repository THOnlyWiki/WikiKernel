package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.config.SetupProperties;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 安装向导完成后的示例内容种子：
 * <ul>
 *     <li>示例首页 {@code 首页}（欢迎页 + 语法演示）；</li>
 *     <li>MediaWiki 风格默认侧边栏 {@code WikiKernel:Sidebar}（导航 / 工具）；</li>
 *     <li>编辑帮助 {@code 帮助:编辑}（编辑工具栏「帮助」按钮的落地页）。</li>
 * </ul>
 * 只在「由安装向导创建（配置里有 initial-accounts）」且**同一个数据库尚未种过**时写入；
 * 已存在的页面不覆盖，种过之后（含系统管理员后来删改）不会再次创建。
 * <p>
 * 顺序在初始账号种子（4）之后。
 */
@Component
@Order(5)
public class SetupContentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SetupContentSeeder.class);

    /** 内容种子标记（存 wiki_setting，与数据库绑定，只跑一次）。 */
    public static final String SEEDED_KEY = "setup.content-seeded";

    private static final String MAIN_PAGE = "首页";
    private static final String SIDEBAR_PAGE = "WikiKernel:Sidebar";
    private static final String HELP_PAGE = "帮助:编辑";
    private static final String GADGET_DEFINITION_PAGE = "WikiKernel:Gadgets-definition";

    private static final String SEED_COMMENT = "初始化示例内容";

    private static final String HOME_CONTENT = """
            欢迎来到 WikiKernel！

            这是本站的示例首页，由安装向导自动创建，可以直接点击「编辑」修改。

            == 快速开始 ==

            * [[特殊:全部页面|全部页面]]：浏览所有条目
            * [[特殊:最近更改|最近更改]]：看看最近改了什么
            * [[特殊:上传文件|上传文件]]：上传图片并在页面里使用
            * [[帮助:编辑|编辑帮助]]：常用语法速查

            == 格式速览 ==

            '''粗体'''、''斜体''、[[首页|内部链接]]、[https://www.mediawiki.org 外部链接]，还有脚注<ref>这是一条示例脚注，会显示在页面底部的脚注列表里。</ref>。

            * 无序列表
            * 第二项

            # 有序列表
            # 第二项

            {| class="wikitable"
            |-
            ! 表头 !! 表头
            |-
            | 单元格 || 单元格
            |}

            == 脚注与更多 ==

            <references/>

            本页演示了标题、列表、表格与脚注；模板、微件、图片、分类与目录等语法见 [[帮助:编辑|编辑帮助]]。

            本页面在安装初始化时自动创建，欢迎自由修改。
            """;

    private static final String SIDEBAR_CONTENT = """
            * 导航
            ** 首页
            ** 特殊:随机页面|随机页面
            ** 特殊:全部页面|全部页面
            ** 特殊:最近更改|最近更改
            ** 特殊:最新页面|最新页面
            ** 帮助:编辑|帮助
            * 工具
            说明：$title$ 是当前页面标题的占位（渲染时替换为当前页面）；下面条目在普通页面和特殊页面都会自动生成链接。
            ** 特殊:链入页面?page=$title$|链入页面
            ** 特殊:相关更改?page=$title$|相关更改
            ** 特殊:上传文件|上传文件
            ** 特殊:特殊页面|特殊页面
            ** $title$?printable=yes|可打印版
            ** $title$?oldid=|固定链接
            ** $title$?action=info|页面信息
            """;

    private static final String HELP_CONTENT = """
            == 编辑帮助 ==

            常用语法（编辑器上方的工具栏可以一键插入这些格式）：

            <pre>
            标题        == 二级标题 ==    === 三级标题 ===
            粗体 / 斜体  '''粗体'''    ''斜体''
            链接        [[页面标题|显示文字]]    [https://example.com 外链]
            列表        * 无序    # 有序    : 缩进
            签名        --~~~~
            水平线      ----
            脚注        来源<ref>说明</ref>    ...    <references/>
            图片        [[文件:名称.png|thumb|说明|200px]]
            模板        {{模板名|参数=值}}
            微件        {{#widget:名称|参数=值}}
            表格        {| class="wikitable"  ...  |}
            分类        [[分类:分类名|排序键]]
            目录        标题 ≥4 个时自动显示；__TOC__ 强制显示 / __NOTOC__ 关闭
            </pre>

            编辑页还有「显示预览」按钮，可以在保存前查看渲染效果；编辑中的内容会自动保存为本地草稿，防止手滑丢失。
            """;

    private static final String GADGET_DEFINITION_CONTENT = """
            == Gadgets ==

            Gadget definitions use one line per Gadget:

            <pre>
            * QuickEdit | 快速编辑工具 | Gadget-QuickEdit.js | Gadget-QuickEdit.css | default
            * DarkMode | 深色模式 | Gadget-DarkMode.js | default
            </pre>

            JavaScript and CSS assets are stored as [[Gadget:Gadget-QuickEdit.js]] and
            [[Gadget:Gadget-QuickEdit.css]]. The 「小部件管理员」 group can edit definitions and assets.
            """;

    private final SetupProperties properties;
    private final WikiPageService wikiPageService;
    private final SiteSettingsService siteSettingsService;

    public SetupContentSeeder(SetupProperties properties,
                              WikiPageService wikiPageService,
                              SiteSettingsService siteSettingsService) {
        this.properties = properties;
        this.wikiPageService = wikiPageService;
        this.siteSettingsService = siteSettingsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getInitialAccounts().isEmpty()) {
            return; // 不是安装向导创建的 wiki
        }
        if (siteSettingsService.getBoolean(SEEDED_KEY, false)) {
            return; // 同一个数据库只种一次（之后可自由删改）
        }
        boolean created = createIfMissing(MAIN_PAGE, HOME_CONTENT);
        created |= createIfMissing(SIDEBAR_PAGE, SIDEBAR_CONTENT);
        created |= createIfMissing(HELP_PAGE, HELP_CONTENT);
        created |= createIfMissing(GADGET_DEFINITION_PAGE, GADGET_DEFINITION_CONTENT);
        siteSettingsService.set(SEEDED_KEY, "true");
        if (created) {
            log.info("安装向导：已创建示例首页 / 默认侧边栏 / 编辑帮助");
        }
    }

    /** 页面不存在时创建；返回是否真的创建了。 */
    private boolean createIfMissing(String title, String content) {
        if (wikiPageService.getByTitle(title).isPresent()) {
            return false;
        }
        wikiPageService.editPage(null, title, content, SEED_COMMENT);
        return true;
    }
}
