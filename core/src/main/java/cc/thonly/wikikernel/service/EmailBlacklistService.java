package cc.thonly.wikikernel.service;

/**
 * 编辑邮箱黑名单（仿 MediaWiki 的 {@code MediaWiki:Email-blacklist}，规则来自
 * {@code WikiKernel:Email-blacklist} 页面）。
 * <p>
 * 页面存在至少一条规则时，电子邮箱命中任一规则的用户不能保存页面；规则按「包含」匹配
 * （不区分大小写的正则片段，如 {@code example\.com} 匹配 {@code user@example.com}），
 * 以 {@code #} 开头的行是注释。管理员 / 行政员不受限制（避免锁死）。
 * <p>
 * 与 {@link EmailWhitelistService 编辑邮箱白名单} 同时启用时：先要求命中白名单，再要求不命中黑名单。
 */
public interface EmailBlacklistService {

    /** 黑名单是否启用（名单页存在且至少有一条规则）。 */
    boolean enabled();

    /** 邮箱是否命中黑名单（未启用时恒为 false；匿名 / 空邮箱一般不会命中，除非规则写成能匹配空串）。 */
    boolean blocked(String email);
}
