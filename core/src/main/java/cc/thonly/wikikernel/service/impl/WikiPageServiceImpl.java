package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.InvalidTitleException;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 页面 + 历史版本实现（MediaWiki 式 page/revision 两表模型）。
 */
@Service
public class WikiPageServiceImpl implements WikiPageService {

    /** 全站样式页：内容会注入所有页面（类似 MediaWiki:Common.css 的角色）。 */
    private static final String SITE_CSS_TITLE = "Wiki.css";

    /** 第二份全站样式页（WikiKernel 命名空间，随 Wiki.css 一起注入所有页面）。 */
    private static final String COMMON_CSS_TITLE = "WikiKernel:Common.css";

    /** 页面级样式：主页面下以 .css 结尾的子页面，如 {@code 首页/样式.css}。 */
    private static final String CSS_SUFFIX = ".css";

    /** 全站脚本页：内容会在每个页面以 &lt;script src&gt; 加载。 */
    private static final String SITE_JS_TITLE = "WikiKernel:Wiki.js";

    /** 第二份全站脚本页（随 WikiKernel:Wiki.js 一起在每个页面加载）。 */
    private static final String COMMON_JS_TITLE = "WikiKernel:Common.js";

    /** 按皮肤生效的站点样式 / 脚本页标题前缀与后缀：{@code WikiKernel:<皮肤 id>.css} / {@code .js}。 */
    private static final String SKIN_PAGE_NAMESPACE_PREFIX = "WikiKernel:";

    /** 皮肤页标题里允许出现的皮肤 id 字符（皮肤 id 由插件 / core 注册，正常都是小写字母加连字符）。 */
    private static final Pattern SKIN_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 页面级脚本：主页面下以 .js 结尾的子页面，如 {@code 首页/脚本.js}。 */
    private static final String JS_SUFFIX = ".js";

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final LogService logService;
    private final PermissionService permissionService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * TypeScript 编译（{@code {title}/XXX.ts} 子页面同样当页面脚本）；
     * 用 {@code ObjectProvider} 取值，避免手工构造（测试）时必须提供。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cc.thonly.wikikernel.typescript.TypeScriptAssetService typeScriptAssetService;

    /**
     * 皮肤注册表：只用于**旧签名的兜底**（{@code getCSS(title)} / {@code getJS(title)} 按站点默认皮肤取皮肤页）。
     * 用 {@code required = false} 取值，避免手工构造（测试）时必须提供；请求级渲染一律由调用点显式传入皮肤 id。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cc.thonly.wikikernel.skin.SkinRegistry skinRegistry;

    public WikiPageServiceImpl(WikiPageMapper wikiPageMapper,
                               WikiRevisionMapper wikiRevisionMapper,
                               LogService logService,
                               PermissionService permissionService,
                               ApplicationEventPublisher eventPublisher) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.logService = logService;
        this.permissionService = permissionService;
        this.eventPublisher = eventPublisher;
    }


    @Override
    @Transactional
    public WikiPage createPage(WikiUser user, String title, String content) {
        return this.createPage(user, title, content, null);
    }

    @Override
    @Transactional
    public WikiPage createPage(WikiUser user, String title, String content, String comment) {
        return createPageInternal(user, title, content, comment, false, false, "");
    }

    /** 创建页面（机器人标记 / 标签由保存入口传入）。 */
    private WikiPage createPageInternal(WikiUser user, String title, String content, String comment,
                                         boolean bot, boolean minor, String tags) {
        String normalized = normalizeTitle(title);
        if (getByTitle(normalized).isPresent()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        // 标题下有「已删除」的页面：重新创建 = 把这一行复活（deleted=false）再追加新版本，
        // 否则插入会撞 title 唯一键（MediaWiki 里被删页面可以重新创建）。
        WikiPage revived = findDeletedByTitle(normalized).orElse(null);
        if (revived != null) {
            revived.setDeleted(false);
            revived.setUpdateAt(now);
            wikiPageMapper.updateById(revived);
            WikiRevision revision = appendRevision(revived, user, content, comment, now, bot, minor, tags);
            revived.setLatestRevisionId(revision.getId());
            wikiPageMapper.updateById(revived);
            logService.record(LogTypeConfig.CREATE, actorId(user), revived.getTitle(), null, null);
            publish(revived, false);
            return revived;
        }
        WikiPage page = new WikiPage(null, normalized, null, false, false, false, now, now);
        wikiPageMapper.insert(page);
        WikiRevision revision = appendRevision(page, user, content, comment, now, bot, minor, tags);
        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);
        logService.record(LogTypeConfig.CREATE, actorId(user), page.getTitle(), null, null);
        publish(page, false);
        return page;
    }

