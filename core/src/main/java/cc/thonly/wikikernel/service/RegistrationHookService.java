package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.RegistrationFormField;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 注册钩子的 core 侧调度：把插件的 {@link RegistrationGuard} / {@link RegistrationFormField}
 * 接进注册页与 {@code POST /api/user/register}。
 * <ul>
 *     <li>{@link #beforeRegister}：创建账号前按 {@code order()} 升序执行钩子，
 *     返回 {@link Decision#blocked()} 时以 400 + 说明拒绝注册；</li>
 *     <li>{@link #formFields}：注册表单里要插入的额外字段 HTML。</li>
 * </ul>
 * 容错：单个插件抛异常时只记警告并跳过它（注册页与注册接口照常可用）；
 * 没有任何插件注册时两个方法都返回空，注册行为与迁移前完全一致。
 */
@Component
public class RegistrationHookService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationHookService.class);

    private final PluginManager pluginManager;

    public RegistrationHookService(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 注册表单要插入的字段 HTML（按 {@link RegistrationFormField#order()} 升序，已过滤空白）。 */
    public List<String> formFields(RegistrationContext context) {
        List<String> fields = new ArrayList<>();
        for (RegistrationFormField field : sortedFields()) {
            try {
                String html = field.html(context);
                if (html != null && !html.isBlank()) {
                    fields.add(html);
                }
            } catch (RuntimeException exception) {
                log.warn("注册页表单字段插件执行失败（已跳过）: {}", field.getClass().getName(), exception);
            }
        }
        return List.copyOf(fields);
    }

    /**
     * 注册前钩子循环：按 {@link RegistrationGuard#order()} 升序执行，
     * 遇到 {@code BLOCK} 立即停下并返回该结论；全部放行返回 {@code ALLOW}。
     */
    public Decision beforeRegister(RegistrationContext context) {
        for (RegistrationGuard guard : sortedGuards()) {
            RegistrationGuard.Outcome outcome;
            try {
                outcome = guard.beforeRegister(context);
            } catch (RuntimeException exception) {
                log.warn("注册前钩子执行失败（已跳过）: {}", guard.getClass().getName(), exception);
                continue;
            }
            if (outcome == null) {
                continue;
            }
            if (outcome.result() == RegistrationGuard.Outcome.Result.BLOCK) {
                return new Decision(RegistrationGuard.Outcome.Result.BLOCK,
                        Strings.trimToEmpty(outcome.message()));
            }
        }
        return new Decision(RegistrationGuard.Outcome.Result.ALLOW, "");
    }

    /** 按 order 升序的注册表单字段。 */
    private List<RegistrationFormField> sortedFields() {
        return this.pluginManager.extensions(RegistrationFormField.class).stream()
                .sorted(Comparator.comparingInt(RegistrationFormField::order))
                .toList();
    }

    /** 按 order 升序的注册前钩子。 */
    private List<RegistrationGuard> sortedGuards() {
        return this.pluginManager.extensions(RegistrationGuard.class).stream()
                .sorted(Comparator.comparingInt(RegistrationGuard::order))
                .toList();
    }

    /**
     * 钩子循环的最终结论。
     *
     * @param result  结论（{@code ALLOW} / {@code BLOCK}）
     * @param message {@code BLOCK} 时返回给用户的说明
     */
    public record Decision(RegistrationGuard.Outcome.Result result, String message) {

        public Decision {
            result = result == null ? RegistrationGuard.Outcome.Result.ALLOW : result;
            message = message == null ? "" : message;
        }

        /** 是否拒绝注册。 */
        public boolean blocked() {
            return result == RegistrationGuard.Outcome.Result.BLOCK;
        }
    }
}
