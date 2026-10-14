package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPermissionType;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.data.WikiUserPermission;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiFileMapper;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiPermissionTypeMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserPermissionMapper;
import cc.thonly.wikikernel.service.PermissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code 特殊:统计} / {@code Special:Statistics}：页面 / 编辑 / 文件 / 用户与管理员数量。
 */
@Component
public class StatisticsSpecialPage extends AbstractReportPage {

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiFileMapper wikiFileMapper;
    private final WikiUserMapper wikiUserMapper;
    private final WikiUserPermissionMapper wikiUserPermissionMapper;
    private final WikiPermissionTypeMapper wikiPermissionTypeMapper;

    public StatisticsSpecialPage(MessageSource messageSource,
                                 WikiPageMapper wikiPageMapper,
                                 WikiRevisionMapper wikiRevisionMapper,
                                 WikiFileMapper wikiFileMapper,
                                 WikiUserMapper wikiUserMapper,
                                 WikiUserPermissionMapper wikiUserPermissionMapper,
                                 WikiPermissionTypeMapper wikiPermissionTypeMapper) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiFileMapper = wikiFileMapper;
        this.wikiUserMapper = wikiUserMapper;
        this.wikiUserPermissionMapper = wikiUserPermissionMapper;
        this.wikiPermissionTypeMapper = wikiPermissionTypeMapper;
    }

    @Override
    public String name() {
        return "统计";
    }

    @Override
    public List<String> aliases() {
        return List.of("Statistics", "Stat");
    }

    @Override
    public String messageKey() {
        return "statistics";
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String render(SpecialPageRequest request) {
        long pages = wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false));
        long revisions = wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getDeleted, false));
        long files = wikiFileMapper.selectCount(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getDeleted, false));
        long users = wikiUserMapper.selectCount(null);
        long admins = countAdmins();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(escape(message("statistics.pages")), String.valueOf(pages)));
        rows.add(List.of(escape(message("statistics.revisions")), String.valueOf(revisions)));
        rows.add(List.of(escape(message("statistics.files")), String.valueOf(files)));
        rows.add(List.of(escape(message("statistics.users")), String.valueOf(users)));
        rows.add(List.of(escape(message("statistics.admins")), String.valueOf(admins)));
        return page(table(List.of(message("statistics.metric"), message("statistics.value")), rows));
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
}
