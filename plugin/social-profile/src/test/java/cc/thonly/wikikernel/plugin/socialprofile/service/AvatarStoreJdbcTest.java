package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AvatarStoreJdbcTest {
    @TempDir Path directory;

    @Test void fakeImageContentIsRejectedBeforeAnyFileIsWritten() {
        var users = new SocialProfileFakes.FakeUsers().add(7,"甲","");
        var store = new AvatarStoreJdbc(database(1),users,directory.toString(),20);
        assertThrows(IllegalArgumentException.class,()->store.store(7,"avatar.png","image/png","<html>fake</html>".getBytes()));
        assertFalse(Files.exists(directory.resolve("avatars")));
    }

    @Test void removalRestoresDefaultAndConcurrentReplacementIsKept() throws Exception {
        var users = new SocialProfileFakes.FakeUsers().add(7,"甲","u7-old.png").defaultAvatar("/img/site.svg");
        Files.createDirectories(directory.resolve("avatars"));
        Path old = directory.resolve("avatars/u7-old.png"); Files.write(old,new byte[]{1});
        assertThrows(IllegalArgumentException.class,()->new AvatarStoreJdbc(database(0),users,directory.toString(),20).remove(7));
        assertTrue(Files.exists(old));
        assertEquals("/img/site.svg",new AvatarStoreJdbc(database(1),users,directory.toString(),20).remove(7));
        assertFalse(Files.exists(old));
    }

    private PluginDatabase database(int changed) {
        return (PluginDatabase) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PluginDatabase.class},
                (proxy,method,args)->{if(method.getName().equals("update"))return changed;throw new UnsupportedOperationException(method.getName());});
    }
}
