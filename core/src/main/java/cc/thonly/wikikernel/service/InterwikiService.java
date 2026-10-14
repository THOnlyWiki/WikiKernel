package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiInterwiki;
import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.List;
import java.util.Optional;

/**
 * 跨 Wiki 前缀（仿 MediaWiki 的 Interwiki 功能 / {@code 特殊:跨Wiki}）。
 * <p>
 * 前缀表决定 {@code [[前缀:页面]]} 的去向：外部 wiki（生成外链）、语言链接（收集到侧栏「语言」）
 * 或本地前缀（生成本站内部链接）。表在 {@code 特殊:跨Wiki} 里维护，只有管理员 / 行政员可改。
 */
public interface InterwikiService {

    /** 全部前缀（按前缀升序）。 */
    List<WikiInterwiki> all();

    /** 语言链接前缀（{@code language} 非空，按语言码排序）。 */
    List<WikiInterwiki> languages();

    /** 前缀命中（大小写不敏感）→ 目标；未命中返回空。 */
    Optional<Target> find(String prefix);

    /** 新建（{@code id == null}）或更新；校验失败抛 {@code IllegalArgumentException(消息键)}。 */
    WikiInterwiki save(Long id, Form form, WikiUser actor);

    /** 删除；返回是否删掉了行。 */
    boolean delete(Long id);

    /** 导出为文本（每行：前缀 + TAB + URL + TAB + 标志，仿 MediaWiki 的跨 Wiki 表文本格式）。 */
    String exportText();

    /** 从文本导入（已存在的前缀就地更新，不存在的新建）；返回处理的条数。 */
    int importText(String text);

    /** 语言码的本地化名称（内置常见语言表，未知回退为大写语言码）。 */
    String languageName(String code);

    /** 解析出的链接目标。 */
    record Target(String prefix, String urlTemplate, String language, boolean local, boolean transcludable) {

        /** 是否语言链接（正文里不显示，收集到侧栏）。 */
        public boolean languageLink() {
            return language != null && !language.isBlank();
        }
    }

    /** 表单（新增 / 编辑）。 */
    record Form(String prefix, String url, String language, boolean local, boolean transcludable) {
    }

    /** 正文里的一条跨语言链接（{@code languageName} 是按当前界面语言本地化的语言名，用于侧栏「语言」）。 */
    record Link(String prefix, String title, String url, String language, String languageName) {
    }
}
