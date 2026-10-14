package cc.thonly.wikikernel.common.extension;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 一次页面渲染（解析）内的共享上下文：解析器函数 / 魔术字 / 行为开关 / 标签等扩展点读写用。
 * <p>
 * 由 core 在每次渲染时创建；同一页面的所有模板展开共享同一个实例，渲染结束后丢弃。
 * 插件通过 {@link ParserFunctionCall#context()}、{@link ParserTagCall#context()} 等拿到它。
 */
public interface WikiRenderContext {

    /** 当前页面标题（含命名空间，如 {@code 模板:问候}）。 */
    String pageTitle();

    /**
     * 渲染内变量存储（Variables 扩展的 {@code #vardefine} 等用）：
     * 跨模板共享、页面级作用域，渲染结束后丢弃。键值都是字符串。
     */
    Map<String, Object> store();

    /** 写入页面属性（渲染结束后由 core 保存到页面属性表，如 SEO 元数据、templatedata）。 */
    void setProperty(String name, String value);

    /** 读取本次渲染已写入的页面属性（没有则返回 defaultValue）。 */
    String property(String name, String defaultValue);

    /** 本次渲染已写入的全部页面属性（只读视图）。 */
    Map<String, String> properties();

    /**
     * 注册「解析结束后再求值」的占位（Variables 的 {@code #var_final} 用）：
     * 返回值请放进输出文本，解析完成后 core 会用 {@code resolver} 的结果按 wikitext 展开并替换。
     *
     * @param resolver 解析结束时求值的提供者（结果按 wikitext 展开）
     * @return 私用区字符组成的占位串
     */
    String deferWikitext(Supplier<String> resolver);
}
