package cc.thonly.wikikernel.plugin.vuecomponents.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

/**
 * 页面级资源注入（{@code siteWide=false}）：页面用到 Vue 组件（渲染时写了
 * {@link VueComponentTag#USED_PROPERTY} 页面属性）才注入加载器；
 * 加载器是 ES 模块，自己 {@code import} 本地打包的 Vue 3（{@code vue.esm-browser.prod.js}）。
 */
public class VueHeadProvider implements HtmlHeadProvider {

    /** 资源内容哈希：插件更新后 URL 变化，避免浏览器 1 小时静态缓存继续用旧加载器。 */
    private static final String VERSION = assetVersion();

    @Override
    public int order() {
        return 640;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (context == null || !"1".equals(context.properties().get(VueComponentTag.USED_PROPERTY))) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/vue-components/vue-components.css?v=" + VERSION + "\">",
                "<script type=\"module\" src=\"/plugins/vue-components/vue-components.js?v=" + VERSION + "\"></script>");
    }

    private static String assetVersion() {
        try (InputStream input = VueHeadProvider.class.getResourceAsStream(
                "/static/plugins/vue-components/vue-components.js")) {
            if (input == null) {
                return "1";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder();
            for (byte value : digest.digest()) {
                out.append(String.format("%02x", value));
            }
            return out.substring(0, 12);
        } catch (Exception exception) {
            return "1";
        }
    }
}
