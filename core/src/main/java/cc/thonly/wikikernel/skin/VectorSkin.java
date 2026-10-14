package cc.thonly.wikikernel.skin;

import cc.thonly.wikikernel.common.extension.Skin;
import org.springframework.stereotype.Component;

/**
 * 内置默认皮肤：仿 MediaWiki Vector（经典版 2010）。
 * 外壳在 {@code templates/skins/vector/layout.html}，样式在 {@code static/skins/vector/vector.css}。
 */
@Component
public class VectorSkin implements Skin {

    public static final String ID = "vector";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public int order() {
        return 10;
    }
}
