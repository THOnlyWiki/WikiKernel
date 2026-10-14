package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.UserProfileContext;
import cc.thonly.wikikernel.common.extension.Viewer;

/**
 * {@link UserProfileContext} 的 core 实现：一次用户页资料卡渲染的用户信息
 * （标题里的用户名、账号是否存在、看的是不是自己、当前用户与默认头像）。
 */
public final class RequestUserProfileContext implements UserProfileContext {

    private final long userId;
    private final String username;
    private final boolean self;
    private final Viewer viewer;
    private final String defaultAvatarUrl;

    public RequestUserProfileContext(long userId, String username, boolean self, Viewer viewer,
                                     String defaultAvatarUrl) {
        this.userId = Math.max(0, userId);
        this.username = username == null ? "" : username;
        this.self = self;
        this.viewer = viewer == null ? Viewer.ANONYMOUS : viewer;
        this.defaultAvatarUrl = defaultAvatarUrl == null ? "" : defaultAvatarUrl;
    }

    @Override
    public long userId() {
        return userId;
    }

    @Override
    public String username() {
        return username;
    }

    @Override
    public boolean self() {
        return self;
    }

    @Override
    public Viewer viewer() {
        return viewer;
    }

    @Override
    public String defaultAvatarUrl() {
        return defaultAvatarUrl;
    }
}
