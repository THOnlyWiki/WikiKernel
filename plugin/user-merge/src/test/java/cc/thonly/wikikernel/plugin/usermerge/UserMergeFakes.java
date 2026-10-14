package cc.thonly.wikikernel.plugin.usermerge;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.usermerge.service.UserMergeStore;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用户合并插件的测试替身：内存版 {@link UserMergeStore} 桥、当前用户与文案源。
 * 全部不依赖 Spring 与 core（插件测试只跑插件自己的代码）。
 */
public final class UserMergeFakes {

    private UserMergeFakes() {
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
        return messages(new UserMergeMessages().messages().get("zh"));
    }

    /** 当前请求用户桥替身。 */
    public static CurrentUser currentUser(Viewer viewer) {
        return () -> viewer;
    }

    /** 内存版 {@link UserMergeStore} 桥（不真合并用户，只记录请求并返回可配置结果）。 */
    public static final class FakeUserMergeStore implements UserMergeStore {

        public final Map<String, WikiUser> users = new LinkedHashMap<>();
        public boolean permitted = true;
        public Result nextResult = Result.OK;
        public final List<MergeRequest> mergeRequests = new ArrayList<>();

        @Override
        public boolean canMerge(Viewer viewer) {
            return viewer != null && viewer.loggedIn() && permitted;
        }

        @Override
        public Result merge(Viewer performer, String sourceName, String targetName, boolean deleteSource) {
            mergeRequests.add(new MergeRequest(performer, sourceName, targetName, deleteSource));
            return nextResult;
        }

        /** 注册一个用户（返回用户）。 */
        public WikiUser addUser(String username) {
            WikiUser user = new WikiUser();
            user.setId(users.size() + 1L);
            user.setUsername(username);
            users.put(username, user);
            return user;
        }

        /** 一次合并请求记录。 */
        public record MergeRequest(Viewer performer, String sourceName, String targetName,
                                   boolean deleteSource) {
        }
    }
}