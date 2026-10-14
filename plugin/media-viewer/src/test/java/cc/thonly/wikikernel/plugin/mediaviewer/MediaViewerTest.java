package cc.thonly.wikikernel.plugin.mediaviewer;

import cc.thonly.wikikernel.common.extension.FileEmbedCall;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MediaPlayerEmbedRenderer}（音视频嵌入渲染）与 {@link MediaViewerHeadProvider}（脚本 / 样式注入）：
 * 开关语义、只处理音视频、宽度与说明、站点级注入与文案。
 */
class MediaViewerTest {

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

    private static SiteConfig config(boolean enabled) {
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return enabled;
            }
        };
    }

    private static MediaViewerSupport support(boolean enabled) {
        return new MediaViewerSupport(config(enabled), MESSAGES);
    }

    @Test
    void rendersPlayerForAudioAndVideoOnly() {
        MediaPlayerEmbedRenderer renderer = new MediaPlayerEmbedRenderer(support(true));

        String video = renderer.render(call("演示.webm", "video", 300));
        assertTrue(video != null && video.contains("wiki-media-video"), String.valueOf(video));
        assertTrue(video.contains("data-kind=\"video\""), video);
        assertTrue(video.contains("max-width:300px"), video);
        assertTrue(video.contains("<video class=\"wiki-media-element\""), video);
        assertTrue(video.contains("controls"), video);

        String audio = renderer.render(call("演示.mp3", "audio", null));
        assertTrue(audio != null && audio.contains("wiki-media-audio"), String.valueOf(audio));
        assertTrue(audio.contains("<audio class=\"wiki-media-element\""), audio);

        // 图片与其它文件交给 core
        assertNull(renderer.render(call("演示.png", "image", null)));
        assertNull(renderer.render(call("演示.pdf", "other", null)));
    }

    @Test
    void disabledFeatureFallsBackToCore() {
        MediaPlayerEmbedRenderer renderer = new MediaPlayerEmbedRenderer(support(false));
        assertNull(renderer.render(call("演示.webm", "video", 300)));
    }

    @Test
    void headProviderInjectsScriptsAndMessages() {
        MediaViewerHeadProvider provider = new MediaViewerHeadProvider(support(true));
        assertTrue(provider.siteWide(), "文件页与特殊页也需要播放器脚本");
        String joined = String.join("\n", provider.headItems(head()));
        assertTrue(joined.contains("/plugins/media-viewer/media-viewer.css"), joined);
        assertTrue(joined.contains("/plugins/media-viewer/media-viewer.js"), joined);
        assertTrue(joined.contains("/plugins/media-viewer/image-viewer.js"), joined);
        assertTrue(joined.contains("WIKI_CONFIG.mediaViewer = true"), joined);
        assertTrue(joined.contains("mediaPlay:"), joined);
        assertTrue(joined.contains("imageViewerClose:"), joined);

        assertEquals(List.of(), new MediaViewerHeadProvider(support(false)).headItems(head()));
    }

    @Test
    void escapesMessagesForInlineScript() {
        assertTrue(MediaViewerSupport.jsonEscape("a\"b<c>&d").contains("\\\""));
        assertTrue(MediaViewerSupport.jsonEscape("<x>").contains("\\u003c"));
        assertFalse(MediaViewerSupport.jsonEscape("<x>").contains("<"));
    }

    // ==================== 测试替身 ====================

    private static FileEmbedCall call(String fileName, String kind, Integer width) {
        return new FileEmbedCall() {
            @Override
            public String fileName() {
                return fileName;
            }

            @Override
            public String url() {
                return "/files/abc-" + fileName;
            }

            @Override
            public String pageUrl() {
                return "/wiki/文件:" + fileName;
            }

            @Override
            public String mediaKind() {
                return kind;
            }

            @Override
            public String mimeType() {
                return "video/webm";
            }

            @Override
            public long size() {
                return 1024;
            }

            @Override
            public Integer width() {
                return width;
            }

            @Override
            public String caption() {
                return "说明";
            }

            @Override
            public String alt() {
                return "文件:" + fileName;
            }

            @Override
            public Map<String, String> options() {
                return Map.of();
            }
        };
    }

    private static HeadContext head() {
        return new HeadContext() {
            @Override
            public long pageId() {
                return 1;
            }

            @Override
            public String pageTitle() {
                return "媒体演示页";
            }

            @Override
            public String path() {
                return "/wiki/媒体演示页";
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
