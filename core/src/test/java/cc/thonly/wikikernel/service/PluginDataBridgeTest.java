package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiKv;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.KvStore;
import cc.thonly.wikikernel.common.service.KvValue;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.FileWriter;
import cc.thonly.wikikernel.common.service.PageLinkStore;
import cc.thonly.wikikernel.common.service.PageQueryStore;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.common.service.SearchStore;
import cc.thonly.wikikernel.common.extension.SearchHit;
import cc.thonly.wikikernel.common.service.WikiDiff;
import cc.thonly.wikikernel.common.service.EditSubmitter;
import cc.thonly.wikikernel.common.service.PageWriter;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.db.mapper.WikiLogMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件数据桥（{@code common.service.*}）：评论 / 私信 / 头像 / 用户 / 渲染 / 日志 / 文件写入 / 差异。
 * 这些桥是治理类插件（评论、用户交互、审核…）迁移后的数据访问层，行为要与迁移前的 core 服务一致。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PluginDataBridgeTest {

    @Autowired
    private UserStore userStore;

    @Autowired
    private WikiText wikiText;

    @Autowired
    private LogWriter logWriter;

    @Autowired
    private PageWriter pageWriter;

    @Autowired
    private EditSubmitter editSubmitter;

    @Autowired
    private FileWriter fileWriter;

    @Autowired
    private WikiDiff wikiDiff;

    @Autowired
    private PageQueryStore pageQueryStore;

    @Autowired
    private PageLinkStore pageLinkStore;

    @Autowired
    private SearchStore searchStore;

    @Autowired
    private RateLimit rateLimit;

    @Autowired
    private KvStore kvStore;

    @Autowired
    private FileService fileService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiLogMapper wikiLogMapper;

    @Test
    void userStoreReadsNamesAvatarsAndGroups() {
        WikiUser user = insertUser();
        UserStore.UserRef ref = userStore.byId(user.getId()).orElseThrow();
        assertEquals(user.getUsername(), ref.username());
        assertEquals(user.getUsername(), userStore.byName(user.getUsername()).orElseThrow().username());
        assertTrue(userStore.byName("绝对不存在的用户" + System.nanoTime()).isEmpty());
        assertTrue(userStore.exists(user.getUsername()));

        Map<Long, String> names = userStore.names(List.of(user.getId()));
        assertEquals(user.getUsername(), names.get(user.getId()));
        assertEquals(Set.of(), userStore.groups(user.getId()));
    }

    @Test
    void wikiTextRendersThroughCoreRenderer() {
        String html = wikiText.toHtml("'''粗体'''与[[首页]]", "渲染桥测试页");
        assertTrue(html.contains("<b>粗体</b>"), html);
        assertTrue(html.contains("首页"), html);

        WikiText.Rendered rendered = wikiText.toHtmlWithCategories("正文[[分类:桥测试]]", "渲染桥测试页");
        assertTrue(rendered.categories().stream().anyMatch(title -> title.contains("桥测试")),
                rendered.categories().toString());
    }

    @Test
    void logWriterWritesIntoWikiLog() {
        WikiUser user = insertUser();
        long before = wikiLogMapper.selectCount(Wrappers.<cc.thonly.wikikernel.common.data.WikiLog>lambdaQuery());
        logWriter.record("edit", user.getId(), "日志桥测试页", "摘要");
        assertEquals(before + 1, wikiLogMapper.selectCount(
                Wrappers.<cc.thonly.wikikernel.common.data.WikiLog>lambdaQuery()));
    }

    @Test
    void pageWriterSavesWithOptimisticLockAndAiWatermark() {
        WikiUser user = insertUser();
        String title = "写入桥测试页" + System.nanoTime();
        cc.thonly.wikikernel.common.service.PageWriter.Result created =
                pageWriter.save(cc.thonly.wikikernel.common.service.PageWriter.Request.of(
                        user.getId(), title, "初版正文", "写入桥创建"));
        assertTrue(created.saved(), String.valueOf(created.error()));
        assertTrue(created.revisionId() > 0);
        assertEquals("初版正文", wikiPageService.getLatestContent(created.pageId()).orElse(""));

        // 版本冲突：expectedRevisionId 过期时不落盘
        cc.thonly.wikikernel.common.service.PageWriter.Result conflict =
                pageWriter.save(new cc.thonly.wikikernel.common.service.PageWriter.Request(
                        user.getId(), title, "覆盖别人的修改", "冲突测试", false, false, null,
                        created.revisionId() + 1000, false, null));
        assertFalse(conflict.saved());
        assertEquals("conflict", conflict.error());
        assertEquals("初版正文", wikiPageService.getLatestContent(created.pageId()).orElse(""));

        // AI 标记 + 乐观锁正确时保存成功
        cc.thonly.wikikernel.common.service.PageWriter.Result saved =
                pageWriter.save(cc.thonly.wikikernel.common.service.PageWriter.Request.aiAssisted(
                        user.getId(), title, "AI 改写的正文", "AI 提案", created.revisionId(), "mock/mock"));
        assertTrue(saved.saved(), String.valueOf(saved.error()));
        assertEquals("AI 改写的正文", wikiPageService.getLatestContent(created.pageId()).orElse(""));
        assertTrue(Boolean.TRUE.equals(wikiPageService.getLatestRevision(created.pageId())
                .orElseThrow().getAiAssisted()), "AI 协助的版本应带标记");

        // 用户不存在 / 标题为空
        assertFalse(pageWriter.save(cc.thonly.wikikernel.common.service.PageWriter.Request.of(
                999_999_999L, title + "x", "内容", "")).saved());
        assertFalse(pageWriter.save(cc.thonly.wikikernel.common.service.PageWriter.Request.of(
                user.getId(), "  ", "内容", "")).saved());

        // 删除（软删除）
        assertTrue(pageWriter.delete(user.getId(), title, "清理").saved());
        assertTrue(wikiPageService.getVisibleByTitle(title).isEmpty());
        assertFalse(pageWriter.delete(user.getId(), title, "清理").saved());
    }

    @Test
    void fileWriterStoresAndDeletesFiles() {
        WikiUser user = insertUser();
        String name = "文件写入桥" + System.nanoTime() + ".png";
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        cc.thonly.wikikernel.common.service.FileWriter.Result stored =
                fileWriter.store(user.getId(), name, "写入桥说明", png);
        assertTrue(stored.ok(), stored.error());
        assertTrue(stored.id() > 0);
        assertEquals(name, stored.name());
        assertEquals(png.length, stored.size());
        assertTrue(stored.contentType().startsWith("image/"), stored.contentType());
        assertTrue(fileService.findByName(name).isPresent(), "写入桥应把文件写进正式存储");
        // 同名再写 = 新版本
        cc.thonly.wikikernel.common.service.FileWriter.Result second =
                fileWriter.store(user.getId(), name, "第二次", new byte[]{1, 2, 3, 4, 5});
        assertTrue(second.ok(), second.error());
        assertEquals(5, second.size());

        // 校验失败：空内容 / 被禁止的扩展名 / 用户不存在
        assertFalse(fileWriter.store(user.getId(), name + "空.png", "", new byte[0]).ok());
        assertFalse(fileWriter.store(user.getId(), "桥测试.exe", "", png).ok());
        assertFalse(fileWriter.store(999_999_999L, name, "", png).ok());

        // 删除（软删除 → 文件不再可见；core 的软删除是幂等的）
        assertTrue(fileWriter.delete(user.getId(), stored.id(), false));
        assertTrue(fileService.findByName(name).isEmpty());
        assertTrue(fileWriter.delete(user.getId(), stored.id(), false));
        // 彻底删除后文件记录不存在 → false
        assertTrue(fileWriter.delete(user.getId(), stored.id(), true));
        assertTrue(fileService.findAnyByName(name).isEmpty());
        assertFalse(fileWriter.delete(user.getId(), stored.id(), false));
        assertFalse(fileWriter.delete(999_999_999L, stored.id(), false));
    }

    @Test
    void wikiDiffBridgeSharesTheCoreRowModel() {
        List<Map<String, Object>> rows = wikiDiff.rows("甲\n乙", "甲\n丙");
        assertFalse(rows.isEmpty());
        // 与 core 差异页同一套行模型：未变行 / 变化行都能渲染
        assertTrue(rows.stream().anyMatch(row -> "甲".equals(row.get("oldAfter"))
                        || "甲".equals(row.get("oldBefore")) || "甲".equals(row.get("oldChange"))),
                rows.toString());
        assertTrue(rows.stream().anyMatch(row -> "丙".equals(row.get("newChange"))
                        || "丙".equals(row.get("newAfter")) || "丙".equals(row.get("newBefore"))),
                rows.toString());
        for (Map<String, Object> row : rows) {
            assertTrue(row.containsKey("oldNo") && row.containsKey("oldMarker")
                    && row.containsKey("oldClass") && row.containsKey("newNo")
                    && row.containsKey("newMarker") && row.containsKey("newClass"), row.keySet().toString());
        }
        assertTrue(wikiDiff.rows("", "").size() <= 1);
    }

    @Test
    void editSubmitterRunsGuardsAndSavesWithWatermark() {
        WikiUser user = insertUser();
        String title = "提交桥测试页" + System.nanoTime();

        // 没有插件注册 EditGuard（core 测试环境）：直接保存
        EditSubmitter.Result created = editSubmitter.submit(EditSubmitter.Request.of(
                user.getId(), title, "初版正文", "提交桥创建"));
        assertTrue(created.saved(), String.valueOf(created.error()));
        assertTrue(created.revisionId() > 0);
        assertEquals("初版正文", wikiPageService.getLatestContent(created.pageId()).orElse(""));

        // 乐观锁冲突：不落盘
        EditSubmitter.Result conflict = editSubmitter.submit(new EditSubmitter.Request(
                user.getId(), title, "别人的改动", "冲突", false, created.revisionId() + 1000L,
                false, null, EditSubmitter.SOURCE_AI, "", "", java.util.Map.of()));
        assertFalse(conflict.saved());
        assertEquals("conflict", conflict.error());

        // AI 协助提交：保存 + 打水印
        EditSubmitter.Result saved = editSubmitter.submit(EditSubmitter.Request.aiAssisted(
                user.getId(), title, "AI 改写的正文", "AI 提案", created.revisionId(), "mock/mock"));
        assertTrue(saved.saved(), String.valueOf(saved.error()));
        assertTrue(Boolean.TRUE.equals(wikiPageService.getLatestRevision(created.pageId())
                .orElseThrow().getAiAssisted()), "AI 协助的版本应带标记");

        // 用户不存在 / 标题为空
        assertFalse(editSubmitter.submit(EditSubmitter.Request.of(
                999_999_999L, title + "x", "内容", "")).saved());
        assertFalse(editSubmitter.submit(EditSubmitter.Request.of(user.getId(), "   ", "内容", "")).saved());
    }
    @Test
    void searchStoreFindsPagesThroughCoreSearch() {
        WikiUser user = insertUser();
        String title = "搜索桥测试页" + System.nanoTime();
        String unique = "独特词" + System.nanoTime();
        wikiPageService.editPage(user, title, "正文里有 " + unique + " 这个词");

        List<SearchHit> hits = searchStore.search(unique, 10);
        assertFalse(hits.isEmpty(), "core 搜索引擎应能命中刚写入的页面");
        assertTrue(hits.stream().anyMatch(hit -> title.equals(hit.title())), hits.toString());
        assertTrue(searchStore.search("", 5).size() <= 5, "limit 生效");
    }

    @Test
    void pageQueryStoreReadsDetailListAndHistory() {
        WikiUser user = insertUser();
        String title = "查询桥测试页" + System.nanoTime();
        wikiPageService.editPage(user, title, "第一版");
        wikiPageService.editPage(user, title, "第二版");

        PageQueryStore.PageDetail detail = pageQueryStore.detail(title).orElseThrow();
        assertEquals(title, detail.title());
        assertTrue(detail.id() > 0);
        assertTrue(detail.latestRevisionId() != null && detail.latestRevisionId() > 0);
        assertTrue(pageQueryStore.detail("不存在的页面" + System.nanoTime()).isEmpty());
        assertFalse(pageQueryStore.namespaces().isEmpty(), "命名空间列表来自 core 的 WikiNamespaces");

        // 前缀 + 命名空间过滤（(主) 命名空间用空串）
        List<PageQueryStore.PageEntry> listed = pageQueryStore.list(title.substring(0, 4), "", 50);
        assertTrue(listed.stream().anyMatch(entry -> title.equals(entry.title())), listed.toString());

        List<PageQueryStore.RevisionEntry> history = pageQueryStore.history(detail.id(), 10);
        assertTrue(history.size() >= 2, "两次编辑应有两个版本");
        assertTrue(pageQueryStore.latestRevision(detail.id()).isPresent());
    }

    @Test
    void pageLinkStoreSeesTemplatesLinksAndTransclusions() {
        WikiUser user = insertUser();
        String suffix = String.valueOf(System.nanoTime());
        String template = "模板:链接桥模板" + suffix;
        String transcluding = "链接桥嵌入页" + suffix;
        String linking = "链接桥链接页" + suffix;
        wikiPageService.editPage(user, template, "模板内容");
        wikiPageService.editPage(user, transcluding, "{{" + template + "}}");
        wikiPageService.editPage(user, linking, "见 [[" + transcluding + "]]");

        assertTrue(pageLinkStore.templatesUsedBy(transcluding).stream()
                .anyMatch(t -> t.contains("链接桥模板" + suffix)), pageLinkStore.templatesUsedBy(transcluding).toString());
        assertTrue(pageLinkStore.pagesTranscluding(template, 10).contains(transcluding),
                pageLinkStore.pagesTranscluding(template, 10).toString());
        assertTrue(pageLinkStore.pagesLinkingTo(transcluding, 10).contains(linking),
                pageLinkStore.pagesLinkingTo(transcluding, 10).toString());
        assertTrue(pageLinkStore.templatesUsedBy("没有这个页面" + suffix).isEmpty());
    }

    @Test
    void rateLimitBridgeExposesSlidingWindow() {
        String category = "plugin-bridge-test";
        String key = "k" + System.nanoTime();
        RateLimit.Decision first = rateLimit.check(category, key);
        assertFalse(first.limited(), "没有任何命中记录时不应被限流");
        assertEquals(0, first.retryAfterSeconds());

        // hit / success 都不抛异常，且 success 会清掉该 key 的窗口
        rateLimit.hit(category, key);
        rateLimit.hit(category, key);
        rateLimit.success(category, key);
        assertFalse(rateLimit.check(category, key).limited(), "success 之后应重新放行");
    }

    @Test
    void kvStoreHandlesStringsNumbersJsonAndIncrements() {
        String key = "bridge.kv." + System.nanoTime();

        // 读不到时的默认值 / 空值语义
        assertTrue(kvStore.get(key).isEmpty());
        assertFalse(kvStore.exists(key));
        assertEquals("默认值", kvStore.getString(key, "默认值"));
        assertTrue(kvStore.string(key).isEmpty());
        assertTrue(kvStore.number(key).isEmpty());
        assertEquals(7.5d, kvStore.getNumber(key, 7.5d));
        assertTrue(kvStore.json(key).isEmpty());

        // 字符串：写 → 读（含 get 带 type 的原样读法）→ 覆盖写
        kvStore.putString(key, "第一版");
        assertTrue(kvStore.exists(key));
        assertEquals("第一版", kvStore.getString(key, "默认值"));
        assertEquals("第一版", kvStore.string(key).orElseThrow());
        KvValue stored = kvStore.get(key).orElseThrow();
        assertTrue(stored.id() > 0);
        assertEquals(key, stored.name());
        assertEquals(WikiKv.TYPE_STRING, stored.type());
        assertEquals("第一版", stored.rawValue());
        assertEquals("第一版", stored.asString());
        kvStore.putString(key, "第二版");
        assertEquals("第二版", kvStore.getString(key, "默认值"), "同名的第二次写应覆盖");

        // 数值：写 → 读；increment 返回新值，键不存在时从 0 开始
        kvStore.putNumber(key, 42.0d);
        assertEquals(WikiKv.TYPE_NUMBER, kvStore.get(key).orElseThrow().type());
        assertEquals(42.0d, kvStore.getNumber(key, 0d));
        assertEquals(42L, kvStore.number(key).orElseThrow());
        assertEquals(142L, kvStore.increment(key, 100L));
        assertEquals(139L, kvStore.increment(key, -3L));
        assertEquals(139.0d, kvStore.getNumber(key, 0d));
        String freshKey = key + ".counter";
        assertEquals(1L, kvStore.increment(freshKey, 1L), "键不存在时 increment 从 0 开始");

        // JSON：写 → 读（原样往返）→ 覆盖写
        ObjectNode first = JsonNodeFactory.instance.objectNode();
        first.put("a", 1);
        first.putArray("list").add(1).add(2);
        kvStore.putJson(key, first);
        assertEquals(WikiKv.TYPE_JSON, kvStore.get(key).orElseThrow().type());
        assertEquals(1, kvStore.json(key).orElseThrow().get("a").asInt());
        assertTrue(kvStore.json(key).orElseThrow().get("list").isArray());
        ObjectNode second = JsonNodeFactory.instance.objectNode();
        second.put("b", "x");
        kvStore.putJson(key, second);
        JsonNode readBack = kvStore.json(key).orElseThrow();
        assertNull(readBack.get("a"), "覆盖写之后旧内容不应残留");
        assertEquals("x", readBack.get("b").asString());

        // 四个前缀相同的键：list 按 name 升序 + 前缀过滤 + limit
        String idKey = key + ".id";
        String nameKey = key + ".name";
        kvStore.putString(idKey, "7");
        kvStore.putString(nameKey, "示例");
        List<KvValue> listed = kvStore.list(key, 10);
        assertEquals(4, listed.size(), listed.toString());
        assertEquals(List.of(key, freshKey, idKey, nameKey), listed.stream().map(KvValue::name).toList(),
                "list 应按 name 升序");
        assertEquals(2, kvStore.list(key, 2).size(), "limit 生效");
        assertEquals(List.of(freshKey, idKey, nameKey),
                kvStore.list(key + ".", 10).stream().map(KvValue::name).toList(),
                "带点的前缀只命中带点的子键");
        assertTrue(kvStore.list("绝对不存在的前缀" + key, 10).isEmpty());
        assertFalse(kvStore.list("", 0).isEmpty(), "空前缀 + 不限条数应能列出全部键");

        // delete：删掉返回 true，再删返回 false
        assertTrue(kvStore.delete(idKey));
        assertFalse(kvStore.exists(idKey));
        assertFalse(kvStore.delete(idKey));
        assertEquals(3, kvStore.list(key, 10).size());

        // 非法入参：空键 / 超长键 / null 值 / JSON 自增 → 每次都抛异常
        assertThrows(IllegalArgumentException.class, () -> kvStore.getString("   ", ""));
        assertThrows(IllegalArgumentException.class, () -> kvStore.putString(null, "x"));
        String tooLong = "k".repeat(KvStore.MAX_NAME_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> kvStore.putString(tooLong, "x"));
        assertThrows(IllegalArgumentException.class, () -> kvStore.getString(tooLong, ""));
        assertThrows(IllegalArgumentException.class, () -> kvStore.putString(key, null));
        assertThrows(IllegalArgumentException.class, () -> kvStore.putJson(key, null));
        kvStore.putJson(key, JsonNodeFactory.instance.stringNode("文本"));
        assertThrows(IllegalArgumentException.class, () -> kvStore.increment(key, 1L),
                "JSON 类型不能自增");
        kvStore.putString(key, "不是数字");
        assertThrows(IllegalArgumentException.class, () -> kvStore.increment(key, 1L),
                "非数字字符串不能自增");
    }

    private WikiUser insertUser() {
        WikiUser user = new WikiUser();
        user.setUsername("数据桥用户" + System.nanoTime());
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
