package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.WikiPermissionType;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.data.WikiUserPermission;
import cc.thonly.wikikernel.db.mapper.WikiPermissionTypeMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserPermissionMapper;
import cc.thonly.wikikernel.service.PermissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限种子与自动组回填：
 * <ul>
 *     <li>保证内置权限类型存在：用户 / 自动确定用户 / 邮箱确认用户 / 管理员 / 界面管理员 / 小部件编辑者 /
 *     行政员 / 核查员 / 评论管理员；</li>
 *     <li>旧名字迁移：把「自动确认用户」改回「自动确定用户」（保留已有授权）；</li>
 *     <li>基础组回填：给所有用户补齐「用户」；「自动确定用户」只补授予编辑满
 *     {@link PermissionService#AUTO_CONFIRM_EDITS} 次的用户（其余靠编辑时自动获得）。</li>
 * </ul>
 * 幂等；顺序排在 Auto-DDL（0）与旧数据迁移（1）之后。
 */
@Component
@Order(2)
public class PermissionTypeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionTypeSeeder.class);

    /** 旧版本用的名字，启动时迁移到 {@link PermissionService#GROUP_AUTOCONFIRMED}。 */
    private static final String LEGACY_AUTOCONFIRMED = "自动确认用户";

    /** 内置权限类型。 */
    public static final List<String> BUILT_IN_TYPES = List.of(
            PermissionService.GROUP_USER,
            PermissionService.GROUP_AUTOCONFIRMED,
            PermissionService.GROUP_EMAIL_CONFIRMED,
            PermissionService.GROUP_ADMIN,
            PermissionService.GROUP_INTERFACE_ADMIN,
            PermissionService.GROUP_WIDGET_EDITOR,
            PermissionService.GROUP_GADGET_ADMIN,
            PermissionService.GROUP_BUREAUCRAT,
            PermissionService.GROUP_CHECKUSER,
            PermissionService.GROUP_RENAME_USER,
            PermissionService.GROUP_COMMENT_ADMIN,
            PermissionService.GROUP_MODERATION_EXEMPT,
            PermissionService.GROUP_PAGE_ADMIN,
            PermissionService.GROUP_AI_AGENT,
            PermissionService.GROUP_BOT);

    private final WikiPermissionTypeMapper permissionTypeMapper;
    private final WikiUserPermissionMapper userPermissionMapper;
    private final WikiUserMapper wikiUserMapper;
    private final WikiRevisionMapper wikiRevisionMapper;

    public PermissionTypeSeeder(WikiPermissionTypeMapper permissionTypeMapper,
                                WikiUserPermissionMapper userPermissionMapper,
                                WikiUserMapper wikiUserMapper,
                                WikiRevisionMapper wikiRevisionMapper) {
        this.permissionTypeMapper = permissionTypeMapper;
        this.userPermissionMapper = userPermissionMapper;
        this.wikiUserMapper = wikiUserMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrateLegacyAutoconfirmed();
        for (String name : BUILT_IN_TYPES) {
            ensureType(name);
        }
        backfillImplicitGroups();
    }

    /** 「自动确定用户」→「自动确认用户」：没有新名字就改名，两个都在就合并授权后删旧。 */
    private void migrateLegacyAutoconfirmed() {
        WikiPermissionType legacy = findType(LEGACY_AUTOCONFIRMED);
        if (legacy == null) {
            return;
        }
        WikiPermissionType current = findType(PermissionService.GROUP_AUTOCONFIRMED);
        if (current == null) {
            legacy.setName(PermissionService.GROUP_AUTOCONFIRMED);
            permissionTypeMapper.updateById(legacy);
            log.info("权限种子：已把「{}」改名为「{}」", LEGACY_AUTOCONFIRMED,
                    PermissionService.GROUP_AUTOCONFIRMED);
            return;
        }
        userPermissionMapper.update(null, Wrappers.<WikiUserPermission>lambdaUpdate()
                .eq(WikiUserPermission::getPermissionType, legacy.getId())
                .set(WikiUserPermission::getPermissionType, current.getId()));
        permissionTypeMapper.deleteById(legacy.getId());
        log.info("权限种子：已合并旧的「{}」到「{}」", LEGACY_AUTOCONFIRMED,
                PermissionService.GROUP_AUTOCONFIRMED);
    }

    private void ensureType(String name) {
        if (findType(name) == null) {
            permissionTypeMapper.insert(new WikiPermissionType(null, name));
            log.info("权限种子：已创建内置权限类型「{}」", name);
        }
    }

    /** 所有用户自动拥有「用户」；「自动确定用户」只补授予编辑满门槛的用户（其余在编辑时自动获得）。 */
    private void backfillImplicitGroups() {
        Long userId = findTypeId(PermissionService.GROUP_USER);
        Long autoconfirmedId = findTypeId(PermissionService.GROUP_AUTOCONFIRMED);
        if (userId == null || autoconfirmedId == null) {
            return;
        }
        int granted = 0;
        for (WikiUser user : wikiUserMapper.selectList(null)) {
            granted += grantIfMissing(user.getId(), userId);
            long edits = wikiRevisionMapper.selectCount(Wrappers.<WikiRevision>lambdaQuery()
                    .eq(WikiRevision::getUserId, user.getId()));
            if (edits >= PermissionService.AUTO_CONFIRM_EDITS) {
                granted += grantIfMissing(user.getId(), autoconfirmedId);
            }
        }
        if (granted > 0) {
            log.info("权限种子：已为已有用户补齐自动组 {} 条（自动确定用户按编辑满 {} 次）", granted,
                    PermissionService.AUTO_CONFIRM_EDITS);
        }
    }

    private int grantIfMissing(Long userId, Long typeId) {
        boolean exists = userPermissionMapper.exists(Wrappers.<WikiUserPermission>lambdaQuery()
                .eq(WikiUserPermission::getUserId, userId)
                .eq(WikiUserPermission::getPermissionType, typeId));
        if (exists) {
            return 0;
        }
        WikiUserPermission permission = new WikiUserPermission();
        permission.setUserId(userId);
        permission.setPermissionType(typeId);
        userPermissionMapper.insert(permission);
        return 1;
    }

    private WikiPermissionType findType(String name) {
        return permissionTypeMapper.selectOne(Wrappers.<WikiPermissionType>lambdaQuery()
                .eq(WikiPermissionType::getName, name)
                .last("LIMIT 1"));
    }

    private Long findTypeId(String name) {
        WikiPermissionType type = findType(name);
        return type == null ? null : type.getId();
    }
}
