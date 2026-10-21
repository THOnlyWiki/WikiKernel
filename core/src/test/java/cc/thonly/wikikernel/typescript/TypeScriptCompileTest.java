package cc.thonly.wikikernel.typescript;

import cc.thonly.wikikernel.common.data.WikiCompiledScript;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.TypeScriptProperties;
import cc.thonly.wikikernel.db.mapper.WikiCompiledScriptMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.service.impl.PageIndexGate;
import cc.thonly.wikikernel.user.UserSession;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TypeScript 服务端编译（自包含 GraalJS + 官方 tsc）：
 * <ul>
 *     <li>编译器能力：类型注解 / 泛型 / {@code enum} / 装饰器 / TSX 都能转译，语法错误给行号诊断；</li>
 *     <li>{@code ?action=raw&ctype=text/javascript}（脚本加载路径）对 {@code .ts} 页面返回**编译产物**，
 *     且按版本缓存（同一版本只编译一次）、保存新版本后自然失效；</li>
 *     <li>未开启编译时返回说明性注释（不会让浏览器报语法错误），源码仍可用普通 raw 取到；</li>
 *     <li>「保存时编译」开启后，保存即在页面顶部显示诊断（不阻止保存）。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class TypeScriptCompileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TypeScriptCompiler compiler;

    @Autowired
    private TypeScriptAssetService assetService;

    @Autowired
    private WikiCompiledScriptMapper compiledScriptMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private PageIndexGate indexGate;


    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void compilerTranspilesTypeScriptFeatures() {
        assertTrue(compiler.available(), "jar 里应内置 typescript.js");

        TypeScriptCompiler.Result result = compiler.compile("""
                enum Kind { A = 'a', B = 'b' }
                interface Point { x: number; y: number }
                type Pair<T> = [T, T];
                const point: Point = { x: 1, y: 2 } as Point;
                const pair: Pair<number> = [1, 2];
                function pick<T>(items: T[]): T | undefined { return items[0]; }
                export class Box {
                  constructor(private readonly value: string) {}
                  get(): string { return this.value; }
                }
                export const kind: Kind = Kind.A;
                export const sum = pair.reduce((a, b) => a + b, 0);
                """, "demo.ts");

        assertTrue(result.ok(), "应当编译成功：" + result.diagnosticsText());
        String code = result.code();
        // 类型注解 / 泛型 / as 断言被剥离，enum 转成 IIFE，参数属性落到构造函数里
        assertFalse(code.contains("interface Point"), code);
        assertFalse(code.contains(": number"), code);
        assertTrue(code.contains("var Kind;"), code);
        assertTrue(code.contains("(function (Kind)"), code);
        assertTrue(code.contains("this.value = value"), code);
        assertTrue(code.contains("function pick(items)"), code);
        // 目标语言级别：ES2020 保留 const / 箭头函数（不做降级）
        assertTrue(code.contains("const point = { x: 1, y: 2 }"), code);
        assertFalse(code.contains("var point ="), code);
    }

    @Test
    void compilerReportsSyntaxDiagnosticsWithLineNumbers() {
        TypeScriptCompiler.Result broken = compiler.compile("const a = 1;\nconst b: number = ;\n", "broken.ts");
        assertFalse(broken.ok());
        assertEquals(1, broken.diagnostics().size(), broken.diagnosticsText());
        TypeScriptCompiler.Diagnostic diagnostic = broken.diagnostics().get(0);
        assertEquals(2, diagnostic.line(), broken.diagnosticsText());
        assertTrue(diagnostic.message().toLowerCase().contains("expression expected"),
                broken.diagnosticsText());
    }

    @Test
    void compilerSupportsTsx() {
        TypeScriptCompiler.Result result = compiler.compile(
                "export const App = () => <div className=\"a\">hi</div>;", "app.tsx");
        assertTrue(result.ok(), result.diagnosticsText());
        assertTrue(result.code().contains("React.createElement"), result.code());
    }

    @Test
    void rawEndpointServesCompiledJavaScriptAndCaches() throws Exception {
        featureService.setTypescript(true);
        try {
            String title = "编译测试" + System.nanoTime() + ".ts";
            wikiPageService.editPage(null, title, "export const answer: number = 1 + 1;\n");
            WikiPage page = wikiPageService.getByTitle(title).orElseThrow();

            mockMvc.perform(get("/wiki/{title}", title).param("action", "raw").param("ctype", "text/javascript"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("javascript")))
                    .andExpect(header().string("X-Source-Revision", String.valueOf(page.getLatestRevisionId())))
                    .andExpect(content().string(containsString("export const answer = 1 + 1")))
                    .andExpect(content().string(not(containsString(": number"))));

            // 同一版本只编译一次（缓存命中）
            List<WikiCompiledScript> rows = compiledScriptMapper.selectList(
                    Wrappers.<WikiCompiledScript>lambdaQuery().eq(WikiCompiledScript::getPageId, page.getId()));
            assertEquals(1, rows.size(), rows.toString());
            long firstId = rows.get(0).getId();
            mockMvc.perform(get("/wiki/{title}", title).param("action", "raw").param("ctype", "text/javascript"))
                    .andExpect(status().isOk());
            List<WikiCompiledScript> again = compiledScriptMapper.selectList(
                    Wrappers.<WikiCompiledScript>lambdaQuery().eq(WikiCompiledScript::getPageId, page.getId()));
            assertEquals(1, again.size(), "缓存命中时不应新增行");
            assertEquals(firstId, again.get(0).getId());

            // 保存新版本 → 缓存失效并重新编译
            wikiPageService.editPage(null, title, "export const answer: number = 2 + 2;\n");
            mockMvc.perform(get("/wiki/{title}", title).param("action", "raw").param("ctype", "text/javascript"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("2 + 2")));
            List<WikiCompiledScript> afterEdit = compiledScriptMapper.selectList(
                    Wrappers.<WikiCompiledScript>lambdaQuery().eq(WikiCompiledScript::getPageId, page.getId()));
            assertEquals(1, afterEdit.size(), "每个页面只保留最新一条编译结果");

            // 普通 raw（不带 JS ctype）仍返回 TypeScript 源码，便于编辑与审计
            mockMvc.perform(get("/wiki/{title}", title).param("action", "raw"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("text/plain")))
                    .andExpect(content().string(containsString(": number")));
        } finally {
            featureService.setTypescript(false);
        }
    }

    @Test
    void compileFailureIsServedAsCommentAndDisabledFeatureExplains() throws Exception {
        featureService.setTypescript(true);
        try {
            String brokenTitle = "编译失败测试" + System.nanoTime() + ".ts";
            wikiPageService.editPage(null, brokenTitle, "const broken: number = ;\n");
            mockMvc.perform(get("/wiki/{title}", brokenTitle)
                            .param("action", "raw").param("ctype", "text/javascript"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("TypeScript 编译失败")))
                    .andExpect(content().string(containsString("Expression expected")))
                    // 说明性注释不会让浏览器报语法错误（整段都是注释）
                    .andExpect(content().string(not(containsString("const broken:"))));
        } finally {
            featureService.setTypescript(false);
        }

        // 未开启编译：返回「未启用」注释，而不是直接把 TS 源码当 JS 发出去
        String title = "未启用编译测试" + System.nanoTime() + ".ts";
        wikiPageService.editPage(null, title, "const x: number = 1;\n");
        mockMvc.perform(get("/wiki/{title}", title).param("action", "raw").param("ctype", "text/javascript"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TypeScript 编译未启用")))
                .andExpect(content().string(not(containsString("const x: number = 1;"))));
    }

    @Test
    void compileOnSaveShowsDiagnosticsOnPage() throws Exception {
        featureService.setTypescript(true);
        featureService.setTypescriptCompileOnSave(true);
        try {
            WikiUser user = insertUser("TS 保存用户" + System.nanoTime());
            // .ts 是可能被加载为脚本的界面页面：默认只有界面管理员能保存。
            permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_INTERFACE_ADMIN));
            MockHttpSession session = sessionOf(user);

            // 语法错误：保存后页面顶部直接显示诊断，且保存本身没被阻止（版本照常产生）
            String title = "保存时编译测试" + System.nanoTime() + ".ts";
            mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                            .param("content", "const bad: number = ;\n"))
                    .andExpect(status().is3xxRedirection());
            awaitIndex();
            mockMvc.perform(get("/wiki/{title}", title).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("TypeScript 编译失败")))
                    .andExpect(content().string(containsString("Expression expected")))
                    .andExpect(content().string(containsString("data-language=\"typescript\"")));

            // 修正后：显示「已编译（typescript/x.y.z）」
            mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(session)
                            .param("content", "export const ok: number = 1;\n"))
                    .andExpect(status().is3xxRedirection());
            awaitIndex();
            mockMvc.perform(get("/wiki/{title}", title).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("已编译")))
                    .andExpect(content().string(not(containsString("TypeScript 编译失败"))));

            // 关闭开关后：既没有诊断也没有状态提示（回到纯代码页）
            featureService.setTypescript(false);
            mockMvc.perform(get("/wiki/{title}", title).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("已编译"))))
                    .andExpect(content().string(not(containsString("TypeScript 编译失败"))));

            // 监听挂在「页面变更事件」上：服务层的任何保存路径（网页表单 / action=edit / 导入 / AI 代理）
            // 都会自动编译，不需要各入口单独接入
            featureService.setTypescript(true);
            String eventTitle = "事件保存测试" + System.nanoTime() + ".ts";
            wikiPageService.editPage(null, eventTitle, "export const viaService: string = 'x';\n");
            awaitIndex();
            long revisionId = wikiPageService.getByTitle(eventTitle).orElseThrow().getLatestRevisionId();
            assertTrue(assetService.cached(revisionId, TypeScriptAssetService.TARGET_JAVASCRIPT).isPresent(),
                    "服务层保存也应自动编译（页面变更事件）");
        } finally {
            featureService.setTypescript(false);
            featureService.setTypescriptCompileOnSave(false);
        }
    }

    @Test
    void siteSettingsExposeCompilerSwitches() throws Exception {
        WikiUser admin = insertUser("TS 设置管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        MockHttpSession session = sessionOf(admin);

        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"typescript\"")))
                .andExpect(content().string(containsString("name=\"typescriptCompileOnSave\"")))
                .andExpect(content().string(containsString("name=\"typescriptMaxSourceKilobytes\"")))
                .andExpect(content().string(containsString("TypeScript 服务端编译")))
                // 上限输入框带当前生效值（默认 256）
                .andExpect(content().string(containsString("value=\"256\"")));

        // 开关的读写走 FeatureService（不 POST 站点设置表单：那会把其它开关一并重置成未勾选）
        featureService.setTypescript(true);
        featureService.setTypescriptCompileOnSave(true);
        assertTrue(featureService.typescriptEnabled());
        assertTrue(featureService.typescriptCompileOnSave());
        String html = mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.matches("(?s).*<input[^>]*name=\"typescript\"[^>]*checked[^>]*>.*"),
                "TypeScript 开关应显示为已勾选");
        assertTrue(html.matches("(?s).*<input[^>]*name=\"typescriptCompileOnSave\"[^>]*checked[^>]*>.*"),
                "保存时编译开关应显示为已勾选");

        featureService.setTypescript(false);
        featureService.setTypescriptCompileOnSave(false);
    }

    @Test
    void assetServiceSkipsCompilationWhenDisabled() {
        featureService.setTypescript(false);
        assertFalse(assetService.enabled(), "未开启时不应认为编译可用");
        assertTrue(TypeScriptAssetService.isTypeScriptPage("演示.ts"));
        assertTrue(TypeScriptAssetService.isTypeScriptPage("演示.TSX"));
        assertFalse(TypeScriptAssetService.isTypeScriptPage("演示.js"));
        // 声明文件（.d.ts——注意它也以 .ts 结尾）只有类型，没有运行时代码
        assertTrue(TypeScriptAssetService.isDeclarationPage("global.d.ts"));
        assertFalse(TypeScriptAssetService.isTypeScriptPage("global.d.ts"));
        assertFalse(TypeScriptAssetService.isTypeScriptPage("types.d.tsx"));
        assertFalse(TypeScriptAssetService.isTypeScriptPage("types.d.mts"));
        assertFalse(TypeScriptAssetService.isTypeScriptPage("types.d.cts"));
    }

    @Test
    void declarationPagesAreNotScripts() throws Exception {
        String host = "TS声明宿主页" + System.nanoTime();
        wikiPageService.editPage(null, host, "正文");
        String declaration = host + "/类型.d.ts";
        wikiPageService.editPage(null, declaration, "declare const answer: number;\n");

        featureService.setTypescript(true);
        siteSettingsService.set("features.gadgets", Boolean.toString(true));
        try {
            // 不作为页面脚本自动加载
            assertFalse(wikiPageService.getJS(host).contains(declaration), wikiPageService.getJS(host).toString());
            // 请求 JS 时给明确说明，而不是把声明文件当脚本发出去
            mockMvc.perform(get("/wiki/{title}", declaration)
                            .param("action", "raw").param("ctype", "text/javascript"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("类型声明文件")))
                    .andExpect(content().string(not(containsString("declare const answer"))));
            // 普通 raw 仍是源码（可以做类型提示 / 审计）
            mockMvc.perform(get("/wiki/{title}", declaration).param("action", "raw"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("declare const answer")));
            // 仍按代码页高亮（TypeScript 语法）
            mockMvc.perform(get("/wiki/{title}", declaration))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("data-language=\"typescript\"")));
        } finally {
            featureService.setTypescript(false);
        }
    }

    @Test
    void pageScriptsAndGadgetsAcceptTypeScriptWhenEnabled() throws Exception {
        String host = "TS脚本宿主页" + System.nanoTime();
        wikiPageService.editPage(null, host, "正文");
        String scriptTitle = host + "/脚本.ts";
        wikiPageService.editPage(null, scriptTitle, "console.log('页内 TS 脚本');");

        // 未开启编译：.ts 子页面不会被当成页面脚本（否则浏览器拿到无法执行的源码）
        assertFalse(wikiPageService.getJS(host).contains(scriptTitle), wikiPageService.getJS(host).toString());

        featureService.setTypescript(true);
        try {
            assertTrue(wikiPageService.getJS(host).contains(scriptTitle),
                    "开启编译后 {title}/XXX.ts 应作为页面脚本：" + wikiPageService.getJS(host));

            // Gadgets 已迁到插件 gadget：这里只验证 core 的能力——「小部件:」命名空间下的 .ts 资源页
            // 经 ?action=raw&ctype=text/javascript 返回**编译后的 JavaScript**（插件用它当 Gadget 脚本）
            wikiPageService.editPage(null, "小部件:TsDemo.ts", "export const x: number = 1;\n");
            mockMvc.perform(get("/wiki/{title}", "小部件:TsDemo.ts")
                            .param("action", "raw").param("ctype", "text/javascript"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("export const x")));
        } finally {
            featureService.setTypescript(false);
        }
        // 关闭编译后，同一个地址返回的是说明用注释（不是 TS 源码），浏览器不会报语法错误
        mockMvc.perform(get("/wiki/{title}", "小部件:TsDemo.ts")
                        .param("action", "raw").param("ctype", "text/javascript"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("export const x"))));
    }

    @Test
    void oversizedSourceIsRejectedWithoutCachingAndLimitIsDynamic() {
        WikiPage page = new WikiPage(
                null, "超大源码测试.ts", 1000L, false, false, false,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        page.setId(999_999L);
        page.setLatestRevisionId(999_999L);
        String original = siteSettingsService.get(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "");
        try {
            // 上限 1 KB：超限只给诊断，且**不写缓存**
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "1");
            WikiCompiledScript rejected = assetService.compile(page, "const a = 1;\n".repeat(400),
                    TypeScriptAssetService.TARGET_JAVASCRIPT);
            assertFalse(Boolean.TRUE.equals(rejected.getSuccessful()));
            assertTrue(rejected.getDiagnostics().contains("源码过大"), rejected.getDiagnostics());
            assertTrue(assetService.cached(page.getLatestRevisionId(),
                    TypeScriptAssetService.TARGET_JAVASCRIPT).isEmpty(), "超限拒绝不应写缓存");

            // 改成 -1（不限制）：同一份源码立刻可以编译（无需重启、无需重新保存）
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "-1");
            assertEquals(-1, assetService.maxSourceKilobytes());
            WikiCompiledScript compiled = assetService.compile(page, "export const ok: number = 1;\n",
                    TypeScriptAssetService.TARGET_JAVASCRIPT);
            assertTrue(Boolean.TRUE.equals(compiled.getSuccessful()), compiled.getDiagnostics());
        } finally {
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, original);
        }
    }

    @Test
    void pageViewShowsSourceSizeWarning() throws Exception {
        featureService.setTypescript(true);
        String original = siteSettingsService.get(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "");
        try {
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "1");
            String title = "上限提示页" + System.nanoTime() + ".ts";
            wikiPageService.editPage(null, title, "export const big: string = '" + "x".repeat(3000) + "';\n");
            // 超限不写缓存，但页面顶部也要如实提示（实时判断，不依赖缓存）
            mockMvc.perform(get("/wiki/{title}", title))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("TypeScript 编译失败")))
                    .andExpect(content().string(containsString("源码过大")))
                    .andExpect(content().string(containsString("上限 1 KB")));
        } finally {
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, original);
            featureService.setTypescript(false);
        }
    }

    @Test
    void maxSourceLimitIsNormalizedAndDynamic() {        // 解析规则：数字原样；-1 / 0 = 不限制；空 / 非法 = 回退到 YAML 默认值
        assertEquals(512, TypeScriptAssetService.normalizeMaxSourceKilobytes("512", 256));
        assertEquals(-1, TypeScriptAssetService.normalizeMaxSourceKilobytes("-1", 256));
        assertEquals(0, TypeScriptAssetService.normalizeMaxSourceKilobytes("0", 256));
        assertEquals(-1, TypeScriptAssetService.normalizeMaxSourceKilobytes("-99", 256));
        assertEquals(1_000_000, TypeScriptAssetService.normalizeMaxSourceKilobytes("99999999", 256));
        assertEquals(256, TypeScriptAssetService.normalizeMaxSourceKilobytes("", 256));
        assertEquals(256, TypeScriptAssetService.normalizeMaxSourceKilobytes("abc", 256));

        // 动态生效：站点设置改完立刻影响 maxSourceKilobytes()
        String original = siteSettingsService.get(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "");
        try {
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "1024");
            assertEquals(1024, assetService.maxSourceKilobytes());
            assertEquals("1024 KB", assetService.maxSourceKilobytesText());
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, "-1");
            assertEquals(-1, assetService.maxSourceKilobytes());
            assertEquals("不限制", assetService.maxSourceKilobytesText());
        } finally {
            siteSettingsService.set(TypeScriptAssetService.MAX_SOURCE_KB_KEY, original);
        }
    }

    @Test
    void sandboxBlocksHostAccessAndFileIo() {
        // 与 GraalTypeScriptCompiler 里同一份沙箱配置（单点定义，避免测试与实现漂移）
        try (org.graalvm.polyglot.Context context = GraalTypeScriptCompiler.sandboxedBuilder().build()) {
            // 宿主类访问被拒绝（HostAccess.NONE + 关闭类查找）
            String hostAccess = evalQuietly(context, "Java.type('java.lang.System').getProperty('java.version')");
            assertTrue(hostAccess.contains("not allowed"), hostAccess);
            // 文件 IO 被拒绝，并且 load 函数本身已被关掉（IOAccess.NONE + js.load=false）
            String fileIo = evalQuietly(context, "typeof load === 'undefined' ? 'load disabled' : load('pom.xml')");
            assertTrue(fileIo.contains("disabled") || fileIo.contains("not allowed"), fileIo);
            // 没有 java 包全局、没有多语言 API、没有 console
            assertEquals("undefined", context.eval("js", "typeof java").toString());
            assertEquals("undefined", context.eval("js", "typeof Polyglot").toString());
            assertEquals("undefined", context.eval("js", "typeof console").toString());
            // 但正常 JS 照常可用（转译器本身依赖它）
            assertEquals("3", context.eval("js", "1 + 2").toString());
        }
    }

    @Test
    void concurrentCompilesOfSameRevisionShareOneRun() throws Exception {
        featureService.setTypescript(true);
        try {
            String title = "并发编译测试" + System.nanoTime() + ".ts";
            wikiPageService.editPage(null, title, "export const n: number = 1;\n");

            // 统计真实编译次数：用一个计数包装器构造独立的 TypeScriptAssetService
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            TypeScriptCompiler counting = new TypeScriptCompiler() {
                @Override
                public boolean available() {
                    return compiler.available();
                }

                @Override
                public String version() {
                    return compiler.version();
                }

                @Override
                public Result compile(String source, String fileName) {
                    calls.incrementAndGet();
                    try {
                        // 放大竞争窗口：真实编译前先睡一会，确保其它线程都进入等待
                        Thread.sleep(120);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return compiler.compile(source, fileName);
                }
            };
            TypeScriptAssetService service = new TypeScriptAssetService(
                    counting, compiledScriptMapper, featureService,
                    new TypeScriptProperties(), siteSettingsService);
            WikiPage page = wikiPageService.getByTitle(title).orElseThrow();

            int threads = 8;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                List<java.util.concurrent.Future<WikiCompiledScript>> futures = new java.util.ArrayList<>();
                for (int index = 0; index < threads; index++) {
                    futures.add(pool.submit(() -> service.compile(page, "export const n: number = 1;\n",
                            TypeScriptAssetService.TARGET_JAVASCRIPT)));
                }
                for (java.util.concurrent.Future<WikiCompiledScript> future : futures) {
                    WikiCompiledScript row = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(Boolean.TRUE.equals(row.getSuccessful()), row.getDiagnostics());
                    assertTrue(row.getCode().contains("export const n = 1"), row.getCode());
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, calls.get(), "同版本的并发请求应共享一次编译");
        } finally {
            featureService.setTypescript(false);
        }
    }

    // ==================== 工具 ====================

    /** 保存后的派生索引（含 TypeScript 编译）已全部在后台完成。 */
    private void awaitIndex() {
        indexGate.awaitIdle();
        indexGate.awaitBackgroundIdle();
    }

    private static String evalQuietly(org.graalvm.polyglot.Context context, String script) {
        try {
            return String.valueOf(context.eval("js", script));
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage()).replace('\n', ' ');
            return exception.getClass().getSimpleName() + ": "
                    + message.substring(0, Math.min(160, message.length()));
        }
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
