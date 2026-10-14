package cc.thonly.wikikernel.plugin.multiupload;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多文件批量上传插件：head 注入（开关、脚本、批量文案）与子资源。
 * <p>
 * 前端的批量编排（列表 / 逐个上传 / 插入画廊 / 对话框批量插入）由
 * {@code /plugins/multi-upload/multi-upload.js} 实现，随插件 jar 一起发布；
 * core 侧只保留单文件流程（见 core 的 {@code MultiUploadTest}）。
 */
class MultiUploadTest {

    private static final MessageSource MESSAGES = new MessageSource() {
        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return defaultMessage;
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return code;
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            return resolvable.getDefaultMessage();
        }
    };

    private static MultiUploadSupport support(boolean enabled) {
        SiteConfig config = new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return MultiUploadPlugin.ENABLED_KEY.equals(key) ? enabled : defaultValue;
            }
        };
        return new MultiUploadSupport(config, MESSAGES);
    }

    @Test
    void headProviderInjectsScriptAndMessagesWhenEnabled() {
        MultiUploadHeadProvider provider = new MultiUploadHeadProvider(support(true));
        assertTrue(provider.siteWide(), "上传页与编辑页都要注入");
        String joined = String.join("\n", provider.headItems(head()));
        assertTrue(joined.contains("WIKI_CONFIG.multiUpload = true"), joined);
        assertTrue(joined.contains("/plugins/multi-upload/multi-upload.js"), joined);
        assertTrue(joined.contains("multiStatusUploading:"), joined);
        assertTrue(joined.contains("uploadChooseMany:"), joined);
        assertTrue(joined.contains("已选择的文件"), joined);
    }

    @Test
    void headProviderInjectsNothingWhenDisabled() {
        MultiUploadHeadProvider provider = new MultiUploadHeadProvider(support(false));
        assertEquals(List.of(), provider.headItems(head()));
    }

    @Test
    void messagesBundleCoversMultiUploadKeys() {
        Map<String, Map<String, String>> messages = new MultiUploadMessages().messages();
        assertTrue(messages.containsKey("zh") && messages.containsKey("en"));
        for (String key : List.of("files.multiUploadHint", "files.multiListLabel", "files.multiNameHint",
                "files.multiDescriptionHint", "files.multiUploading", "files.multiSummary",
                "files.multiSummaryPending", "files.multiRemove", "files.multiStatusQueued",
                "files.multiStatusUploading", "files.multiStatusDone", "files.multiStatusFailed",
                "files.chooseMany", "toolbar.uploadChooseMany")) {
            assertTrue(messages.get("zh").containsKey(key), "zh 缺少 " + key);
            assertTrue(messages.get("en").containsKey(key), "en 缺少 " + key);
        }
    }

    @Test
    void assetIsPackagedInPluginJar() {
        try (var in = getClass().getResourceAsStream("/static/plugins/multi-upload/multi-upload.js")) {
            assertTrue(in != null, "插件 jar 应包含 static/plugins/multi-upload/multi-upload.js");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static HeadContext head() {
        return new HeadContext() {
            @Override
            public long pageId() {
                return 0;
            }

            @Override
            public String pageTitle() {
                return "特殊:上传文件";
            }

            @Override
            public String path() {
                return "/wiki/特殊:上传文件";
            }

            @Override
            public Map<String, String> query() {
                return Map.of();
            }

            @Override
            public Map<String, String> properties() {
                return Map.of();
            }

            @Override
            public String siteName() {
                return "WikiKernel";
            }

            @Override
            public String baseUrl() {
                return "http://localhost:18080";
            }

            @Override
            public String fileUrl(String fileName) {
                return "";
            }

            @Override
            public int fileWidth(String fileName) {
                return 0;
            }

            @Override
            public int fileHeight(String fileName) {
                return 0;
            }
        };
    }
}
