package cc.thonly.wikikernel.plugin.cpuguard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class LinuxCgroupLimiterTest {
    @TempDir Path temp;
    private static final long PID = 1234;
    private Path group(String quota, String cpus, String effective) throws IOException {
        Path path = Files.createDirectories(temp.resolve("app"));
        Files.writeString(path.resolve("cgroup.procs"), "1234\n");
        Files.writeString(path.resolve("cpu.max"), quota);
        Files.writeString(path.resolve("cpuset.cpus"), cpus);
        Files.writeString(path.resolve("cpuset.cpus.effective"), effective);
        return path;
    }
    private CpuGuardConfig.Options options(int percent, int cores) {
        return new CpuGuardConfig.Options(true, percent, "single-core", cores, 4);
    }
    private String read(Path group, String name) throws IOException { return Files.readString(group.resolve(name)).trim(); }
    @Test void verifiesQuotaAndEffectiveAffinityAndRestoresOriginalValues() throws Exception {
        Path group = group("max 100000", "", "0-3");
        var limiter = new LinuxCgroupLimiter(group, PID, pid -> false);
        var first = limiter.apply(options(25, 1));
        assertTrue(first.quotaActive());
        assertFalse(first.affinityActive(), "Writing cpuset alone does not prove affinity was applied");
        assertEquals("25000 100000", read(group, "cpu.max"));
        assertEquals("0", read(group, "cpuset.cpus"));
        Files.writeString(group.resolve("cpuset.cpus.effective"), "0");
        assertTrue(limiter.apply(options(25, 1)).affinityActive());
        limiter.close();
        assertEquals("max 100000", read(group, "cpu.max"));
        assertEquals("\n", Files.readString(group.resolve("cpuset.cpus")));
    }
    @Test void preservesStricterLimitsAndAdminChangesOnUnload() throws Exception {
        Path group = group("10000 100000", "0", "0");
        var limiter = new LinuxCgroupLimiter(group, PID, pid -> false);
        assertTrue(limiter.apply(options(25, 1)).quotaActive());
        assertEquals("10000 100000", read(group, "cpu.max"));
        limiter.close();
        Files.writeString(group.resolve("cpu.max"), "max 100000");
        limiter = new LinuxCgroupLimiter(group, PID, pid -> false);
        limiter.apply(options(25, 1));
        Files.writeString(group.resolve("cpu.max"), "8000 100000");
        limiter.close();
        assertEquals("8000 100000", read(group, "cpu.max"));
    }
    @Test void permissionFailureIsExplicitAndAnExistingReadOnlyQuotaIsRecognized() throws Exception {
        Path group = group("max 100000", "", "0-3");
        LinuxCgroupLimiter.Writer denied = (path, value) -> { throw new IOException("denied"); };
        var limiter = new LinuxCgroupLimiter(group, PID, pid -> false, denied);
        var result = limiter.apply(options(25, 1));
        assertFalse(result.quotaActive());
        assertFalse(result.affinityActive());
        assertTrue(result.detail().contains("not writable"));
        limiter.close();
        Files.writeString(group.resolve("cpu.max"), "50000 200000");
        Files.writeString(group.resolve("cpuset.cpus.effective"), "0");
        limiter = new LinuxCgroupLimiter(group, PID, pid -> false, denied);
        result = limiter.apply(options(25, 1));
        assertTrue(result.quotaActive());
        assertTrue(result.affinityActive());
        assertTrue(result.detail().isEmpty());
    }
    @Test void refusesSharedAndNonLeafGroupsBeforeChangingAnything() throws Exception {
        Path group = group("max 100000", "", "0-3");
        Files.writeString(group.resolve("cgroup.procs"), "1234\n9999\n");
        var limiter = new LinuxCgroupLimiter(group, PID, pid -> false);
        assertThrows(IOException.class, () -> limiter.apply(options(25, 1)));
        assertEquals("max 100000", read(group, "cpu.max"));
        Files.writeString(group.resolve("cgroup.procs"), "1234");
        Files.createDirectory(group.resolve("another-service"));
        assertThrows(IOException.class, () -> limiter.apply(options(25, 1)));
        assertEquals("max 100000", read(group, "cpu.max"));
    }
    @Test void permitsQuotaChangesAndDisablingAffinity() throws Exception {
        Path group = group("max 100000", "", "0-3");
        var limiter = new LinuxCgroupLimiter(group, PID, pid -> false);
        limiter.apply(options(20, 1));
        Files.writeString(group.resolve("cpuset.cpus.effective"), "0");
        limiter.apply(options(28, 1));
        assertEquals("28000 100000", read(group, "cpu.max"));
        assertFalse(limiter.apply(options(28, 0)).affinityActive());
        assertEquals("", read(group, "cpuset.cpus"));
        limiter.release();
        assertEquals("max 100000", read(group, "cpu.max"));
        limiter.close();
    }
    @Test void detectsCgroupMountAndRejectsRootAndPathTraversal() throws Exception {
        Path mount = Files.createDirectories(temp.resolve("mount"));
        Path leaf = Files.createDirectories(mount.resolve("tenant/app.scope"));
        Path proc = temp.resolve("cgroup"), info = temp.resolve("mountinfo");
        Files.writeString(proc, "0::/tenant/app.scope\n");
        String mountPath = mount.toString().replace(" ", "\\040");
        Files.writeString(info, "20 10 0:1 / " + mountPath + " rw - cgroup2 cgroup rw\n");
        assertEquals(leaf.toRealPath(), LinuxCgroupLimiter.discover(proc, info));
        Files.writeString(proc, "0::/\n");
        assertThrows(IOException.class, () -> LinuxCgroupLimiter.discover(proc, info));
        Files.writeString(proc, "0::/../outside\n");
        assertThrows(IOException.class, () -> LinuxCgroupLimiter.discover(proc, info));
    }
}
