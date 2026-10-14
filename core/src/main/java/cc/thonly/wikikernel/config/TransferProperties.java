package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 页面导出 / 导入（{@code 特殊:导出页面}、{@code 特殊:导入页面}）的限额配置。
 * <p>
 * 对应 LocalSettings.yaml 的 {@code wikikernel.transfer.*}；网页表单上会显示当前限额。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.transfer")
public class TransferProperties {

    /** 单次导出最多页面数（含「包含所有引用的模板」带出的模板页）。 */
    private int exportMaxPages = 200;

    /** 单次导出最多版本数（勾选「包含完整历史」时的总上限）。 */
    private int exportMaxRevisions = 5000;

    /** 单次导入最多页面数（超出部分忽略并在结果里提示）。 */
    private int importMaxPages = 2000;

    /** 单次导入最多版本数（超出部分忽略并在结果里提示）。 */
    private int importMaxRevisions = 20000;

    /** 导入文件大小上限（字节；Spring multipart 之上再校验一次，默认 10MB）。 */
    private long importMaxBytes = 10L * 1024 * 1024;
}
