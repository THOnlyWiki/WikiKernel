package cc.thonly.wikikernel.skin;

import cc.thonly.wikikernel.common.extension.Skin;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置兜底皮肤（不可禁用）：极简无装饰布局，Vector 被禁用 / 不可用时自动生效，保证站点永远能打开。
 * 样式只用 core 的 {@code base.css}，不额外加载皮肤 CSS。
 */
@Component
public class FallbackSkin implements Skin {

    public static final String ID = "fallback";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public List<String> stylesheets() {
        return List.of();
    }

    @Override
    public int order() {
        return 1000;
    }
}
