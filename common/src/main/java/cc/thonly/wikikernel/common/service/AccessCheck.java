package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;

import java.util.Optional;

/**
 * 访问控制桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 按**当前访问规则**判断某个动作能不能做（与页面 / 特殊页自己的判定完全一致），
 * 以及当前用户是否被封禁。
 * <p>
 * 插件在自己的接口（{@code @RestController}）或页面里做权限判断时应当用它，
 * 不要自己复制规则逻辑。
 */
public interface AccessCheck {

    /**
     * 某个动作是否允许。
     *
     * @param action 动作（read / edit / create / move / protect / upload / delete / purge / block…）
     * @param target 目标（页面标题 / {@code 特殊:页面名}；{@code null} = 默认规则）
     * @param viewer 当前用户（{@link Viewer#ANONYMOUS} = 匿名）
     */
    boolean can(AccessAction action, String target, Viewer viewer);

    /** 是否可以查看某页面（等价于 {@code can(READ, title, viewer)}）。 */
    default boolean canRead(String title, Viewer viewer) {
        return can(AccessAction.READ, title, viewer);
    }

    /**
     * 当前封禁提示：用户被封禁时返回给用户看的说明（{@code BlockService.notice} 的本地化文案），
     * 未封禁返回空。
     */
    Optional<String> blockNotice(Viewer viewer);
}
