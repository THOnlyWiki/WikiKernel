package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 编辑钩子的 core 侧调度：把插件的 {@link EditGuard} / {@link EditFormField} 接进编辑页与保存路径。
 * <ul>
 *     <li>{@link #beforeSave}：保存前按 {@code order()} 升序执行钩子，聚合成一个
 *     {@link Decision}（放行 / 拒绝 / 放行但提示 / 钩子已自行处理）；</li>
 *     <li>{@link #formFields} / {@link #notices}：编辑页要插入的额外表单字段与提示。</li>
 * </ul>
 * 容错：单个插件抛异常时只记警告并跳过它，页面照常渲染 / 保存（不会 500）；
 * 没有任何插件注册时两个方法都返回空，保存路径与迁移前完全一致。
 */
@Component
public class EditHookService {

    private static final Logger log = LoggerFactory.getLogger(EditHookService.class);

    private final PluginManager pluginManager;

    public EditHookService(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 编辑页要插入的表单字段 HTML（按 {@link EditFormField#order()} 升序，已过滤空白）。 */
    public List<String> formFields(EditFormContext context) {
        List<String> fields = new ArrayList<>();
        for (EditFormField field : sortedFields()) {
            try {
                String html = field.html(context);
                if (html != null && !html.isBlank()) {
                    fields.add(html);
                }
            } catch (RuntimeException exception) {
                log.warn("编辑页表单字段插件执行失败（已跳过）: {}", field.getClass().getName(), exception);
            }
        }
        return List.copyOf(fields);
    }

    /** 保存按钮附近的提示（按 {@link EditFormField#order()} 升序，已过滤空白）。 */
    public List<String> notices(EditFormContext context) {
        List<String> notices = new ArrayList<>();
        for (EditFormField field : sortedFields()) {
            try {
                String notice = field.notice(context);
                if (notice != null && !notice.isBlank()) {
                    notices.add(notice);
                }
            } catch (RuntimeException exception) {
                log.warn("编辑页提示插件执行失败（已跳过）: {}", field.getClass().getName(), exception);
            }
        }
        return List.copyOf(notices);
    }

    /**
     * 保存前钩子循环：按 {@link EditGuard#order()} 升序执行；
     * 遇到 {@code BLOCK} / {@code HANDLED} 立即停下并返回该结论，
     * {@code WARN} 累计后继续（全部放行时结果升级为 {@code WARN}）。
     */
    public Decision beforeSave(EditContext context) {
        List<String> warnings = new ArrayList<>();
        for (EditGuard guard : sortedGuards()) {
            EditGuard.Outcome outcome;
            try {
                outcome = guard.beforeSave(context);
            } catch (RuntimeException exception) {
                log.warn("编辑保存前钩子执行失败（已跳过）: {}", guard.getClass().getName(), exception);
                continue;
            }
            if (outcome == null) {
                continue;
            }
            String message = Strings.trimToEmpty(outcome.message());
            switch (outcome.result()) {
                case BLOCK -> {
                    return new Decision(EditGuard.Outcome.Result.BLOCK, message.isEmpty()
                            ? List.of() : List.of(message), "");
                }
                case HANDLED -> {
                    return new Decision(EditGuard.Outcome.Result.HANDLED, message.isEmpty()
                            ? List.of() : List.of(message), Strings.trimToEmpty(outcome.redirect()));
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
        return new Decision(warnings.isEmpty() ? EditGuard.Outcome.Result.ALLOW
                : EditGuard.Outcome.Result.WARN, List.copyOf(warnings), "");
    }

    /** 按 order 升序的编辑表单字段。 */
    private List<EditFormField> sortedFields() {
        return this.pluginManager.extensions(EditFormField.class).stream()
                .sorted(Comparator.comparingInt(EditFormField::order))
                .toList();
    }

    /** 按 order 升序的保存前钩子。 */
    private List<EditGuard> sortedGuards() {
        return this.pluginManager.extensions(EditGuard.class).stream()
                .sorted(Comparator.comparingInt(EditGuard::order))
                .toList();
    }

    /**
     * 钩子循环的最终结论。
     *
     * @param result   结论（{@code ALLOW} / {@code WARN} 都表示可以继续保存）
     * @param messages 结论附带的说明（{@code BLOCK} / {@code WARN} / {@code HANDLED} 的提示）
     * @param redirect {@code HANDLED} 时的跳转目标（站内路径；空 = 跳回页面本身）
     */
    public record Decision(EditGuard.Outcome.Result result, List<String> messages, String redirect) {

        /** 是否拒绝保存。 */
        public boolean blocked() {
            return result == EditGuard.Outcome.Result.BLOCK;
        }

        /** 钩子是否已自行处理（core 不再保存）。 */
        public boolean handled() {
            return result == EditGuard.Outcome.Result.HANDLED;
        }

        /** core 是否还要保存（{@code ALLOW} / {@code WARN}）。 */
        public boolean saving() {
            return !blocked() && !handled();
        }

        /** 第一条说明（没有时为空串）。 */
        public String message() {
            return messages.isEmpty() ? "" : messages.get(0);
        }

        /** 放行时的提示（{@code WARN} 才有）。 */
        public List<String> warnings() {
            return result == EditGuard.Outcome.Result.WARN ? messages : List.of();
        }
    }
}
