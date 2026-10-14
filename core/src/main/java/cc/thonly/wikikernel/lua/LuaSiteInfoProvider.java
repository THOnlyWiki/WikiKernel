package cc.thonly.wikikernel.lua;

import cc.thonly.wikikernel.WikiKernelInfo;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPermissionType;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUserPermission;
import cc.thonly.wikikernel.db.mapper.WikiFileMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiPermissionTypeMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserPermissionMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SiteNameService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * {@code mw.site} 的数据源：站点名 / 版本 / 服务器路径（按当前请求推导）+ 统计（30 秒缓存，避免
 * 每次 {@code #invoke} 都跑多个 COUNT 查询）。
 */
@Service
public class LuaSiteInfoProvider {

    /** 统计缓存有效期（毫秒）。 */
    private static final long STATS_TTL_MS = 30_000;

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiFileMapper wikiFileMapper;
    private final WikiUserMapper wikiUserMapper;
    private final WikiUserPermissionMapper wikiUserPermissionMapper;
    private final WikiPermissionTypeMapper wikiPermissionTypeMapper;
    private final SiteNameService siteNameService;

    private volatile Stats stats;
    private volatile long statsTime;

    public LuaSiteInfoProvider(WikiPageMapper wikiPageMapper,
                               WikiRevisionMapper wikiRevisionMapper,
                               WikiFileMapper wikiFileMapper,
                               WikiUserMapper wikiUserMapper,
                               WikiUserPermissionMapper wikiUserPermissionMapper,
                               WikiPermissionTypeMapper wikiPermissionTypeMapper,
                               SiteNameService siteNameService) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiFileMapper = wikiFileMapper;
        this.wikiUserMapper = wikiUserMapper;
        this.wikiUserPermissionMapper = wikiUserPermissionMapper;
        this.wikiPermissionTypeMapper = wikiPermissionTypeMapper;
        this.siteNameService = siteNameService;
    }

    /** 站点统计（仿 {@code mw.site.stats}；30 秒内复用同一份快照）。 */
    public record Stats(long pages, long articles, long edits, long files, long users, long admins) {
    }

    public String siteName() {
        return siteNameService.name();
    }

    public String currentVersion() {
        return WikiKernelInfo.VERSION;
    }

    /** 服务器地址（协议 + 主机 + 非默认端口）；没有请求上下文时为空串。 */
    public String server() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "";
        }
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        String host = Optional.ofNullable(request.getHeader("X-Forwarded-Host"))
                .filter(value -> !value.isBlank()).orElse(request.getServerName());
        String scheme = Optional.ofNullable(request.getHeader("X-Forwarded-Proto"))
                .filter(value -> !value.isBlank()).orElse(request.getScheme());
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /** 脚本路径（仿 MediaWiki 的 {@code scriptPath}；本站页面都在 {@code /wiki} 下）。 */
    public String scriptPath() {
        HttpServletRequest request = currentRequest();
        String context = request == null ? "" : request.getContextPath();
        return context + "/wiki";
    }

    /** 条目路径模板（仿 MediaWiki 的 {@code articlePath}）。 */
    public String articlePath() {
        return scriptPath() + "/$1";
    }

    public Stats stats() {
        Stats current = stats;
        if (current != null && System.currentTimeMillis() - statsTime < STATS_TTL_MS) {
            return current;
        }
        synchronized (this) {
            if (stats != null && System.currentTimeMillis() - statsTime < STATS_TTL_MS) {
                return stats;
            }
            stats = computeStats();
            statsTime = System.currentTimeMillis();
            return stats;
        }
    }

    private Stats computeStats() {
        long pages = wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false));
        long articles = wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .notLike(WikiPage::getTitle, "%:%"));
        long edits = wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getDeleted, false));
        long files = wikiFileMapper.selectCount(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getDeleted, false));
        long users = wikiUserMapper.selectCount(null);
        return new Stats(pages, articles, edits, files, users, countAdmins());
    }

    private long countAdmins() {
        Long typeId = Optional.ofNullable(wikiPermissionTypeMapper.selectOne(
                        Wrappers.<WikiPermissionType>lambdaQuery()
                                .eq(WikiPermissionType::getName, PermissionService.GROUP_ADMIN)
                                .last("LIMIT 1")))
                .map(WikiPermissionType::getId)
                .orElse(null);
        if (typeId == null) {
            return 0;
        }
        return wikiUserPermissionMapper.selectCount(Wrappers.<WikiUserPermission>lambdaQuery()
                .eq(WikiUserPermission::getPermissionType, typeId));
    }

    private static HttpServletRequest currentRequest() {
        try {
            Object attributes = RequestContextHolder.getRequestAttributes();
            return attributes instanceof ServletRequestAttributes servlet
                    ? servlet.getRequest() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
