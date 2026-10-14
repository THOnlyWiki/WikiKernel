package cc.thonly.wikikernel.plugin.renameuser;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.renameuser.service.UserAdminStore;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 重命名用户插件的测试替身：内存版 {@link UserAdminStore} 桥、当前用户与文案源。
 * 全部不依赖 Spring 与 core（插件测试只跑插件自己的代码）。
 */
public final class RenameUserFakes {

    private RenameUserFakes() {
    }

    /** 文案源替身：返回兜底文案并按 {@link MessageFormat} 填参（与 core 的 MessageSource 行为一致）。 */
    public static MessageSource messages(Map<String, String> bundle) {
        return new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
                String text = bundle.getOrDefault(code, defaultMessage == null ? code : defaultMessage);
                return args == null || args.length == 0 ? text : MessageFormat.format(text, args);
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                return bundle.getOrDefault(code, code);
            }

            @Override
            public String getMessage(MessageSourceResolvable resolvable,
                                     Locale locale) {
                return resolvable.getDefaultMessage();
            }
        };
    }

    /** 文案源替身：直接用插件自己的中文消息包（键名与格式顺带被验证）。 */
    public static MessageSource pluginMessages() {
        return messages(new RenameUserMessages().messages().get("zh"));
    }

    /** 当前请求用户桥替身。 */
    public static CurrentUser currentUser(Viewer viewer) {
        return () -> viewer;
    }

    /** 内存版 {@link UserAdminStore} 桥。 */
    public static final class FakeUserAdminStore implements UserAdminStore {

        public final Map<Long, WikiUser> users = new LinkedHashMap<>();
        public final AtomicLong ids = new AtomicLong();
        public boolean permitted = true;
        public RenameResult nextRenameResult = RenameResult.OK;
        public final List<RenameRequest> renameRequests = new ArrayList<>();
        public List<RenameLogRow> logRows = new ArrayList<>();

        @Override
        public Optional<WikiUser> byName(String name) {
            return users.values().stream()
                    .filter(user -> user.getUsername().equals(name))
                    .findFirst();
        }

        @Override
        public Optional<WikiUser> byId(long id) {
            return Optional.ofNullable(users.get(id));
        }

        @Override
        public boolean canRename(Viewer viewer) {
            return viewer != null && viewer.loggedIn() && permitted;
        }

        @Override
        public RenameResult rename(Viewer actor, long userId, String oldName, String newName, String reason,
                                   boolean movePages) {
            renameRequests.add(new RenameRequest(actor, userId, oldName, newName, reason, movePages));
            return nextRenameResult;
        }

        @Override
        public List<RenameLogRow> recentRenames(int limit) {
            return logRows;
        }

        /** 注册一个用户（返回用户）。 */
        public WikiUser addUser(String username) {
            WikiUser user = new WikiUser();
            user.setId(ids.incrementAndGet());
            user.setUsername(username);
            users.put(user.getId(), user);
            return user;
        }

        public void addLogRow(String actorName, String targetText, String detailText) {
            logRows.add(new RenameLogRow(LocalDateTime.now(), actorName, "重命名了 ", targetText,
                    "/wiki/" + targetText, " 这个用户", detailText));
        }

        /** 一次改名请求记录。 */
        public record RenameRequest(Viewer actor, long userId, String oldName, String newName,
                                    String reason, boolean movePages) {
        }
    }
}