    @Override
    @Transactional
    public WikiPage editPage(WikiUser user, String title, String content) {
        return this.editPage(user, title, content, null);
    }

    @Override
    @Transactional
    public WikiPage editPage(WikiUser user, String title, String content, String comment) {
        return this.editPage(user, title, content, comment, false, "");
    }

    @Override
    @Transactional
    public WikiPage editPage(WikiUser user, String title, String content, String comment,
                             boolean bot, String tags) {
        return editPage(user, title, content, comment, bot, false, tags);
    }

    @Override
    @Transactional
    public WikiPage editPage(WikiUser user, String title, String content, String comment,
                             boolean bot, boolean minor, String tags) {
        String normalized = normalizeTitle(title);
        Optional<WikiPage> existing = getByTitle(normalized);
        if (existing.isEmpty()) {
            return createPageInternal(user, normalized, content, comment, bot, minor, tags);
        }
        WikiPage page = existing.get();
        LocalDateTime now = LocalDateTime.now();
        WikiRevision revision = appendRevision(page, user, content, comment, now, bot, minor, tags);
        page.setLatestRevisionId(revision.getId());
        page.setUpdateAt(now);
        wikiPageMapper.updateById(page);
        logService.record(LogTypeConfig.EDIT, actorId(user), page.getTitle(), null, comment);
        publish(page, false);
        return page;
    }

    @Override
    @Transactional
    public WikiPage editPageIfLatest(WikiUser user, String title, String content, String comment,
                                     boolean bot, String tags, Long expectedRevisionId) {
        return editPageIfLatest(user, title, content, comment, bot, false, tags, expectedRevisionId);
    }

