package online.yudream.voxelith.runtime.infrastructure.provision;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.runtime.domain.LoaderKind;
import online.yudream.voxelith.runtime.domain.ProvisionedRuntime;
import online.yudream.voxelith.runtime.domain.RuntimeProvisioner;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 基于 java.net.http 的运行时 provision 实现。
 *
 * <p>缓存布局（cacheDir 下）：</p>
 * <pre>
 * mojang/version_manifest_v2.json
 * mojang/versions/&lt;mcVersion&gt;.json
 * mojang/versions/&lt;mcVersion&gt;-client.jar
 * mojang/libraries/&lt;version json 中 downloads.artifact.path&gt;
 * fabric/net/fabricmc/fabric-loader/&lt;v&gt;/fabric-loader-&lt;v&gt;.jar + -launchwrapper.json
 * fabric/&lt;launchwrapper.json 列出的加载器依赖 maven 路径&gt;
 * fabric/net/fabricmc/intermediary/&lt;mc&gt;/intermediary-&lt;mc&gt;-v2.jar
 * </pre>
 *
     * <p>已存在且 sha1 匹配的文件不重复下载；窗口/GL/音频类 LWJGL 模块被剔除（worker 以 stub 取代），
     * org.lwjgl core 与 lwjgl-stb 及其本机 natives 保留（纹理 PNG 解码与堆外内存需要真 native）。</p>
 */
public final class HttpRuntimeProvisioner implements RuntimeProvisioner {

    private static final String VERSION_MANIFEST_V2 =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";
    private static final String LAUNCHER_META_LIBRARIES = "https://libraries.minecraft.net/";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ProvisionedRuntime provision(RuntimeSpec spec, Path cacheDir) {
        if (spec.loader() != LoaderKind.FABRIC) {
            throw new IllegalArgumentException("当前仅实现 FABRIC 运行时 provision，收到: " + spec.loader());
        }
        try {
            Path mojangRoot = cacheDir.resolve("mojang");
            JsonObject versionJson = fetchVersionJson(spec.mcVersion(), mojangRoot);
            Path gameJar = downloadClient(spec.mcVersion(), versionJson, mojangRoot);
            List<Path> libraries = new ArrayList<>(downloadMcLibraries(versionJson, mojangRoot));

            Path fabricRoot = cacheDir.resolve("fabric");
            Path loaderJar = downloadFabricLoader(spec.loaderVersion(), fabricRoot, libraries);
            Path mappingsJar = downloadIntermediary(spec.mcVersion(), fabricRoot);

            return new ProvisionedRuntime(gameJar, mappingsJar, loaderJar, libraries);
        } catch (IOException e) {
            throw new IllegalStateException("运行时 provision 下载失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("运行时 provision 被中断", e);
        }
    }

    // ---- Mojang ----

    private JsonObject fetchVersionJson(String mcVersion, Path mojangRoot)
            throws IOException, InterruptedException {
        Path manifestPath = mojangRoot.resolve("version_manifest_v2.json");
        download(VERSION_MANIFEST_V2, manifestPath, null);
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            if (mcVersion.equals(v.get("id").getAsString())) {
                Path versionPath = mojangRoot.resolve("versions").resolve(mcVersion + ".json");
                download(v.get("url").getAsString(), versionPath, v.get("sha1").getAsString());
                return JsonParser.parseString(Files.readString(versionPath)).getAsJsonObject();
            }
        }
        throw new IllegalStateException("piston-meta 版本清单中不存在 MC 版本: " + mcVersion);
    }

    private Path downloadClient(String mcVersion, JsonObject versionJson, Path mojangRoot)
            throws IOException, InterruptedException {
        JsonObject client = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        Path target = mojangRoot.resolve("versions").resolve(mcVersion + "-client.jar");
        download(client.get("url").getAsString(), target, client.get("sha1").getAsString());
        return target;
    }

    /** worker 以 stub 取代的 LWJGL 模块（窗口/GL/音频等）；core 与 stb 保留真身（PNG 解码/内存管理需真 native）。 */
    private static final java.util.Set<String> STUBBED_LWJGL = java.util.Set.of(
            "lwjgl-glfw", "lwjgl-openal", "lwjgl-opengl", "lwjgl-tinyfd", "lwjgl-jemalloc");

