package cc.thonly.wikikernel.plugin.registrationquiz.hook;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

/**
 * 插件消息：注册问答的界面文案（**键名与迁移前 core 的 messages.properties 相同**）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 里的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * core 里 {@code auth.registerAnswerHint} / {@code auth.registerAnswerWrong} 已删除：
 * 前者用在插件渲染的表单字段里，后者是注册前钩子 {@code BLOCK} 时返回给用户的说明。
 * 设置表单自身的文案（{@code settings.registerQuiz*} / {@code setup.registrationQuiz*} /
 * {@code auth.registerQuizInvalid} 格式校验）也由插件自己的资源文件提供。
 */
public class RegistrationQuizMessages extends ResourceMessageBundle {
}
