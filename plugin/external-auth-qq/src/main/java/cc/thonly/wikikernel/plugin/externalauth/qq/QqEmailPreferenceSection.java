package cc.thonly.wikikernel.plugin.externalauth.qq;

import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.plugin.externalauth.qq.service.QqEmailBindingService;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 参数设置里的「QQ 邮箱绑定」分区。
 * <p>
 * 表单由本插件自己的 {@code /api/qq-email/bind/*} 接口处理；不需要 OAuth App，
 * 用户填写数字 QQ 号后，确认邮件会发送到 {@code <QQ号>@qq.com}。
 */
public final class QqEmailPreferenceSection implements PreferenceSection {

    private final QqEmailBindingService bindings;

    public QqEmailPreferenceSection(QqEmailBindingService bindings) {
        this.bindings = bindings;
    }

    @Override
    public String id() {
        return "qq-email";
    }

    @Override
    public String labelKey() {
        return "qqEmail.title";
    }

    @Override
    public String fallbackLabel() {
        return "QQ 邮箱绑定";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public String html(PreferenceContext context) {
        if (context == null || context.viewer() == null || !context.viewer().loggedIn()) {
            return "";
        }
        Optional<QqEmailBindingService.Status> status = bindings.status(context.viewer().id());
        String boundText = status.map(value -> context.message("qqEmail.bound",
                        "已绑定 QQ：{0}").replace("{0}", value.qq()))
                .orElseGet(() -> context.message("qqEmail.notBound", "当前账号还没有绑定 QQ。"));
        String verifiedAt = status.map(QqEmailBindingService.Status::verifiedAt)
                .map(QqEmailPreferenceSection::formatTime)
                .orElse("");
        StringBuilder html = new StringBuilder(4096);
        html.append("<div class=\"qq-email-binding\" data-qq-email-binding")
                .append(" data-msg-success=\"").append(attr(context.message(
                        "qqEmail.success", "QQ 邮箱绑定成功。"))).append("\"")
                .append(" data-msg-invalid-number=\"").append(attr(context.message(
                        "qqEmail.invalid", "请输入 5–12 位数字 QQ 号。"))).append("\"")
                .append(" data-msg-invalid-token=\"").append(attr(context.message(
                        "qqEmail.tokenInvalid", "确认链接无效或已经使用过。"))).append("\"")
                .append(" data-msg-expired=\"").append(attr(context.message(
                        "qqEmail.tokenExpired", "确认链接已过期，请重新发起绑定。"))).append("\"")
                .append(" data-msg-wrong-user=\"").append(attr(context.message(
                        "qqEmail.wrongUser", "请使用发起绑定的账号登录后再确认。"))).append("\"")
                .append(" data-msg-taken=\"").append(attr(context.message(
                        "qqEmail.qqTaken", "该 QQ 已经绑定其他账号。"))).append("\"")
                .append(" data-msg-unbind-failed=\"").append(attr(context.message(
                        "qqEmail.unbindFailed", "当前账号没有可解除的 QQ 绑定。"))).append("\">");
        html.append("<p class=\"wiki-hint\">").append(esc(context.message(
                "qqEmail.hint", "填写数字 QQ 号后，我们会向该 QQ 的邮箱发送一封确认邮件；点击邮件里的链接才会完成绑定。")))
                .append("</p>");
        html.append("<p class=\"wiki-hint\">").append(esc(context.message(
                "qqEmail.emailHint", "确认邮件会发送到「数字QQ号@qq.com」。即使 QQ 邮箱设置了英文别名，也请填写数字 QQ 号。")))
                .append("</p>");
        html.append("<p><strong>").append(esc(boundText)).append("</strong>");
        if (!verifiedAt.isEmpty()) {
            html.append(" <span class=\"wiki-hint\">").append(esc(verifiedAt)).append("</span>");
        }
        html.append("</p>");
        if (status.isPresent()) {
            html.append("<button type=\"button\" class=\"wiki-button wiki-button-danger\" data-qq-unbind>")
                    .append(esc(context.message("qqEmail.unbind", "解除绑定"))).append("</button>");
        } else {
            // 注意：参数设置页本身已经是一个 <form>，这里不能再嵌套 form，
            // 否则浏览器会丢弃内层表单；用 div + 按钮，由下面的脚本发请求。
            html.append("<div class=\"wiki-form\" data-qq-form>")
                    .append("<div class=\"form-field\"><label for=\"qq-email-number\">")
                    .append(esc(context.message("qqEmail.qqLabel", "QQ 号（只填数字）"))).append("</label>")
                    .append("<input type=\"text\" id=\"qq-email-number\" data-qq-input inputmode=\"numeric\"")
                    .append(" pattern=\"[1-9][0-9]{4,11}\" maxlength=\"12\"")
                    .append(" placeholder=\"").append(attr(context.message(
                            "qqEmail.qqPlaceholder", "例如 123456789"))).append("\"/>")
                    .append("</div>")
                    .append("<button type=\"button\" class=\"wiki-button wiki-button-primary\" data-qq-send>")
                    .append(esc(context.message("qqEmail.send", "发送确认邮件"))).append("</button>")
                    .append("</div>");
        }
        html.append("<p class=\"form-message\" data-qq-message hidden></p>");
        html.append("<script>").append(script()).append("</script>");
        html.append("</div>");
        return html.toString();
    }

    private static String script() {
        return """
                (function(){
                  var root=document.querySelector('[data-qq-email-binding]');
                  if(!root){return;}
                  var message=root.querySelector('[data-qq-message]');
                  function show(text,ok){
                    if(!message){return;}
                    message.textContent=text||'';
                    message.className='form-message'+(ok?' success':'');
                    message.hidden=!text;
                  }
                  var form=root.querySelector('[data-qq-form]');
                  var send=root.querySelector('[data-qq-send]');
                  if(form&&send){
                    var qqInput=form.querySelector('[data-qq-input]');
                    if(qqInput){
                      qqInput.addEventListener('keydown',function(event){
                        if(event.key==='Enter'){event.preventDefault();send.click();}
                      });
                    }
                    send.addEventListener('click',function(){
                      var input=form.querySelector('[data-qq-input]');
                      var qq=input?input.value.trim():'';
                      if(!/^[1-9][0-9]{4,11}$/.test(qq)){
                        show(root.getAttribute('data-msg-invalid-number'),false);
                        return;
                      }
                      send.disabled=true;
                      fetch('/api/qq-email/bind/request',{
                        method:'POST',
                        headers:{
                          'Accept':'application/json',
                          'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
                          'X-Requested-With':'XMLHttpRequest'
                        },
                        body:new URLSearchParams({qq:qq}),
                        credentials:'same-origin'
                      }).then(function(response){return response.json();}).then(function(result){
                        send.disabled=false;
                        if(result&&result.code===200){show(result.message||'',true);if(input){input.value='';}}
                        else{show((result&&result.message)||'error',false);}
                      }).catch(function(){
                        send.disabled=false;
                        show('network error',false);
                      });
                    });
                  }
                  var unbind=root.querySelector('[data-qq-unbind]');
                  if(unbind){
                    unbind.addEventListener('click',function(){
                      if(!window.confirm('确定解除 QQ 绑定吗？')){return;}
                      unbind.disabled=true;
                      fetch('/api/qq-email/bind/remove',{
                        method:'POST',
                        headers:{'Accept':'application/json','X-Requested-With':'XMLHttpRequest'},
                        credentials:'same-origin'
                      }).then(function(response){return response.json();}).then(function(result){
                        if(result&&result.code===200){window.location.reload();return;}
                        show((result&&result.message)||root.getAttribute('data-msg-unbind-failed'),false);
                        unbind.disabled=false;
                      }).catch(function(){unbind.disabled=false;show('network error',false);});
                    });
                  }
                  var state=new URLSearchParams(window.location.search).get('qqBind');
                  if(state){
                    var tab=document.querySelector('.pref-tab[data-target="qq-email"]');
                    if(tab){tab.click();}
                    if(state==='success'){show(root.getAttribute('data-msg-success'),true);}
                    else if(state==='expired'){show(root.getAttribute('data-msg-expired'),false);}
                    else if(state==='wrongUser'){show(root.getAttribute('data-msg-wrong-user'),false);}
                    else if(state==='taken'){show(root.getAttribute('data-msg-taken'),false);}
                    else{show(root.getAttribute('data-msg-invalid-token'),false);}
                  }
                })();
                """;
    }

    private static String formatTime(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return value.toString().replace('T', ' ');
    }

    private static String esc(String value) {
        return attr(value);
    }

    private static String attr(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
