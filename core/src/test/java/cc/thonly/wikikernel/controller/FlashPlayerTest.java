package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flash 播放器（Ruffle）已迁到插件 {@code flash-player}：core 未加载插件时 {@code <flash-player>}
 * 按原文转义显示；播放器占位与开关行为由插件测试 {@code FlashPlayerTagTest} 覆盖。
 * Ruffle 运行时（{@code /vendor/ruffle/…}）仍随 core 分发，这里验证静态资源可访问。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.run-path=./build/test-files"
})
@AutoConfigureMockMvc
class FlashPlayerTest {

    /** 一个最小的合法 SWF（CWS 压缩，53 字节：Ruffle 测试集里的 SimpleRedBackground）。 */
    private static final byte[] SWF = java.util.Base64.getDecoder().decode(
            "Q1dTBh0AAAB4nAXAMREAIAgAwNcCFnLwzkJEIicLfDhJcZe/m4cBMYcD2w==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FileService fileService;

    @Test
    void flashPlayerTagIsUnknownToCoreWithoutPlugin() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String file = "动画演示" + suffix + ".swf";
        fileService.store(null, file, "", SWF);
        String title = "Flash 演示页" + suffix;
        wikiPageService.editPage(null, title,
                "<flash-player file=\"[[文件:" + file + "]]\" width=\"640\" height=\"480\" />");
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;flash-player")))
                .andExpect(content().string(not(containsString("data-flash-player=\"1\""))))
                .andExpect(content().string(not(containsString("/plugins/flash-player/flash-player.js"))));
    }

    @Test
    void ruffleAssetsAreServed() throws Exception {
        mockMvc.perform(get("/vendor/ruffle/ruffle.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("Ruffle")));
        // wasm：期望 application/wasm（Brotli/Gzip 之外的类型，Ruffle 会检查）
        mockMvc.perform(get("/vendor/ruffle/878d548199cddef07035.wasm"))
                .andExpect(status().isOk());
    }
}
