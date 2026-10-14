package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiCompiledScript;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.db.mapper.WikiCompiledScriptMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.typescript.TypeScriptAssetService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 引用用户脚本页面（{@code mw.wikiImport}）依赖的服务端语义：
 * <ul>
 *     <li>raw 响应带 <b>版本化 ETag</b> + {@code Cache-Control: no-cache}：模块 URL 固定不变，
 *     靠校验保证「改动立即生效」，命中 {@code If-None-Match} 时走 304 且**不触发编译**；</li>
 *     <li>{@code &module=1}（模块加载器）遇到编译失败 / 未启用时返回 <b>500 + 诊断</b>，
 *     而不是像经典脚本那样返回说明性注释（那会静默变成空模块）；</li>
 *     <li>页面保存后 ETag 变化 → 客户端拿到新产物（模块不会被旧缓存钉住）。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WikiModuleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FeatureService featureService;

    @Autowired
    private WikiCompiledScriptMapper compiledScriptMapper;

    @Test
    void rawResponsesCarryVersionedEtagAndNoCache() throws Exception {
        String title = "模块校验页" + System.nanoTime() + ".js";
        wikiPageService.editPage(null, title, "export const value = 1;\n");
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();

        String etag = mockMvc.perform(get("/wiki/{title}", title)
                        .param("action", "raw").param("ctype", "text/javascript"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", containsString(String.valueOf(page.getLatestRevisionId()))))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(content().string(containsString("export const value = 1")))
                .andReturn().getResponse().getHeader("ETag");

        // 带 If-None-Match：304（未改动就不重发内容）
        mockMvc.perform(get("/wiki/{title}", title)
                        .param("action", "raw").param("ctype", "text/javascript")
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        // 保存新版本：ETag 变化 → 内容更新
        wikiPageService.editPage(null, title, "export const value = 2;\n");
        mockMvc.perform(get("/wiki/{title}", title)
                        .param("action", "raw").param("ctype", "text/javascript")
                        .header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("export const value = 2")));

        // 普通源码（不带 ctype）同样带 ETag
        mockMvc.perform(get("/wiki/{title}", title).param("action", "raw"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", not(containsString("null"))))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));
    }

    @Test
    void moduleRequestDoesNotRecompileOnNotModified() throws Exception {
        featureService.setTypescript(true);
        try {
            String title = "模块304页" + System.nanoTime() + ".ts";
            wikiPageService.editPage(null, title, "export const answer: number = 42;\n");
            WikiPage page = wikiPageService.getByTitle(title).orElseThrow();

            String etag = mockMvc.perform(get("/wiki/{title}", title)
                            .param("action", "raw").param("ctype", "text/javascript").param("module", "1"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("export const answer = 42")))
                    .andReturn().getResponse().getHeader("ETag");

            long rowsBefore = countRows(page.getId());
            assertEquals(1, rowsBefore, "首次请求应写入一条编译结果");

            // 304：连编译缓存都不该新增 / 不该重新编译
            mockMvc.perform(get("/wiki/{title}", title)
                            .param("action", "raw").param("ctype", "text/javascript").param("module", "1")
                            .header("If-None-Match", etag))
                    .andExpect(status().isNotModified());
            assertEquals(rowsBefore, countRows(page.getId()), "304 不应触发重新编译");
        } finally {
            featureService.setTypescript(false);
        }
    }

    @Test
    void moduleRequestFailsLoudlyOnCompileError() throws Exception {
        featureService.setTypescript(true);
        try {
            String title = "模块编译失败页" + System.nanoTime() + ".ts";
            wikiPageService.editPage(null, title, "const broken: number = ;\n");

            // 模块加载器：500 + 诊断文本（import() 会 reject，作者能看到原因）
            mockMvc.perform(get("/wiki/{title}", title)
                            .param("action", "raw").param("ctype", "text/javascript").param("module", "1"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string(containsString("TypeScript 编译失败")))
                    .andExpect(content().string(containsString("Expression expected")));

            // 经典脚本：仍是 200 + 说明性注释（不会让页面报语法错误）
            mockMvc.perform(get("/wiki/{title}", title)
                            .param("action", "raw").param("ctype", "text/javascript"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("/* TypeScript 编译失败")));
        } finally {
            featureService.setTypescript(false);
        }

        // 编译未启用时的模块请求：也应当明确失败
        String disabled = "模块未启用页" + System.nanoTime() + ".ts";
        wikiPageService.editPage(null, disabled, "export const x: number = 1;\n");
        mockMvc.perform(get("/wiki/{title}", disabled)
                        .param("action", "raw").param("ctype", "text/javascript").param("module", "1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("TypeScript 编译未启用")));
    }

    @Test
    void frontendHelperIsBundledAndDocumented() throws Exception {
        mockMvc.perform(get("/js/wiki-modules.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mw.wikiImport")))
                .andExpect(content().string(containsString("mw.wikiPreload")))
                .andExpect(content().string(containsString("mw.wikiModule")))
                // 模块 URL 约定：raw + JS ctype + module=1
                .andExpect(content().string(containsString("action=raw&ctype=text/javascript&module=1")));
        // head 里引入了助手（先于业务脚本）
        String title = "模块助手引入页" + System.nanoTime();
        wikiPageService.editPage(null, title, "正文");
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/wiki-modules.js")));
    }

    private long countRows(long pageId) {
        return compiledScriptMapper.selectCount(Wrappers.<WikiCompiledScript>lambdaQuery()
                .eq(WikiCompiledScript::getPageId, pageId));
    }
}
