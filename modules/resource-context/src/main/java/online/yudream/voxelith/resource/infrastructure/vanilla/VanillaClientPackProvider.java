package online.yudream.voxelith.resource.infrastructure.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Mojang 官方版本目录客户端：按版本号下载并 sha1 校验原版客户端 jar，缓存在本地。
 * 原版客户端 jar 即"原版资源包"（内含 assets/ 全部 blockstate/model/texture）。
 */
public final class VanillaClientPackProvider {

    private static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Path cacheRoot;

    public VanillaClientPackProvider(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    /**
     * 确保指定版本客户端 jar 存在且校验通过，返回其路径。
     * 已缓存且 sha1 匹配时不重复下载。
     */
    public Path ensureClientJar(String version) {
        Path target = cacheRoot.resolve(version).resolve("client.jar");
        JsonObject clientDownload = locateClientDownload(version);
        String expectedSha1 = clientDownload.get("sha1").getAsString();

        if (Files.isRegularFile(target) && expectedSha1.equals(sha1(target))) {
            return target;
        }
        String url = clientDownload.get("url").getAsString();
        byte[] bytes = getBytes(url);
        String actualSha1 = sha1(bytes);
        if (!expectedSha1.equals(actualSha1)) {
            throw new IllegalStateException(
                    "客户端 jar sha1 校验失败: 期望 " + expectedSha1 + " 实际 " + actualSha1);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("写入客户端 jar 缓存失败: " + target, e);
        }
        return target;
    }

    private JsonObject locateClientDownload(String version) {
        JsonObject manifest = getJson(VERSION_MANIFEST_URL);
        JsonArray versions = manifest.getAsJsonArray("versions");
        for (JsonElement element : versions) {
            JsonObject entry = element.getAsJsonObject();
            if (version.equals(entry.get("id").getAsString())) {
                JsonObject versionJson = getJson(entry.get("url").getAsString());
                return versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
            }
        }
        throw new IllegalArgumentException("版本不存在于 Mojang 版本目录: " + version);
    }

    private JsonObject getJson(String url) {
        return JsonParser.parseString(new String(getBytes(url), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private byte[] getBytes(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("下载失败 HTTP " + response.statusCode() + ": " + url);
            }
            try (InputStream in = response.body()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("下载失败: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载被中断: " + url, e);
        }
    }

    private static String sha1(Path file) {
        try {
            return sha1(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + file, e);
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
