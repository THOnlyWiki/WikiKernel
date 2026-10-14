package cc.thonly.wikikernel.plugin.wikiseo;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * WikiSEO 单元测试用的手写替身（不依赖 Spring 上下文）。
 */
public final class SeoFakes {

    private SeoFakes() {
    }

    /** {@link HeadContext} 替身：页面属性、请求参数、站点信息与假文件表都可直接摆。 */
    public static final class FakeHeadContext implements HeadContext {

        long pageId = 1;
        public String pageTitle = "示例页面";
        public String path = "/wiki/%E7%A4%BA%E4%BE%8B%E9%A1%B5%E9%9D%A2";
        public String siteName = "示例站";
        public String baseUrl = "http://localhost:8080";
        public final Map<String, String> query = new LinkedHashMap<>();
        public final Map<String, String> properties = new LinkedHashMap<>();
        public final Map<String, String> fileUrls = new LinkedHashMap<>();
        public final Map<String, int[]> fileSizes = new LinkedHashMap<>();

        public FakeHeadContext property(String name, String value) {
            properties.put(name, value);
            return this;
        }

        public FakeHeadContext query(String name, String value) {
            query.put(name, value);
            return this;
        }

        public FakeHeadContext pageTitle(String title) {
            this.pageTitle = title;
            return this;
        }

        public FakeHeadContext file(String name, String url, int width, int height) {
            fileUrls.put(name, url);
            fileSizes.put(name, new int[]{width, height});
            return this;
        }

        @Override
        public long pageId() {
            return pageId;
        }

        @Override
        public String pageTitle() {
            return pageTitle;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public Map<String, String> query() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(query));
        }

        @Override
        public Map<String, String> properties() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        @Override
        public String siteName() {
            return siteName;
        }

        @Override
        public String baseUrl() {
            return baseUrl;
        }

        @Override
        public String fileUrl(String fileName) {
            return fileUrls.getOrDefault(fileName, "");
        }

        @Override
        public int fileWidth(String fileName) {
            int[] size = fileSizes.get(fileName);
            return size == null ? 0 : size[0];
        }

        @Override
        public int fileHeight(String fileName) {
            int[] size = fileSizes.get(fileName);
            return size == null ? 0 : size[1];
        }
    }

    /** {@link WikiRenderContext} 替身：属性直接存在 LinkedHashMap 里。 */
    public static final class FakeRenderContext implements WikiRenderContext {

        private final String pageTitle;
        public final Map<String, Object> variables = new LinkedHashMap<>();
        public final Map<String, String> properties = new LinkedHashMap<>();

        public FakeRenderContext(String pageTitle) {
            this.pageTitle = pageTitle;
        }

        @Override
        public String pageTitle() {
            return pageTitle;
        }

        @Override
        public Map<String, Object> store() {
            return variables;
        }

        @Override
        public void setProperty(String name, String value) {
            properties.put(name, value);
        }

        @Override
        public String property(String name, String defaultValue) {
            return properties.getOrDefault(name, defaultValue);
        }

        @Override
        public Map<String, String> properties() {
            return Map.copyOf(properties);
        }

        @Override
        public String deferWikitext(Supplier<String> resolver) {
            return resolver.get();
        }
    }

    /** {@link ParserFunctionCall} 替身：{{名字}} 按 templates 表替换。 */
    public static final class FakeFunctionCall implements ParserFunctionCall {

        private final WikiRenderContext context;
        private final List<String> args;
        public final Map<String, String> templates = new LinkedHashMap<>();

        public FakeFunctionCall(WikiRenderContext context, List<String> args) {
            this.context = context;
            this.args = args;
        }

        public FakeFunctionCall template(String name, String value) {
            templates.put(name, value);
            return this;
        }

        @Override
        public WikiRenderContext context() {
            return context;
        }

        @Override
        public List<String> args() {
            return List.copyOf(args);
        }

        @Override
        public String expand(String wikitext) {
            String result = wikitext == null ? "" : wikitext;
            for (Map.Entry<String, String> entry : templates.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return result;
        }
    }

    /** {@link ParserTagCall} 替身（属性键按 core 的行为小写化）。 */
    public static final class FakeTagCall implements ParserTagCall {

        private final WikiRenderContext context;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final String body;

        public FakeTagCall(WikiRenderContext context, String body) {
            this.context = context;
            this.body = body;
        }

        public FakeTagCall attribute(String name, String value) {
            attributes.put(name.toLowerCase(Locale.ROOT), value);
            return this;
        }

        @Override
        public WikiRenderContext context() {
            return context;
        }

        @Override
        public String tag() {
            return "seo";
        }

        @Override
        public Map<String, String> attributes() {
            return attributes;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public String expand(String wikitext) {
            return wikitext == null ? "" : wikitext;
        }
    }

    /** {@link WikiPageStore} 替身：正文与快照都从内存表读。 */
    public static final class FakePageStore implements WikiPageStore {

        public final Map<Long, String> contents = new LinkedHashMap<>();
        public final Map<Long, PageSnapshot> snapshots = new LinkedHashMap<>();

        public FakePageStore content(long pageId, String title, String content, LocalDateTime updatedAt) {
            contents.put(pageId, content);
            snapshots.put(pageId, new PageSnapshot(pageId, title, false, true,
                    content == null ? 0 : content.length(), updatedAt));
            return this;
        }

        @Override
        public Optional<PageSnapshot> byId(long pageId) {
            return Optional.ofNullable(snapshots.get(pageId));
        }

        @Override
        public Optional<PageSnapshot> byTitle(String title) {
            return snapshots.values().stream().filter(page -> page.title().equals(title)).findFirst();
        }

        @Override
        public Optional<String> content(long pageId) {
            return Optional.ofNullable(contents.get(pageId));
        }

        @Override
        public List<PageSnapshot> contentPages() {
            return new ArrayList<>(snapshots.values());
        }
    }

    /** {@link SetupContext} 替身：记录写入的插件配置键值。 */
    public static final class FakeSetupContext implements SetupContext {

        public final Map<String, Object> globals = new LinkedHashMap<>();
        public final Map<String, Object> settings = new LinkedHashMap<>();

        @Override
        public void putGlobal(String path, Object value) {
            globals.put(path, value);
        }

        @Override
        public void putPluginSetting(String key, Object value) {
            settings.put(key, value);
        }
    }
}
