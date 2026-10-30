package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.extension.EmailType;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.service.SiteNameService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * SMTP 邮件发送实现。
 * <p>
 * 适配常见的三种组合：587 + STARTTLS（默认）、465 + SSL、内网无验证 SMTP；
 * 发送超时都设了上限，避免请求长时间挂起。
 */
@Service
public class MailServiceImpl implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MailServiceImpl.class);

    /** 旧版本固定使用的默认发件人显示名；遇到它时也回退到站点名称。 */
    private static final String LEGACY_DEFAULT_FROM_NAME = "WikiKernel";

    private final MailProperties properties;
    private final EmailTypeRegistry emailTypeRegistry;
    private final SiteNameService siteNameService;

    public MailServiceImpl(MailProperties properties, EmailTypeRegistry emailTypeRegistry,
                           SiteNameService siteNameService) {
        this.properties = properties;
        this.emailTypeRegistry = emailTypeRegistry;
        this.siteNameService = siteNameService;
    }

    @Override
    public boolean isConfigured() {
        return !Strings.isBlank(properties.getHost()) && !fromAddress().isBlank();
    }

    @Override
    public boolean isEnabled(String typeId) {
        if (!properties.isEnabled()) {
            return false;
        }
        Boolean flag = properties.getTypes().get(typeId);
        if (flag != null) {
            return flag;
        }
        return emailTypeRegistry.byId(typeId).map(EmailType::enabledByDefault).orElse(false);
    }

    @Override
    public void send(String typeId, String to, String subject, String text) {
        if (Strings.isBlank(to)) {
            throw new MailException("email.noAddress");
        }
        if (!isEnabled(typeId)) {
            throw new MailException("email.disabled");
        }
        if (!isConfigured()) {
            throw new MailException("email.notConfigured");
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(Strings.trimToEmpty(properties.getHost()));
            sender.setPort(properties.getPort());
            sender.setDefaultEncoding("UTF-8");
            if (properties.isAuth()) {
                sender.setUsername(Strings.trimToEmpty(properties.getUsername()));
                sender.setPassword(Strings.trimToEmpty(properties.getPassword()));
            }
            Properties javaMail = sender.getJavaMailProperties();
            javaMail.put("mail.transport.protocol", "smtp");
            javaMail.put("mail.smtp.auth", String.valueOf(properties.isAuth()));
            javaMail.put("mail.smtp.connectiontimeout", "10000");
            javaMail.put("mail.smtp.timeout", "15000");
            javaMail.put("mail.smtp.writetimeout", "15000");
            if (properties.isSsl()) {
                javaMail.put("mail.smtp.ssl.enable", "true");
            } else if (properties.isStarttls()) {
                javaMail.put("mail.smtp.starttls.enable", "true");
            }

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            String from = fromAddress();
            helper.setFrom(new InternetAddress(from, fromDisplayName(), "UTF-8"));
            sender.send(message);
        } catch (Exception exception) {
            log.warn("邮件发送失败（type={}, to={}, host={}）：{}", typeId, to,
                    properties.getHost(), exception.getMessage());
            throw new MailException("email.sendFailed");
        }
    }

    /** 发件人地址：配置的 from，缺省用 SMTP 用户名。 */
    private String fromAddress() {
        return Strings.isBlank(properties.getFrom())
                ? Strings.trimToEmpty(properties.getUsername())
                : Strings.trimToEmpty(properties.getFrom());
    }

    /**
     * 发件人显示名：优先使用显式配置的 {@code wikikernel.email.from-name}；
     * 留空（或仍是旧版本的默认值 {@code WikiKernel}）时跟随「站点名称」
     * （特殊:权限配置 → 站点设置 / wikikernel.site.name）。
     */
    String fromDisplayName() {
        String configured = Strings.trimToEmpty(properties.getFromName());
        if (configured.isEmpty() || LEGACY_DEFAULT_FROM_NAME.equalsIgnoreCase(configured)) {
            return siteNameService.name();
        }
        return configured;
    }
}
