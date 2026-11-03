package online.yudream.voxelith.server.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 材质包仓库：上传落盘、本机登记、坏包校验与删除。 */
class ResourcePackStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadedZipIsListedWithLayout() throws IOException {
        ResourcePackStore store = new ResourcePackStore(tempDir.resolve("packs"));
        byte[] zip = zipBytes("MyPack/pack.mcmeta", "{}",
                "MyPack/assets/minecraft/blockstates/stone.json", "{}");
        ResourcePackStore.PackInfo info =
                store.saveUpload(new ByteArrayInputStream(zip), "田园材质.zip");

        assertThat(info.managed()).isTrue();
        assertThat(info.layout()).isEqualTo("nested:MyPack");
        assertThat(store.list()).hasSize(1);
        assertThat(store.list().getFirst().name()).isEqualTo("田园材质.zip");
    }

    @Test
    void brokenZipIsRejectedAndNotKept() throws IOException {
        ResourcePackStore store = new ResourcePackStore(tempDir.resolve("packs"));
        byte[] junk = "not a zip".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.saveUpload(new ByteArrayInputStream(junk), "bad.zip"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.list()).isEmpty();
        assertThat(tempDir.resolve("packs")).isEmptyDirectory();
    }

    @Test
    void zipWithoutAssetsIsRejected() throws IOException {
        ResourcePackStore store = new ResourcePackStore(tempDir.resolve("packs"));
        byte[] zip = zipBytes("readme.txt", "只有说明文件");
        assertThatThrownBy(() -> store.saveUpload(new ByteArrayInputStream(zip), "empty.zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有找到资源");
    }

    @Test
    void backslashEntryNamesGetTheirOwnExplanation() throws IOException {
        // Windows 右键「压缩到 zip」写出的条目名是反斜杠，原版 Minecraft 也读不了——
        // 报错要与「真的缺资源」区分开，直接告诉用户重打包
        ResourcePackStore store = new ResourcePackStore(tempDir.resolve("packs"));
        byte[] zip = zipBytes("TestPack\\pack.mcmeta", "{}",
                "TestPack\\assets\\minecraft\\blockstates\\stone.json", "{}");
        assertThatThrownBy(() -> store.saveUpload(new ByteArrayInputStream(zip), "win.zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("反斜杠");
    }

    @Test
    void localDirectoryPackRequiresAssets() throws IOException {
        ResourcePackStore store = new ResourcePackStore(tempDir.resolve("packs"));
        Path packDir = Files.createDirectories(tempDir.resolve("SomePack"));
        assertThatThrownBy(() -> store.registerLocal(packDir.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assets/");

        Path assets = Files.createDirectories(packDir.resolve("assets").resolve("minecraft"));
        Files.writeString(assets.resolve("keep.txt"), "x");
        ResourcePackStore.PackInfo info = store.registerLocal(packDir.toString());
        assertThat(info.managed()).isFalse();
        assertThat(info.zip()).isFalse();
        assertThat(info.layout()).isEqualTo("directory");
    }

    @Test
    void managedPackCanBeDeletedByNameOnly() throws IOException {
        ResourcePackStore store = new ResourcePackStore(tempDir.resolve("packs"));
        byte[] zip = zipBytes("pack.mcmeta", "{}",
                "assets/minecraft/blockstates/stone.json", "{}");
        store.saveUpload(new ByteArrayInputStream(zip), "plain.zip");

        assertThat(store.deleteManaged("plain.zip")).isTrue();
        assertThat(store.list()).isEmpty();
        // 带路径成分的名字直接拒绝，防止借删除接口碰受管目录外的文件
        assertThatThrownBy(() -> store.deleteManaged("../escape.zip"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sanitizeNameKeepsFileNameOnly() {
        assertThat(ResourcePackStore.sanitizeName("a/b/c\\Pack v2.zip")).isEqualTo("Pack v2.zip");
        assertThat(ResourcePackStore.sanitizeName("no-extension")).isEqualTo("no-extension.zip");
        assertThatThrownBy(() -> ResourcePackStore.sanitizeName("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] zipBytes(String... pathAndContent) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pathAndContent.length; i += 2) {
                out.putNextEntry(new ZipEntry(pathAndContent[i]));
                out.write(pathAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
