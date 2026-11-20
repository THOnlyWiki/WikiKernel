package cc.thonly.wikikernel;

/**
 * WikiKernel 项目信息，供 {@code /api/version} 与 {@code 特殊:版本} 共用。
 * <p>
 * 版本号与根目录 {@code build.gradle} 的 {@code version} 保持一致。
 */
public final class WikiKernelInfo {

    public static final String NAME = "WikiKernel";
    public static final String VERSION = "1.0.0";
    public static final String PROJECT_URL = "https://github.com/THOnlyWiki/WikiKernel";

    private WikiKernelInfo() {
    }
}