    private List<Path> downloadMcLibraries(JsonObject versionJson, Path mojangRoot)
            throws IOException, InterruptedException {
        List<Path> result = new ArrayList<>();
        for (JsonElement e : versionJson.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            String name = lib.get("name").getAsString();
            boolean lwjgl = name.startsWith("org.lwjgl:");
            if (lwjgl && STUBBED_LWJGL.contains(name.split(":")[1])) {
                continue; // 这些模块由 worker stub 取代
            }
            if (!includeByRules(lib)) {
                continue;
            }
            JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            JsonObject artifact = downloads != null && downloads.has("artifact")
                    ? downloads.getAsJsonObject("artifact") : null;
            if (artifact != null) {
                result.add(downloadLibraryFile(artifact, name, mojangRoot));
            }
            // 保留模块的 natives（如 org.lwjgl:lwjgl 的 natives-windows）——MemoryUtil/STB 需要真 native
            if (artifact != null && lib.has("natives") && downloads.has("classifiers")) {
                JsonObject natives = lib.getAsJsonObject("natives");
                if (natives.has(currentOs())) {
                    String classifier = natives.get(currentOs()).getAsString()
                            .replace("${arch}", System.getProperty("os.arch"));
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifiers.has(classifier)) {
                        result.add(downloadLibraryFile(
                                classifiers.getAsJsonObject(classifier), name, mojangRoot));
                    }
                }
            }
        }
        return result;
    }

    private Path downloadLibraryFile(JsonObject artifact, String coords, Path mojangRoot)
            throws IOException, InterruptedException {
        String path = artifact.has("path")
                ? artifact.get("path").getAsString()
                : mavenPath(coords);
        String url = artifact.has("url")
                ? artifact.get("url").getAsString()
                : LAUNCHER_META_LIBRARIES + path;
        String sha1 = artifact.has("sha1") ? artifact.get("sha1").getAsString() : null;
        Path target = mojangRoot.resolve("libraries").resolve(path);
        download(url, target, sha1);
        return target;
    }

    private static boolean includeByRules(JsonObject lib) {
        if (!lib.has("rules")) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement e : lib.getAsJsonArray("rules")) {
            JsonObject rule = e.getAsJsonObject();
            boolean matches = !rule.has("os") || osMatches(rule.getAsJsonObject("os"));
            if (matches) {
                allowed = "allow".equals(rule.get("action").getAsString());
            }
        }
        return allowed;
    }

    private static boolean osMatches(JsonObject os) {
        if (!os.has("name")) {
            return true; // 仅有 arch/version 限制的规则视为匹配（natives 已被整体跳过）
        }
        return currentOs().equals(os.get("name").getAsString());
    }

    private static String currentOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    // ---- Fabric ----

    private Path downloadFabricLoader(String loaderVersion, Path fabricRoot, List<Path> libraries)
            throws IOException, InterruptedException {
        String base = "net/fabricmc/fabric-loader/" + loaderVersion + "/";
        Path lwJsonPath = fabricRoot.resolve(base + "fabric-loader-" + loaderVersion + "-launchwrapper.json");
        download(FABRIC_MAVEN + base + "fabric-loader-" + loaderVersion + "-launchwrapper.json", lwJsonPath, null);

        Path loaderJar = fabricRoot.resolve(base + "fabric-loader-" + loaderVersion + ".jar");
        download(FABRIC_MAVEN + base + "fabric-loader-" + loaderVersion + ".jar", loaderJar, null);

        JsonObject lw = JsonParser.parseString(Files.readString(lwJsonPath)).getAsJsonObject();
        JsonObject libs = lw.getAsJsonObject("libraries");
        for (String section : new String[]{"common", "client"}) {
            JsonArray arr = libs.has(section) ? libs.getAsJsonArray(section) : new JsonArray();
            for (JsonElement e : arr) {
                JsonObject dep = e.getAsJsonObject();
                String coords = dep.get("name").getAsString();
                if (coords.startsWith("org.lwjgl:")) {
                    continue;
                }
                String path = mavenPath(coords);
                String sha1 = dep.has("sha1") ? dep.get("sha1").getAsString() : null;
                Path target = fabricRoot.resolve(path);
                String preferred = dep.has("url") ? dep.get("url").getAsString() : MAVEN_CENTRAL;
                downloadFirstReachable(new String[]{preferred, LAUNCHER_META_LIBRARIES, MAVEN_CENTRAL},
                        path, target, sha1);
                libraries.add(target);
            }
        }
        return loaderJar;
    }

    private Path downloadIntermediary(String mcVersion, Path fabricRoot)
            throws IOException, InterruptedException {
        String path = "net/fabricmc/intermediary/" + mcVersion
                + "/intermediary-" + mcVersion + "-v2.jar";
        Path target = fabricRoot.resolve(path);
        download(FABRIC_MAVEN + path, target, null);
        return target;
    }

    /** group:artifact:version[:classifier] → maven 仓库相对路径。 */
    private static String mavenPath(String coords) {
        String[] parts = coords.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("非法 maven 坐标: " + coords);
        }
        String file = parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + ".jar";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + file;
    }

    // ---- 下载原语 ----

    /** 依次尝试多个仓库基址，首个 HTTP 200 且 sha1 匹配者胜出。 */
    private void downloadFirstReachable(String[] repoBases, String path, Path target, String sha1)
            throws IOException, InterruptedException {
        if (Files.isRegularFile(target) && (sha1 == null || sha1.equals(sha1Of(target)))) {
            return;
        }
        IOException last = null;
        for (String base : repoBases) {
            try {
                download(base + path, target, sha1);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("无可下载仓库: " + path);
    }

    private void download(String url, Path target, String sha1)
            throws IOException, InterruptedException {
        if (Files.isRegularFile(target) && (sha1 == null || sha1.equals(sha1Of(target)))) {
            return;
        }
        Files.createDirectories(target.getParent());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + response.statusCode() + " 下载失败: " + url);
        }
        if (sha1 != null && !sha1.equals(sha1Of(tmp))) {
            Files.deleteIfExists(tmp);
            throw new IOException("sha1 校验失败: " + url);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha1Of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) != -1; ) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
