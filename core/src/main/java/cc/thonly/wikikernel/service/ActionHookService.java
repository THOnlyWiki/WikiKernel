package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.ActionContext;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 通用动作钩子的 core 侧调度：把插件的 {@link ActionGuard} 接进移动 / 上传 / 删除 / 封禁这些动作
 * （编辑 / 创建页面走 {@link EditHookService}）。
 * <ul>
 *     <li>{@link #beforeAction}：动作执行前按 {@code order()} 升序执行钩子，
 *     返回 {@link Decision#blocked()} 时调用方应拒绝本次动作（网页回到原页面并显示 message，
 *     API 返回 4xx + message），{@link Decision#handled()} 时调用方**不再执行动作**并当作成功返回
 *     （message 给用户看，{@link Decision#data()} 并入响应），{@link Decision#warnings()} 则回给调用方提示；</li>
 * </ul>
 * 容错：单个插件抛异常时只记警告并跳过它，动作照常执行（不会 500）；
 * 没有任何插件注册时返回 {@code ALLOW} 且没有提示，动作行为与迁移前完全一致。
 */
@Component
public class ActionHookService {

    private static final Logger log = LoggerFactory.getLogger(ActionHookService.class);

    private final PluginManager pluginManager;

    public ActionHookService(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 动作前钩子循环：按 {@link ActionGuard#order()} 升序执行；
     * 遇到 {@code BLOCK} / {@code HANDLED} 立即停下并返回该结论，{@code WARN} 累计后继续。
     */
    public Decision beforeAction(ActionContext context) {
        List<String> warnings = new ArrayList<>();
        for (ActionGuard guard : sortedGuards()) {
            ActionGuard.Outcome outcome;
            try {
                outcome = guard.beforeAction(context);
            } catch (RuntimeException exception) {
                log.warn("动作前钩子执行失败（已跳过）: {}", guard.getClass().getName(), exception);
                continue;
            }
            if (outcome == null) {
                continue;
            }
            String message = Strings.trimToEmpty(outcome.message());
            switch (outcome.result()) {
                case BLOCK -> {
                    return new Decision(ActionGuard.Outcome.Result.BLOCK,
                            message.isEmpty() ? List.of() : List.of(message), Map.of());
                }
                case HANDLED -> {
                    // 钩子已自行处理（例如审核把上传写进队列）：core 不再执行该动作
                    return new Decision(ActionGuard.Outcome.Result.HANDLED,
                            message.isEmpty() ? List.of() : List.of(message), outcome.data());
                }
                case WARN -> {
                    if (!message.isEmpty()) {
                        warnings.add(message);
                    }
                }
                case ALLOW -> {
                    // 放行，继续问下一个钩子
                }
            }
        }
        return new Decision(warnings.isEmpty() ? ActionGuard.Outcome.Result.ALLOW
                : ActionGuard.Outcome.Result.WARN, List.copyOf(warnings), Map.of());
    }

    /** 按 order 升序的动作前钩子。 */
    private List<ActionGuard> sortedGuards() {
        return this.pluginManager.extensions(ActionGuard.class).stream()
                .sorted(Comparator.comparingInt(ActionGuard::order))
                .toList();
    }

    /**
     * 钩子循环的最终结论。
     *
     * @param result   结论（{@code ALLOW} / {@code WARN} 都表示可以继续）
     * @param messages {@code BLOCK} / {@code WARN} / {@code HANDLED} 的说明（{@code BLOCK} 时只有一条）
     * @param data     {@code HANDLED} 的附加响应数据（调用方原样并入响应；其它结论为空 Map）
     */
    public record Decision(ActionGuard.Outcome.Result result, List<String> messages,
                           Map<String, Object> data) {

        public Decision {
            result = result == null ? ActionGuard.Outcome.Result.ALLOW : result;
            messages = messages == null ? List.of() : List.copyOf(messages);
            data = data == null ? Map.of() : Map.copyOf(data);
        }

        /** 是否拒绝本次动作。 */
        public boolean blocked() {
            return result == ActionGuard.Outcome.Result.BLOCK;
        }

        /** 钩子是否已自行处理（core 不再执行该动作，并当作成功回给调用方）。 */
        public boolean handled() {
            return result == ActionGuard.Outcome.Result.HANDLED;
        }

        /** core 是否还要执行该动作（{@code ALLOW} / {@code WARN}）。 */
        public boolean acting() {
            return !blocked() && !handled();
        }

        /** 第一条说明（没有时为空串）。 */
        public String message() {
            return messages.isEmpty() ? "" : messages.get(0);
        }

        /** 放行但要让调用方提示的说明（{@code WARN} 才有）。 */
        public List<String> warnings() {
            return result == ActionGuard.Outcome.Result.WARN ? messages : List.of();
        }
    }
}
