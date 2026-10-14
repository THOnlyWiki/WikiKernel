package cc.thonly.wikikernel.service;

/**
 * 编辑邮箱白名单（仿 音MAD维基 的 {@code MediaWiki:Email-whitelist}，规则来自
 * {@code WikiKernel:Email-whitelist} 页面）。
 * <p>
 * 页面存在至少一条规则时，只有电子邮箱与规则匹配的用户才能保存页面；规则按「包含」匹配
 * （不区分大小写的正则片段，如 {@code gmail\.com} 匹配 {@code user@gmail.com}），
 * 以 {@code #} 开头的行是注释。管理员 / 行政员不受限制（避免锁死）。
 */
public interface EmailWhitelistService {

    /** 白名单是否启用（名单页存在且至少有一条规则）。 */
    boolean enabled();

    /** 邮箱是否允许保存（未启用时恒为 true；空邮箱恒为 false）。 */
    boolean allows(String email);
}
