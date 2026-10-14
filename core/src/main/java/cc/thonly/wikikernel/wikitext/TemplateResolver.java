package cc.thonly.wikikernel.wikitext;

import java.util.Optional;

/**
 * 模板内容解析：给定带命名空间的标题（如 {@code 模板:abc模板}），返回模板正文。
 * <p>
 * 做成接口方便替换（内存、文件、数据库）与单元测试。
 */
@FunctionalInterface
public interface TemplateResolver {

    Optional<String> resolve(String title);
}
