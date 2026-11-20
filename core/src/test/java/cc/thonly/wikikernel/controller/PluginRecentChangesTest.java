package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.*;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PluginRecentChangesTest {
    @Autowired MockMvc mvc;
    @Autowired LogService logs;
    @Autowired WikiUserMapper users;
    @Autowired PermissionService permissions;
    @MockitoSpyBean PluginManager plugins;

    private void register() {
        LogType type = new LogType() {
            public String id() { return "plugin-change-test"; }
            public boolean inRecentChanges() { return true; }
            public TargetKind targetKind() { return TargetKind.PAGE; }
            public boolean visibleTo(cc.thonly.wikikernel.common.data.WikiLog log, Viewer viewer) {
                return !log.getTarget().contains("Private") || viewer.loggedIn();
            }
        };
        doReturn(List.of(type)).when(plugins).extensions(LogType.class);
    }

    @Test void registeredPluginLogsAppearInPageAndApiWithoutArtificialWikiPages() throws Exception {
        // An early page request must not freeze the registry before plugins have registered their types.
        mvc.perform(get("/wiki/特殊:最近更改")).andExpect(status().isOk());
        register();
        logs.record("plugin-change-test", null, "VirtualActivityObject", null, "PLUGIN_CHANGE_TOKEN");
        mvc.perform(get("/wiki/特殊:最近更改")).andExpect(status().isOk())
                .andExpect(content().string(containsString("PLUGIN_CHANGE_TOKEN")));
        mvc.perform(get("/api.php").param("action", "query").param("list", "recentchanges")
                        .param("rctype", "log").param("rcprop", "title|loginfo|comment"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("VirtualActivityObject")));
    }

    @Test void privatePluginEventsAreFilteredFromAllPublicLogSurfaces() throws Exception {
        register();
        logs.record("plugin-change-test", null, "VirtualPrivateObject", null, "PRIVATE_PLUGIN_EVENT");
        for (String path : List.of("/wiki/特殊:最近更改", "/wiki/特殊:日志")) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("PRIVATE_PLUGIN_EVENT"))));
        }
        mvc.perform(get("/api.php").param("action", "query").param("list", "recentchanges"))
                .andExpect(content().string(not(containsString("VirtualPrivateObject"))));
        WikiUser user = new WikiUser();user.setUsername("PluginLogViewer" + System.nanoTime()); user.setPassword("test"); users.insert(user);permissions.grantDefault(user.getId());
        var session = new MockHttpSession();session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        mvc.perform(get("/wiki/特殊:最近更改").session(session))
                .andExpect(content().string(containsString("PRIVATE_PLUGIN_EVENT")));
    }
}
