package online.yudream.voxelith.resource.infrastructure.pack;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源包 zip 根探测：社区材质包常把内容包在一层子目录里（PackName/assets/...），
 * 原版 jar 则 assets/ 直接在根上——两种布局都要能按同一条路径读到。
 */
class ZipResourcePackTest {

    @TempDir
    Path tempDir;

    @Test
    void rootLayoutReadsAssetsDirectly() throws IOException {
        Path zip = zip("root.zip", Map.of(
                "pack.mcmeta", "{}",
                "assets/minecraft/blockstates/stone.json", "{}",
                "assets/minecraft/textures/block/stone.png", new byte[] {1, 2, 3}));
        try (ZipResourcePack pack = new ZipResourcePack(zip, 0)) {
            assertEquals("", pack.rootPrefix());
            assertTrue(pack.blockstate(Identifier.parse("minecraft:stone")).isPresent());
            assertTrue(pack.texture(Identifier.parse("minecraft:block/stone")).isPresent());
            assertEquals(1, pack.listBlocksWithBlockstate().size());
        }
    }

    @Test
    void nestedLayoutDetectsSubfolderByPackMeta() throws IOException {
        Path zip = zip("nested.zip", Map.of(
                "MyPack/pack.mcmeta", "{}",
                "MyPack/assets/minecraft/blockstates/stone.json", "{}",
                "MyPack/assets/minecraft/textures/block/stone.png", new byte[] {9}));
        try (ZipResourcePack pack = new ZipResourcePack(zip, 0)) {
            assertEquals("MyPack/", pack.rootPrefix());
            assertTrue(pack.blockstate(Identifier.parse("minecraft:stone")).isPresent());
            assertTrue(pack.texture(Identifier.parse("minecraft:block/stone")).isPresent());
            assertEquals(1, pack.listBlocksWithBlockstate().size());
        }
    }

    @Test
    void nestedLayoutFallsBackToAssetsFolder() throws IOException {
        // 没有 pack.mcmeta（不规范但常见）：退化到「assets/ 所在的那层目录」
        Path zip = zip("no-meta.zip", Map.of(
                "MyPack/assets/minecraft/blockstates/oak_stairs.json", "{}"));
        try (ZipResourcePack pack = new ZipResourcePack(zip, 0)) {
            assertEquals("MyPack/", pack.rootPrefix());
            assertTrue(pack.blockstate(Identifier.parse("minecraft:oak_stairs")).isPresent());
        }
    }

    @Test
    void vanillaJarLayoutIgnoresDeepEntries() throws IOException {
        // 原版 jar：assets/ 在根上，另有一层 class 目录不应干扰探测
        Path zip = zip("client.zip", Map.of(
                "net/minecraft/client/Main.class", new byte[] {0},
                "assets/minecraft/blockstates/stone.json", "{}"));
        try (ZipResourcePack pack = new ZipResourcePack(zip, 0)) {
            assertEquals("", pack.rootPrefix());
            assertTrue(pack.blockstate(Identifier.parse("minecraft:stone")).isPresent());
        }
    }

    private Path zip(String name, Map<String, Object> entries) throws IOException {
        Path file = tempDir.resolve(name);
        // LinkedHashMap 保序：pack.mcmeta 先写入才能覆盖「探测先命中 pack.mcmeta」的路径
        Map<String, Object> ordered = new LinkedHashMap<>(entries);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, Object> entry : ordered.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                if (entry.getValue() instanceof byte[] bytes) {
                    out.write(bytes);
                } else {
                    out.write(((String) entry.getValue()).getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return file;
    }
}
