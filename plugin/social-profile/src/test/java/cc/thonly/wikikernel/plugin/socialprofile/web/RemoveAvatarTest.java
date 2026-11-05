package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RemoveAvatarTest {
    @Test void avatarReadFollowsSiteDefault() {
        var users = new SocialProfileFakes.FakeUsers().add(1,"甲","").defaultAvatar("/img/custom.svg");
        var api = new AvatarApiController(new AvatarSupport(new SocialProfileFakes.FakeAvatars(),users),
                SocialProfileFakes.access(null), SocialProfileFakes.currentUser(SocialProfileFakes.viewer(1,"甲")),
                SocialProfileFakes.support(true),users);
        assertEquals("/img/custom.svg",api.current().getData().get("avatar"));
    }

    @Test void usersCanOnlyRemoveOwnAvatarWhileAdministratorsCanRemoveOthers() {
        var users = new SocialProfileFakes.FakeUsers().add(1,"甲","").add(2,"乙","");
        var storage = new SocialProfileFakes.FakeAvatars();
        storage.store(1,"a.png","image/png",new byte[]{1});
        storage.store(2,"b.png","image/png",new byte[]{2});
        AccessCheck access = new AccessCheck() {
            @Override public boolean can(AccessAction action,String title,Viewer viewer) { return viewer.inGroup("管理员"); }
            @Override public Optional<String> blockNotice(Viewer viewer) { return Optional.empty(); }
        };
        var api = new AvatarApiController(new AvatarSupport(storage,users),access,
                SocialProfileFakes.currentUser(SocialProfileFakes.viewer(1,"甲")),SocialProfileFakes.support(true),users);
        assertEquals(403,api.remove("乙").getCode());
        assertEquals(200,api.remove("甲").getCode());
        assertFalse(storage.current.containsKey(1L)); assertTrue(storage.current.containsKey(2L));
        var admin = new AvatarApiController(new AvatarSupport(storage,users),access,
                SocialProfileFakes.currentUser(SocialProfileFakes.viewer(1,"甲","管理员")),SocialProfileFakes.support(true),users);
        assertEquals(200,admin.remove("乙").getCode());
        assertFalse(storage.current.containsKey(2L));
    }
}
