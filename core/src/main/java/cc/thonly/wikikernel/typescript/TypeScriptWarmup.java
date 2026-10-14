package cc.thonly.wikikernel.typescript;

import cc.thonly.wikikernel.config.TypeScriptProperties;
import cc.thonly.wikikernel.service.FeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后**后台预热** TypeScript 引擎（{@code wikikernel.typescript.warmup}，默认开启）：
 * 引擎首次装载要读入并求值 8.7 MB 的 {@code typescript.js}（约 4 秒），预热后第一个脚本请求
 * 就不用等这次装载，请求线程的等待时间从「秒级」降到「毫秒级」。
 * <p>
 * 只在**功能开关打开时**预热（关闭时完全不加载 GraalJS，也就不会占内存）；预热在虚拟线程上进行，
 * 不阻塞启动流程。
 */
@Component
public class TypeScriptWarmup {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptWarmup.class);

    private final GraalTypeScriptCompiler compiler;
    private final FeatureService featureService;
    private final TypeScriptProperties properties;

    public TypeScriptWarmup(GraalTypeScriptCompiler compiler,
                           FeatureService featureService,
                           TypeScriptProperties properties) {
        this.compiler = compiler;
        this.featureService = featureService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!properties.isWarmup() || !featureService.typescriptEnabled()) {
            return;
        }
        log.info("TypeScript 编译已启用，后台预热编译器引擎（pool-size={}）…", properties.getPoolSize());
        compiler.warmUp();
    }
}
