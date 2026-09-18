package online.yudream.voxelith.runtime.infrastructure.provision;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 下载 URL 安全策略：只允许 http/https，且拒绝 localhost、环回、私有与保留地址。
 *
 * <p>provision 会跟随 piston-meta / Fabric Maven 返回的 JSON 里的 {@code url} 字段发起请求，
 * 这些字段属于远端可控数据。若不校验，被篡改的清单就能让本机去请求自己的内网服务
 * （SSRF 面）。因此：</p>
 *
 * <ul>
 *   <li>方案只允许 http/https —— 挡住 {@code file:}、{@code jar:}、{@code data:} 等本地/伪协议；</li>
 *   <li>拒绝 URL 内嵌用户信息（{@code https://user:pass@host/}），避免凭据被带出去；
 *       主机名字面量先按名称拒绝（{@code localhost} / {@code *.local} / {@code *.internal}），
 *       再解析后逐个地址复核 —— 防止「名字正常、解析到 127.0.0.1」的绕过；</li>
 *   <li>地址层面覆盖环回、私有、链路本地、运营商级 NAT、唯一本地 IPv6、组播与保留段。</li>
 * </ul>
 */
public final class DownloadUrlPolicy {

    /** 主机名解析器（测试注入，避免依赖真实 DNS）。 */
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    public DownloadUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    DownloadUrlPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 校验下载地址，返回可安全请求的 URI。
     *
     * @throws IllegalArgumentException 方案非法、携带用户信息、缺少主机，或主机指向本机/私有/保留地址
     */
    public URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法下载 URL: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "仅允许 http/https 下载，收到: " + (scheme.isEmpty() ? "(无方案)" : scheme));
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("下载 URL 不允许内嵌用户信息: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("下载 URL 缺少主机: " + url);
        }
        // IPv6 字面量在 URI 里带方括号（如 [::1]），去掉才能正确解析与判定
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        rejectLocalHostName(host);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("下载主机无法解析: " + host, e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("下载主机无解析结果: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("下载主机指向不允许的地址（本机/私有/保留）: "
                        + host + " → " + address.getHostAddress());
            }
        }
        return uri;
    }

    /** 名称层面的快速拒绝，同时覆盖「用 localhost 别名解析到内网」的常见写法。 */
    private static void rejectLocalHostName(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                || h.endsWith(".internal") || h.endsWith(".home.arpa")) {
            throw new IllegalArgumentException("下载主机不允许指向本机/内网名称: " + host);
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()        // 0.0.0.0 / ::
                || address.isLoopbackAddress()  // 127.0.0.0/8 / ::1
                || address.isLinkLocalAddress() // 169.254.0.0/16 / fe80::/10
                || address.isSiteLocalAddress() // 10/8、172.16/12、192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (isIpv4Mapped(bytes)) {
            return isBlockedIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        }
        return (bytes[0] & 0xFE) == 0xFC; // fc00::/7 唯一本地地址
    }

    /** 标准预定义方法未覆盖的保留段（Java 没有内建判定）。 */
    private static boolean isBlockedIpv4(byte[] b) {
        int a = b[0] & 0xFF;
        int c = b[1] & 0xFF;
        int d = b[2] & 0xFF;
        if (a == 0 || a >= 240) {                       // 0/8（本网络）、240/4（含 255.255.255.255）
            return true;
        }
        if (a == 100 && c >= 64 && c <= 127) {           // 100.64/10 运营商级 NAT
            return true;
        }
        if (a == 192 && c == 0 && (d == 0 || d == 2)) {  // 192.0.0/24、192.0.2/24 TEST-NET-1
            return true;
        }
        if (a == 198 && (c == 18 || c == 19)) {          // 198.18/15 基准测试段
            return true;
        }
        if (a == 198 && c == 51 && d == 100) {           // 198.51.100/24 TEST-NET-2
            return true;
        }
        return a == 203 && c == 0 && d == 113;           // 203.0.113/24 TEST-NET-3
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }
}
