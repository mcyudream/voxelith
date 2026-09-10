package online.yudream.voxelith.maps.infrastructure.store;

import online.yudream.voxelith.maps.domain.ObjectStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * S3 兼容对象存储（AWS SigV4 + HttpURLConnection，无 AWS SDK）。
 * 适用于 AWS S3 / MinIO / Cloudflare R2；path-style 寻址 {@code /{bucket}/{key}}。
 *
 * <p>{@code endpoint} 形如 {@code https://s3.amazonaws.com} 或 {@code http://127.0.0.1:9000}。
 */
public final class S3ObjectStore implements ObjectStore {

    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;

    public S3ObjectStore(URI endpoint, String bucket, String region,
                         String accessKey, String secretKey) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        String type = contentType == null ? "application/octet-stream" : contentType;
        HttpResult result = request("PUT", objectPath(key), bytes, type, false);
        if (result.status() >= 400) {
            throw fail("PUT", key, result);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        HttpResult result = request("GET", objectPath(key), new byte[0], null, true);
        if (result.status() == 404) {
            return Optional.empty();
        }
        if (result.status() >= 400) {
            throw fail("GET", key, result);
        }
        return Optional.of(result.body());
    }

    @Override
    public boolean exists(String key) {
        HttpResult result = request("HEAD", objectPath(key), new byte[0], null, false);
        if (result.status() == 404) {
            return false;
        }
        if (result.status() >= 400) {
            throw fail("HEAD", key, result);
        }
        return true;
    }

    @Override
    public void delete(String key) {
        HttpResult result = request("DELETE", objectPath(key), new byte[0], null, false);
        if (result.status() >= 400 && result.status() != 404) {
            throw fail("DELETE", key, result);
        }
    }

    @Override
    public List<String> list(String prefix) {
        String path = "/" + bucket + "?list-type=2&prefix="
                + java.net.URLEncoder.encode(prefix, StandardCharsets.UTF_8).replace("+", "%20");
        HttpResult result = request("GET", path, new byte[0], null, true);
        if (result.status() >= 400) {
            throw fail("LIST", prefix, result);
        }
        return S3Signer.parseListKeys(result.bodyAsString());
    }

    private String objectPath(String key) {
        return "/" + bucket + "/" + key;
    }

    private HttpResult request(String method, String pathAndQuery, byte[] payload,
                               String contentType, boolean readBody) {
        Instant now = Instant.now();
        URI uri = URI.create(endpoint.toString().replaceAll("/$", "") + pathAndQuery);
        try {
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Host", S3Signer.hostHeader(uri));
            conn.setRequestProperty("x-amz-date", S3Signer.amzDate(now));
            conn.setRequestProperty("x-amz-content-sha256", S3Signer.sha256Hex(payload));
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            }
            conn.setRequestProperty("Authorization", S3Signer.authorization(
                    method, uri, now, payload, contentType, region, accessKey, secretKey));
            if ("PUT".equals(method)) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] body = new byte[0];
            if (stream != null && (readBody || status >= 400)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                stream.transferTo(buf);
                body = buf.toByteArray();
            }
            conn.disconnect();
            return new HttpResult(status, body);
        } catch (IOException e) {
            throw new UncheckedIOException("S3 请求失败: " + method + " " + pathAndQuery, e);
        }
    }

    private static UncheckedIOException fail(String op, String key, HttpResult result) {
        return new UncheckedIOException("S3 " + op + " 失败 " + result.status() + ": " + key,
                new IOException(result.bodyAsString()));
    }

    private record HttpResult(int status, byte[] body) {
        String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
