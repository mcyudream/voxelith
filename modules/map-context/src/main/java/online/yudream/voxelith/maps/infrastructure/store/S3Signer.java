package online.yudream.voxelith.maps.infrastructure.store;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * AWS SigV4 签名与 S3 ListObjectsV2 XML 解析（无 SDK）。
 * 纯函数，便于单测；HTTP 发送仍由 {@link S3ObjectStore} 负责。
 */
final class S3Signer {

    static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private S3Signer() {
    }

    static String authorization(String method, URI uri, Instant now, byte[] payload,
                                String contentType, String region, String accessKey, String secretKey) {
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = sha256Hex(payload);
        boolean hasContentType = contentType != null;
        String signedHeaders = hasContentType
                ? "content-type;host;x-amz-content-sha256;x-amz-date"
                : "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n"
                + canonicalPath(uri) + "\n"
                + canonicalQuery(uri) + "\n"
                + canonicalHeaders(uri, amzDate, payloadHash, contentType) + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(secretKey, dateStamp, region), stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    static String amzDate(Instant now) {
        return AMZ_DATE.format(now);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hostHeader(URI uri) {
        int port = uri.getPort();
        if (port > 0 && port != 80 && port != 443) {
            return uri.getHost() + ":" + port;
        }
        return uri.getHost();
    }

    static String canonicalPath(URI uri) {
        String path = uri.getRawPath();
        return (path == null || path.isEmpty()) ? "/" : path;
    }

    static String canonicalQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        TreeMap<String, String> params = new TreeMap<>();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                params.put(part, "");
            } else {
                params.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    static String canonicalHeaders(URI uri, String amzDate, String payloadHash, String contentType) {
        StringBuilder sb = new StringBuilder();
        if (contentType != null) {
            sb.append("content-type:").append(contentType).append('\n');
        }
        sb.append("host:").append(hostHeader(uri)).append('\n');
        sb.append("x-amz-content-sha256:").append(payloadHash).append('\n');
        sb.append("x-amz-date:").append(amzDate).append('\n');
        return sb.toString();
    }

    static List<String> parseListKeys(String xml) {
        List<String> keys = new ArrayList<>();
        for (online.yudream.voxelith.maps.domain.ObjectStore.ObjectMeta meta : parseListEntries(xml)) {
            keys.add(meta.key());
        }
        return keys;
    }

    /**
     * ListObjectsV2 的 {@code <Contents>} 条目：Key / LastModified / ETag / Size。
     *
     * <p>ETag 是变更检测的主判据（S3 单段上传的 ETag 就是内容 MD5）。没有 SDK 时
     * 手写一个只认这几个字段的扫描器比引入 XML 依赖更省事——S3 的响应结构是稳定的。</p>
     */
    static List<online.yudream.voxelith.maps.domain.ObjectStore.ObjectMeta> parseListEntries(String xml) {
        List<online.yudream.voxelith.maps.domain.ObjectStore.ObjectMeta> entries = new ArrayList<>();
        int from = 0;
        while (true) {
            int contents = xml.indexOf("<Contents>", from);
            if (contents < 0) {
                break;
            }
            int contentsEnd = xml.indexOf("</Contents>", contents);
            if (contentsEnd < 0) {
                break;
            }
            String block = xml.substring(contents, contentsEnd);
            String key = tag(block, "Key");
            if (key != null) {
                entries.add(new online.yudream.voxelith.maps.domain.ObjectStore.ObjectMeta(
                        key,
                        parseLong(tag(block, "Size"), -1),
                        parseInstant(tag(block, "LastModified")),
                        stripQuotes(tag(block, "ETag"))));
            }
            from = contentsEnd + 11;
        }
        return entries;
    }

    private static String tag(String block, String name) {
        int start = block.indexOf("<" + name + ">");
        if (start < 0) {
            return null;
        }
        int end = block.indexOf("</" + name + ">", start);
        if (end < 0) {
            return null;
        }
        return block.substring(start + name.length() + 2, end);
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        // S3 的 ETag 形如 "abc..."：XML 里既可以写裸引号，也可能被转义成 &quot;
        String trimmed = value.trim()
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        return trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }

    private static byte[] signingKey(String secret, String dateStamp, String region) {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
