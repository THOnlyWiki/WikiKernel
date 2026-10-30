package cc.thonly.wikikernel.plugin.externalauth.qq;

import cc.thonly.wikikernel.common.extension.MessageBundle;

import java.util.LinkedHashMap;
import java.util.Map;

/** QQ 邮箱绑定插件的界面文案（中 / 英）。 */
public final class QqEmailMessages implements MessageBundle {

    @Override
    public Map<String, Map<String, String>> messages() {
        Map<String, Map<String, String>> languages = new LinkedHashMap<>();
        languages.put("zh", zh());
        languages.put("en", en());
        return languages;
    }

    private static Map<String, String> zh() {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("qqEmail.title", "QQ 邮箱绑定");
        messages.put("qqEmail.hint", "填写数字 QQ 号后，我们会向该 QQ 的邮箱发送一封确认邮件；点击邮件里的链接才会完成绑定。");
        messages.put("qqEmail.emailHint", "确认邮件会发送到「数字QQ号@qq.com」。即使 QQ 邮箱设置了英文别名，也请填写数字 QQ 号。");
        messages.put("qqEmail.bound", "已绑定 QQ：{0}");
        messages.put("qqEmail.notBound", "当前账号还没有绑定 QQ。");
        messages.put("qqEmail.qqLabel", "QQ 号（只填数字）");
        messages.put("qqEmail.qqPlaceholder", "例如 123456789");
        messages.put("qqEmail.send", "发送确认邮件");
        messages.put("qqEmail.unbind", "解除绑定");
        messages.put("qqEmail.invalid", "请输入 5–12 位数字 QQ 号（不要填写邮箱别名或邮箱地址）。");
        messages.put("qqEmail.alreadyBound", "当前账号已经绑定 QQ，请先解除原绑定。");
        messages.put("qqEmail.qqTaken", "该 QQ 已经绑定其他账号。");
        messages.put("qqEmail.qqPending", "该 QQ 正在等待其他账号确认，请稍后再试。");
        messages.put("qqEmail.sendFailed", "确认邮件发送失败，请检查站点邮件配置后重试。");
        messages.put("qqEmail.sent", "确认邮件已发送，请到 QQ 邮箱查收并点击确认链接。");
        messages.put("qqEmail.rateLimited", "发送过于频繁，请 {0} 秒后再试。");
        messages.put("qqEmail.tokenInvalid", "确认链接无效或已经使用过。");
        messages.put("qqEmail.tokenExpired", "确认链接已过期，请重新发起绑定。");
        messages.put("qqEmail.wrongUser", "请使用发起绑定的账号登录后再确认。");
        messages.put("qqEmail.success", "QQ 邮箱绑定成功。");
        messages.put("qqEmail.unbound", "已解除 QQ 绑定。");
        messages.put("qqEmail.unbindFailed", "当前账号没有可解除的 QQ 绑定。");
        messages.put("email.typeName.qq-email-bind", "QQ 邮箱绑定确认");
        return messages;
    }

    private static Map<String, String> en() {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("qqEmail.title", "QQ email binding");
        messages.put("qqEmail.hint", "Enter your numeric QQ number. We will send a confirmation email to that QQ mailbox; binding completes only after you click the link.");
        messages.put("qqEmail.emailHint", "The email is sent to numeric-QQ@qq.com. Even if your QQ mailbox uses an English alias, enter the numeric QQ number here.");
        messages.put("qqEmail.bound", "Bound QQ: {0}");
        messages.put("qqEmail.notBound", "This account has no QQ binding yet.");
        messages.put("qqEmail.qqLabel", "QQ number (digits only)");
        messages.put("qqEmail.qqPlaceholder", "e.g. 123456789");
        messages.put("qqEmail.send", "Send confirmation email");
        messages.put("qqEmail.unbind", "Unbind");
        messages.put("qqEmail.invalid", "Enter a numeric QQ number with 5-12 digits (not an email alias or address).");
        messages.put("qqEmail.alreadyBound", "This account already has a QQ binding; unbind it first.");
        messages.put("qqEmail.qqTaken", "This QQ number is already bound to another account.");
        messages.put("qqEmail.qqPending", "This QQ number is awaiting confirmation for another account; try again later.");
        messages.put("qqEmail.sendFailed", "Could not send the confirmation email; check the site's mail configuration.");
        messages.put("qqEmail.sent", "Confirmation email sent. Check your QQ mailbox and click the link.");
        messages.put("qqEmail.rateLimited", "Please wait {0} seconds before trying again.");
        messages.put("qqEmail.tokenInvalid", "The confirmation link is invalid or was already used.");
        messages.put("qqEmail.tokenExpired", "The confirmation link has expired; please start again.");
        messages.put("qqEmail.wrongUser", "Log in with the account that started the binding, then confirm again.");
        messages.put("qqEmail.success", "QQ email binding completed.");
        messages.put("qqEmail.unbound", "QQ binding removed.");
        messages.put("qqEmail.unbindFailed", "There is no QQ binding to remove.");
        messages.put("email.typeName.qq-email-bind", "QQ email binding confirmation");
        return messages;
    }
}
