package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 编辑页草稿保护（edit-draft.js）：
 * 编辑页带草稿提示容器与页面标题（localStorage 键 {@code tempedit-cache-<标题>}），
 * 脚本包含自动保存 / beforeunload 关闭确认逻辑。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class EditDraftTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void editPageShipsDraftGuard() throws Exception {
        WikiUser user = insertUser("草稿测试用户" + System.nanoTime());
        String title = "草稿测试页" + System.nanoTime();
        wikiPageService.editPage(null, title, "正文");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());

        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/edit-draft.js")))
                .andExpect(content().string(containsString("id=\"edit-draft-notice\"")))
                .andExpect(content().string(containsString("id=\"edit-draft-discard\"")))
                .andExpect(content().string(containsString("id=\"edit-draft-view-latest\"")))
                .andExpect(content().string(containsString("data-title=\"" + title + "\"")))
                // 表单带当前修订号，用于冲突检测
                .andExpect(content().string(containsString("data-revision=")))
                // WIKI_MESSAGES 里有恢复 / 自动保存 / 冲突提示文案
                .andExpect(content().string(containsString("已恢复上次未保存的草稿")))
                .andExpect(content().string(containsString("草稿已自动保存到本地")))
                .andExpect(content().string(containsString("被修改过")));

        // 脚本本体：localStorage 键前缀 / 关闭确认 / 自动保存 / 修订号冲突检测
        mockMvc.perform(get("/js/edit-draft.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tempedit-cache-")))
                .andExpect(content().string(containsString("beforeunload")))
                .andExpect(content().string(containsString("data-revision")))
                .andExpect(content().string(containsString("draftSaved")))
                .andExpect(content().string(containsString("localStorage.setItem")));

        // hidden 必须真的隐藏（作者 display 会盖掉 UA 的 [hidden]，需要显式规则）
        mockMvc.perform(get("/css/base.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".edit-draft-notice[hidden]")));
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
