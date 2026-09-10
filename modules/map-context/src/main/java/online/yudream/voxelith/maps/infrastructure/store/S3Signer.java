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
        int from = 0;
        while (true) {
            int start = xml.indexOf("<Key>", from);
            if (start < 0) {
                break;
            }
            int end = xml.indexOf("</Key>", start);
            if (end < 0) {
                break;
            }
            keys.add(xml.substring(start + 5, end));
            from = end + 6;
        }
        return keys;
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