    @Override
    @Transactional
    public WikiPage editPageIfLatest(WikiUser user, String title, String content, String comment,
                                     boolean bot, boolean minor, String tags, Long expectedRevisionId) {
        String normalized = normalizeTitle(title);
        Optional<WikiPage> existing = getByTitle(normalized);
        if (existing.isEmpty()) {
            return createPageInternal(user, normalized, content, comment, bot, minor, tags);
        }
        WikiPage page = existing.get();
        if (expectedRevisionId == null || page.getLatestRevisionId() == null
                || !Objects.equals(page.getLatestRevisionId(), expectedRevisionId)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        WikiRevision revision = appendRevision(page, user, content, comment, now, bot, minor, tags);
        int updated = wikiPageMapper.updateLatestIfMatches(page.getId(), expectedRevisionId,
                revision.getId(), now);
        if (updated != 1) {
            // appendRevision 与 CAS 在同一事务中；主动删除让返回 null 也不会留下孤立修订。
            wikiRevisionMapper.deleteById(revision.getId());
            return null;
        }
        page.setLatestRevisionId(revision.getId());
        page.setUpdateAt(now);
        logService.record(LogTypeConfig.EDIT, actorId(user), page.getTitle(), null, comment);
        publish(page, false);
        return page;
    }

    @Override
    @Transactional
    public WikiPage deletePage(WikiUser user, String title) {
        String normalized = normalizeTitle(title);
        WikiPage page = getByTitle(normalized).orElse(null);
        if (page == null) {
            return null;
        }
        page.setDeleted(true);
        page.setUpdateAt(LocalDateTime.now());
        wikiPageMapper.updateById(page);
        logService.record(LogTypeConfig.DELETE, actorId(user), page.getTitle(), null, null);
        publish(page, true);
        return page;
    }

    @Override
    public Optional<WikiPage> getById(Long pageId) {
        // 不过滤 deleted：REST/Action API 拿到版本号后要反查页面做权限判定，
        // 是否已删除由调用方判断（对外一律 404 还是 410，由调用方决定）
        return pageId == null ? Optional.empty() : Optional.ofNullable(wikiPageMapper.selectById(pageId));
    }

    @Override
    public Optional<WikiPage> findDeletedByTitle(String title) {
        String normalized = normalizeTitle(title);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        Optional<WikiPage> page = selectDeletedExactTitle(normalized);
        if (page.isPresent()) {
            return page;
        }
        for (String alias : WikiNamespaces.aliasVariants(normalized)) {
            Optional<WikiPage> legacy = selectDeletedExactTitle(alias);
            if (legacy.isPresent()) {
                return legacy;
            }
        }
        return Optional.empty();
    }

    /** 按标题精确查一行**已删除**页面。 */
    private Optional<WikiPage> selectDeletedExactTitle(String title) {
        return Optional.ofNullable(wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, title)
                .eq(WikiPage::getDeleted, true)
                .last("LIMIT 1")));
    }

    @Override
    public List<WikiPage> deletedPages(int limit) {
        int capped = limit <= 0 ? 200 : Math.min(limit, 500);
        return wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, true)
                .orderByDesc(WikiPage::getUpdateAt)
                .orderByDesc(WikiPage::getId)
                .last("LIMIT " + capped));
    }

    @Override
    @Transactional
    public WikiPage restorePage(WikiUser user, String title) {
        WikiPage page = findDeletedByTitle(title).orElse(null);
        if (page == null) {
            return null;
        }
        page.setDeleted(false);
        page.setUpdateAt(LocalDateTime.now());
        wikiPageMapper.updateById(page);
        logService.record(LogTypeConfig.RESTORE, actorId(user), page.getTitle(), null, null);
        publish(page, false);
        return page;
    }

    @Override
    @Transactional
    public WikiPage movePage(WikiUser user, String fromTitle, String toTitle) {
        return this.movePage(user, fromTitle, toTitle, false);
    }

    @Override
    @Transactional
    public WikiPage movePage(WikiUser user, String fromTitle, String toTitle, boolean leaveRedirect) {
        WikiPage page = getByTitle(normalizeTitle(fromTitle)).orElse(null);
        if (page == null) {
            return null;
        }
        String previousTitle = page.getTitle();
        page.setTitle(normalizeTitle(toTitle));
        page.setUpdateAt(LocalDateTime.now());
        wikiPageMapper.updateById(page);
        // 日志：目标 = 新标题，详情存原标题（展示时本地化）
        logService.record(LogTypeConfig.MOVE, actorId(user), page.getTitle(), null, previousTitle);
        if (leaveRedirect) {
            createRedirectPage(user, previousTitle, page.getTitle());
        }
        publish(page, false);
        return page;
    }

    /** 移动时在旧标题留下重定向（旧标题已被占用则放弃；不记创建日志，移动日志已说明去向）。 */
    private void createRedirectPage(WikiUser user, String oldTitle, String newTitle) {
        String normalized = normalizeTitle(oldTitle);
        if (getByTitle(normalized).isPresent()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        WikiPage redirect = new WikiPage(null, normalized, null, false, false, false, now, now);
        wikiPageMapper.insert(redirect);
        WikiRevision revision = appendRevision(redirect, user,
                "#REDIRECT [[" + newTitle + "]]", "移动页面时留下重定向", now, false, false, "");
        redirect.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(redirect);
    }

    @Override
    @Transactional
    public Optional<WikiPage> setProtected(WikiUser user, String title, boolean protectedPage) {
        WikiPage page = getByTitle(normalizeTitle(title)).orElse(null);
        if (page == null) {
            return Optional.empty();
        }
        page.setProtectedPage(protectedPage);
        page.setUpdateAt(LocalDateTime.now());
        wikiPageMapper.updateById(page);
        logService.record(LogTypeConfig.PROTECT, actorId(user), page.getTitle(), null,
                protectedPage ? "on" : "off");
        return Optional.of(page);
    }

    private static Long actorId(WikiUser user) {
        return user == null ? null : user.getId();
    }

    /** 发布页面变更事件（分类索引等派生数据监听重建）。 */
    private void publish(WikiPage page, boolean deleted) {
        if (page != null) {
            this.eventPublisher.publishEvent(
                    new WikiPageChangedEvent(page.getId(), page.getTitle(), deleted));
        }
    }

    private WikiRevision appendRevision(WikiPage page, WikiUser user, String content, String comment,
                                        LocalDateTime now, boolean bot, boolean minor, String tags) {        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent(content == null ? "" : content);
        revision.setUserId(user == null ? null : user.getId());
        revision.setComment(comment == null ? "" : comment.trim());
        revision.setDeleted(false);
        revision.setBot(bot);
        revision.setMinor(minor);
        revision.setTags(tags == null ? "" : tags.trim());
        revision.setCreateAt(now);
        wikiRevisionMapper.insert(revision);
        grantAutoConfirmIfEligible(user);
        return revision;
    }

    /** 编辑满 {@link PermissionService#AUTO_CONFIRM_EDITS} 次时自动授予「自动确定用户」（系统操作；匿名忽略）。 */
    private void grantAutoConfirmIfEligible(WikiUser user) {
        if (user == null || user.getId() == null) {
            return;
        }
        long edits = wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getUserId, user.getId()));
        this.permissionService.grantAutoConfirmIfEligible(user.getId(), edits);
    }

    private String normalizeTitle(String title) {
        WikiTitles.validate(title).ifPresent(error -> {
            throw new InvalidTitleException(error);
        });
        String normalized = WikiTitles.normalize(title);
        if (normalized.isEmpty()) {
            throw new InvalidTitleException("empty", "error.titleEmpty", "");
        }
        return normalized;
    }

    @Override
    public Optional<WikiPage> getVisibleByTitle(String title) {
        return findByTitle(title, true);
    }

    @Override
    public Optional<WikiPage> getByTitle(String title) {
        return findByTitle(title, false);
    }

    /**
     * 按标题取页面（{@code visibleOnly} = 排除已隐藏页面）：
     * <ol>
     *     <li>先用规范化标题（命名空间别名已归一到规范前缀）精确查；</li>
     *     <li>规范名没有命中时，再用别名写法回退查一次 —— 别名归一之前按英文别名落库的历史页面仍然可读
     *         （如 {@code Gadget:夜行.js}：规范名是小部件:夜行.js，历史行仍是 Gadget:夜行.js）。</li>
     * </ol>
     */
    private Optional<WikiPage> findByTitle(String title, boolean visibleOnly) {
        String normalized = WikiTitles.normalize(title);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        Optional<WikiPage> page = selectByExactTitle(normalized, visibleOnly);
        if (page.isPresent()) {
            return page;
        }
        for (String alias : WikiNamespaces.aliasVariants(normalized)) {
            Optional<WikiPage> legacy = selectByExactTitle(alias, visibleOnly);
            if (legacy.isPresent()) {
                return legacy;
            }
        }
        return Optional.empty();
    }

    /** 按标题精确查一行（{@code visibleOnly} 时排除已隐藏页面）。 */
    private Optional<WikiPage> selectByExactTitle(String title, boolean visibleOnly) {
        LambdaQueryWrapper<WikiPage> query = Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, title)
                .eq(WikiPage::getDeleted, false)
                .last("LIMIT 1");
        if (visibleOnly) {
            query = query.eq(WikiPage::getHided, false);
        }
        return Optional.ofNullable(wikiPageMapper.selectOne(query));
    }

    @Override
    public Optional<WikiRevision> getLatestRevision(Long pageId) {
        if (pageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(wikiRevisionMapper.selectOne(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, pageId)
                .eq(WikiRevision::getDeleted, false)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT 1")));
    }

    @Override
    public Optional<String> getLatestContent(Long pageId) {
        return getLatestRevision(pageId)
                .map(WikiRevision::getContent)
                .filter(content -> !content.isBlank());
    }

    @Override
    public Optional<WikiRevision> getRevision(Long revisionId) {
        return revisionId == null ? Optional.empty() : Optional.ofNullable(wikiRevisionMapper.selectById(revisionId));
    }

    @Override
    public boolean markAiAssisted(Long revisionId, String aiModel) {
        if (revisionId == null) {
            return false;
        }
        WikiRevision revision = wikiRevisionMapper.selectById(revisionId);
        if (revision == null) {
            return false;
        }
        revision.setAiAssisted(true);
        revision.setAiModel(aiModel == null ? "" : aiModel.trim());
        wikiRevisionMapper.updateById(revision);
        return true;
    }

    @Override
    public List<WikiRevision> getHistory(Long pageId) {
        if (pageId == null) {
            return List.of();
        }
        return wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, pageId)
                .orderByDesc(WikiRevision::getId));
    }

    @Override
    public Optional<WikiRevision> getPreviousRevision(Long pageId, Long revisionId) {
        if (pageId == null || revisionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(wikiRevisionMapper.selectOne(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, pageId)
                .eq(WikiRevision::getDeleted, false)
                .lt(WikiRevision::getId, revisionId)
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT 1")));
    }

    @Override
    public Optional<WikiRevision> getNextRevision(Long pageId, Long revisionId) {
        if (pageId == null || revisionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(wikiRevisionMapper.selectOne(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, pageId)
                .eq(WikiRevision::getDeleted, false)
                .gt(WikiRevision::getId, revisionId)
                .orderByAsc(WikiRevision::getId)
                .last("LIMIT 1")));
    }

    // ==================== 页面 CSS ====================

    @Override
    public List<String> getCSS(String title) {
        // 旧签名（没有请求上下文）：按站点默认皮肤取皮肤页（与 SkinRegistry 的默认皮肤一致）
        return getCSS(title, defaultSkinId());
    }

    @Override
    public List<String> getCSS(String title, String skinId) {
        List<String> styles = new ArrayList<>();

        // 1. 全站样式：Wiki.css + WikiKernel:Common.css（依次注入，顺序稳定）
        pageContentByTitle(SITE_CSS_TITLE).ifPresent(styles::add);
        pageContentByTitle(COMMON_CSS_TITLE).ifPresent(styles::add);

        // 2. 按皮肤生效的站点样式：WikiKernel:<皮肤 id>.css（不存在 / 被隐藏 / 无内容时跳过）
        String skinCssTitle = skinPageTitle(skinId, CSS_SUFFIX);
        if (skinCssTitle != null) {
            pageContentByTitle(skinCssTitle).ifPresent(styles::add);
        }

        // 3. 页面级样式：{title}/XXX.css 子页面（未隐藏、未删除），按标题排序保证注入顺序稳定
        if (Strings.isNotBlank(title)) {
            List<WikiPage> subPages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                    .likeRight(WikiPage::getTitle, title + "/")
                    .likeLeft(WikiPage::getTitle, CSS_SUFFIX)
                    .eq(WikiPage::getHided, false)
                    .eq(WikiPage::getDeleted, false)
                    .orderByAsc(WikiPage::getTitle));
            for (WikiPage page : subPages) {
                getLatestContent(page.getId()).ifPresent(styles::add);
            }
        }

        return styles;
    }

    private Optional<String> pageContentByTitle(String title) {
        return getVisibleByTitle(title).flatMap(page -> getLatestContent(page.getId()));
    }

    /**
     * 按皮肤生效的站点页标题：{@code WikiKernel:<皮肤 id>.css|.js}。
     * <p>
     * 只用皮肤 **id**（{@code Skin#id()} 的原样写法，如 {@code plain}），不认显示名；
     * 空白或含 {@code [A-Za-z0-9_-]} 之外字符时返回 {@code null}（调用方跳过、不查询）。
     * 标题本身仍由 {@link #getVisibleByTitle(String)} 走 {@code WikiTitles} 规范化（首字母自动大写等），
     * 所以 {@code WikiKernel:plain.css} 与 {@code WikiKernel:Plain.css} 命中同一个页面。
     */
    private static String skinPageTitle(String skinId, String suffix) {
        if (skinId == null) {
            return null;
        }
        String id = skinId.trim();
        if (!SKIN_ID_PATTERN.matcher(id).matches()) {
            return null;
        }
        return SKIN_PAGE_NAMESPACE_PREFIX + id + suffix;
    }

    /** 站点默认皮肤 id（没有皮肤注册表时返回 {@code null} = 不注入皮肤页）。 */
    private String defaultSkinId() {
        return skinRegistry == null ? null : skinRegistry.defaultSkinId();
    }

    // ==================== 页面 JS ====================

    @Override
    public List<String> getJS(String title) {
        // 旧签名（没有请求上下文）：按站点默认皮肤取皮肤页（与 SkinRegistry 的默认皮肤一致）
        return getJS(title, defaultSkinId());
    }

    @Override
    public List<String> getJS(String title, String skinId) {
        List<String> scripts = new ArrayList<>();

        // 1. 全站脚本：WikiKernel:Wiki.js + WikiKernel:Common.js
        if (pageContentByTitle(SITE_JS_TITLE).isPresent()) {
            scripts.add(SITE_JS_TITLE);
        }
        if (pageContentByTitle(COMMON_JS_TITLE).isPresent()) {
            scripts.add(COMMON_JS_TITLE);
        }

        // 2. 按皮肤生效的站点脚本：WikiKernel:<皮肤 id>.js（不存在 / 被隐藏 / 无内容时跳过）
        //    返回页面的**规范标题**（首字母自动大写后），保证给模板的 raw 地址与页面真实标题一致
        String skinJsTitle = skinPageTitle(skinId, JS_SUFFIX);
        if (skinJsTitle != null) {
            getVisibleByTitle(skinJsTitle)
                    .filter(page -> getLatestContent(page.getId()).isPresent())
                    .ifPresent(page -> scripts.add(page.getTitle()));
        }

        // 3. 页面级脚本：{title}/XXX.js 子页面（未隐藏、未删除），按标题排序；
        //    TypeScript 编译可用时，{title}/XXX.ts(.tsx) 子页面同样作为脚本（服务端编译后输出）
        if (Strings.isNotBlank(title)) {
            List<String> suffixes = new ArrayList<>();
            suffixes.add(JS_SUFFIX);
            if (typeScriptAssetService != null && typeScriptAssetService.enabled()) {
                suffixes.add(".ts");
                suffixes.add(".tsx");
            }
            List<WikiPage> subPages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                    .likeRight(WikiPage::getTitle, title + "/")
                    .and(wrapper -> {
                        for (int index = 0; index < suffixes.size(); index++) {
                            if (index > 0) {
                                wrapper.or();
                            }
                            wrapper.likeLeft(WikiPage::getTitle, suffixes.get(index));
                        }
                    })
                    .eq(WikiPage::getHided, false)
                    .eq(WikiPage::getDeleted, false)
                    .orderByAsc(WikiPage::getTitle));
            for (WikiPage page : subPages) {
                // 类型声明（x.d.ts 也以 .ts 结尾）没有运行时代码，不能当页面脚本加载
                if (cc.thonly.wikikernel.typescript.TypeScriptAssetService.isDeclarationPage(page.getTitle())) {
                    continue;
                }
                if (getLatestContent(page.getId()).isPresent()) {
                    scripts.add(page.getTitle());
                }
            }
        }

        return scripts;
    }
}
