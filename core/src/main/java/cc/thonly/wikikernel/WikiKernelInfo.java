package cc.thonly.wikikernel;

/**
 * WikiKernel 名称与版本，供 {@code /api/version} 与 {@code 特殊:版本} 共用。
 * <p>
 * 版本号与根目录 {@code build.gradle} 的 {@code version} 保持一致。
 */
public final class WikiKernelInfo {

    public static final String NAME = "WikiKernel";
    public static final String VERSION = "0.0.1-SNAPSHOT";

    private WikiKernelInfo() {
    }
}
