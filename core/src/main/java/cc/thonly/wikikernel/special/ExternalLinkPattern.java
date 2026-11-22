package cc.thonly.wikikernel.special;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/** The URL patterns accepted by Special:LinkSearch. */
record ExternalLinkPattern(String scheme, String host, String path, Mode mode) {
    private static final Pattern URL = Pattern.compile(
            "^(?:(https?|ftp)://)?([^/?#\\s]+)([^\\s]*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4_PREFIX = Pattern.compile("(?:[0-9]{1,3}\\.){1,3}\\*");
    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]+(?::[0-9]+)?|\\[[0-9a-f:.]+\\](?::[0-9]+)?");
    enum Mode { ALL, DOMAIN, ADDRESS_PREFIX, EXACT }

    static ExternalLinkPattern parse(String text) {
        var url = URL.matcher(text);
        if (!url.matches()) {
            throw new IllegalArgumentException("Invalid search URL");
        }
        String scheme = url.group(1) == null ? "http" : url.group(1).toLowerCase(Locale.ROOT);
        String host = withoutCredentials(url.group(2));
        if ("*".equals(host) && url.group(1) != null) {
            return new ExternalLinkPattern(scheme, "", "", Mode.ALL);
        }
        if (IPV4_PREFIX.matcher(host).matches()) {
            return new ExternalLinkPattern(scheme, host.substring(0, host.length() - 1), "", Mode.ADDRESS_PREFIX);
        }
        if (host.startsWith("*.")) {
            return new ExternalLinkPattern(scheme, normalizeHost(host.substring(2)), "", Mode.DOMAIN);
        }
        return new ExternalLinkPattern(scheme, normalizeHost(host), url.group(3), Mode.EXACT);
    }

    boolean matches(String target) {
        var url = URL.matcher(target);
        if (!url.matches() || !scheme.equalsIgnoreCase(url.group(1))) {
            return false;
        }
        String actual;
        try {
            actual = normalizeHost(withoutCredentials(url.group(2)));
        } catch (IllegalArgumentException invalidHost) {
            return false;
        }
        return switch (mode) {
            case ALL -> true;
            case DOMAIN -> actual.equals(host) || actual.endsWith("." + host);
            case ADDRESS_PREFIX -> actual.startsWith(host) && actual.matches("[0-9.]+");
            case EXACT -> actual.equals(host) && url.group(3).startsWith(path);
        };
    }

    private static String withoutCredentials(String authority) {
        return authority.substring(authority.lastIndexOf('@') + 1);
    }

    private static String normalizeHost(String host) {
        String normal = host.toLowerCase(Locale.ROOT);
        if (!normal.startsWith("[")) {
            int port = normal.lastIndexOf(':');
            if (port >= 0) {
                normal = IDN.toASCII(normal.substring(0, port)) + normal.substring(port);
            } else {
                normal = IDN.toASCII(normal);
            }
        }
        if (!HOST.matcher(normal).matches()) {
            throw new IllegalArgumentException("Invalid host");
        }
        return normal;
    }
}
