package cc.thonly.wikikernel.http;

import cc.thonly.wikikernel.common.util.Strings;

/**
 * IP 地址匹配工具：IPv4 精确 / CIDR 匹配（如 {@code 203.0.113.7} 命中 {@code 203.0.113.0/24}）；
 * IPv6 仅支持精确匹配。用户核查（按 IP 查日志）与封禁（IP / CIDR 封禁）共用。
 */
public final class IpAddresses {

    private IpAddresses() {
    }

    /** IP 是否命中规则（规则可以是精确地址或 CIDR）。 */
    public static boolean matches(String ip, String rule) {
        if (Strings.isBlank(ip) || Strings.isBlank(rule)) {
            return false;
        }
        String value = ip.trim();
        String target = rule.trim();
        int slash = target.indexOf('/');
        if (slash < 0) {
            return value.equals(target);
        }
        Long candidate = ipv4ToLong(value);
        Long network = ipv4ToLong(target.substring(0, slash));
        if (candidate == null || network == null) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(target.substring(slash + 1));
        } catch (NumberFormatException exception) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (candidate & mask) == (network & mask);
    }

    /** 文本是否像 IP 地址或 CIDR（封禁目标类型判断用）。 */
    public static boolean isIpOrCidr(String text) {
        String value = Strings.trimToEmpty(text);
        if (value.isEmpty()) {
            return false;
        }
        if (value.indexOf('/') >= 0) {
            return true;
        }
        if (ipv4ToLong(value) != null) {
            return true;
        }
        return value.indexOf(':') >= 0;
    }

    private static Long ipv4ToLong(String value) {
        String[] parts = Strings.trimToEmpty(value).split("\\.");
        if (parts.length != 4) {
            return null;
        }
        long result = 0;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            result = (result << 8) | octet;
        }
        return result;
    }
}
