package online.yudream.voxelith.server.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.IntTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.infrastructure.nbt.NbtWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 存档登记：压缩包解包、只传 level.dat 时在本机认领存档目录。
 */
class WorldStoreTest {

    private static final int DATA_VERSION_1_20_4 = 3700;

    @TempDir
    Path tempDir;

    private WorldStore store(Path... worldRoots) {
        return new WorldStore(tempDir.resolve("uploads"), new ObjectMapper().registerModule(new JavaTimeModule()),
                List.of(worldRoots));
    }

    // ---------- 只传 level.dat ----------

    @Test
    void locatesWorldByIdenticalLevelDat() throws IOException {
        Path world = writeWorld(tempDir.resolve("世界A"), levelDat("1.20.4", DATA_VERSION_1_20_4, "世界A"));
        byte[] uploaded = Files.readAllBytes(world.resolve("level.dat"));

        WorldUpload upload = store(tempDir).registerLevelDat(uploaded, null);

        assertThat(upload.source()).isEqualTo(WorldUpload.SOURCE_LOCAL_DIR);
        assertThat(upload.name()).isEqualTo("世界A");
        assertThat(Path.of(upload.worldDir())).isEqualTo(world.toAbsolutePath().normalize());
        assertThat(upload.versionName()).isEqualTo("1.20.4");
        assertThat(upload.dataVersion()).isEqualTo(DATA_VERSION_1_20_4);
        assertThat(upload.dimensions()).containsExactly("minecraft:overworld");
    }

    @Test
    void fallsBackToLevelNameWhenContentDiffers() throws IOException {
        // 换了机器/被游戏重写过的副本：内容对不上，只有 LevelName 还能认
        writeWorld(tempDir.resolve("世界B"), levelDat("1.20.4", DATA_VERSION_1_20_4, "世界B"));
        byte[] uploaded = levelDat("1.20.4", DATA_VERSION_1_20_4 + 1, "世界B");

        WorldUpload upload = store(tempDir).registerLevelDat(uploaded, "自己起的名字");

        assertThat(upload.name()).isEqualTo("自己起的名字");
        assertThat(upload.worldDir()).endsWith("世界B");
        assertThat(upload.source()).isEqualTo(WorldUpload.SOURCE_LOCAL_DIR);
    }

    @Test
    void fallsBackToLevelNameWhenFolderWasRenamed() throws IOException {
        // 文件夹名被改过（加了日期前缀与「存档」后缀），LevelName 只占其中一段
        Path world = writeWorld(tempDir.resolve("250323某校存档"),
                levelDat("1.21.1", 3955, "250323某校"));
        byte[] uploaded = levelDat("1.21.1", 3955 + 1, "250323某校");

        WorldUpload upload = store(tempDir).registerLevelDat(uploaded, null);

        assertThat(Path.of(upload.worldDir())).isEqualTo(world.toAbsolutePath().normalize());
    }

    @Test
    void reportsSearchedRootsWhenNothingMatches() throws IOException {
        writeWorld(tempDir.resolve("世界C"), levelDat("1.20.4", DATA_VERSION_1_20_4, "世界C"));
        byte[] uploaded = levelDat("1.20.1", 3465, "不存在的世界");

        assertThatThrownBy(() -> store(tempDir).registerLevelDat(uploaded, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没在本机找到")
                .hasMessageContaining(tempDir.toAbsolutePath().normalize().toString())
                .hasMessageContaining("1.20.1")
                .hasMessageContaining(".zip");
    }

    @Test
    void rejectsFileThatIsNotLevelDat() {
        assertThatThrownBy(() -> store(tempDir).registerLevelDat("not nbt at all".getBytes(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 level.dat");
    }

    @Test
    void reusesExistingRegistrationInsteadOfDuplicating() throws IOException {
        Path world = writeWorld(tempDir.resolve("世界D"), levelDat("1.20.4", DATA_VERSION_1_20_4, "世界D"));
        byte[] uploaded = Files.readAllBytes(world.resolve("level.dat"));
        WorldStore store = store(tempDir);

        WorldUpload first = store.registerLevelDat(uploaded, null);
        WorldUpload second = store.registerLevelDat(uploaded, null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.list()).hasSize(1);
    }

    // ---------- 上传分流 ----------

    @Test
    void uploadsBareLevelDatStream() throws IOException {
        Path world = writeWorld(tempDir.resolve("世界E"), levelDat("1.20.4", DATA_VERSION_1_20_4, "世界E"));

        try (InputStream in = Files.newInputStream(world.resolve("level.dat"))) {
            WorldUpload upload = store(tempDir).registerUpload(in, "level.dat", null);
            assertThat(upload.worldDir()).endsWith("世界E");
        }
    }

    @Test
    void unpacksArchiveWithRegions() throws IOException {
        byte[] archive = zip(Map.of(
                "存档/level.dat", levelDat("1.20.4", DATA_VERSION_1_20_4, "打包的世界"),
                "存档/region/r.0.0.mca", new byte[]{0, 0, 0, 0}));

        WorldUpload upload = store(tempDir).registerUpload(new ByteArrayInputStream(archive), "存档.zip", null);

        assertThat(upload.source()).isEqualTo(WorldUpload.SOURCE_ARCHIVE);
        assertThat(upload.name()).isEqualTo("存档");
        assertThat(upload.worldDir()).contains("world");
        assertThat(Files.isRegularFile(Path.of(upload.worldDir()).resolve("level.dat"))).isTrue();
    }

    @Test
    void archiveWithOnlyLevelDatFallsBackToLocalWorld() throws IOException {
        // 包内只有 level.dat（没带 region）：解包结果没有渲染价值，应该去本机认领真正的存档目录
        Path world = writeWorld(tempDir.resolve("世界F"), levelDat("1.20.4", DATA_VERSION_1_20_4, "世界F"));
        byte[] archive = zip(Map.of("level.dat", Files.readAllBytes(world.resolve("level.dat"))));
        WorldStore store = store(tempDir);

        WorldUpload upload = store.registerUpload(new ByteArrayInputStream(archive), "世界F.zip", null);

        assertThat(upload.source()).isEqualTo(WorldUpload.SOURCE_LOCAL_DIR);
        assertThat(Path.of(upload.worldDir())).isEqualTo(world.toAbsolutePath().normalize());
        // 解包出来的临时目录不该留下（没有 region，留着只会变成一份渲染出空地图的垃圾登记）
        try (var walk = Files.walk(tempDir.resolve("uploads"))) {
            assertThat(walk.filter(Files::isDirectory).map(p -> p.getFileName().toString()))
                    .doesNotContain("world");
        }
    }

    @Test
    void rejectsArchiveWithoutLevelDat() throws IOException {
        byte[] archive = zip(Map.of("readme.txt", "hi".getBytes()));

        assertThatThrownBy(() ->
                store(tempDir).registerUpload(new ByteArrayInputStream(archive), "杂项.zip", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没找到 level.dat");
    }

    @Test
    void rejectsUnsupportedFormat() {
        // RAR 魔数：既不是 zip 也不是 level.dat，得在分流处就被挡下并说清楚支持什么
        assertThatThrownBy(() ->
                store(tempDir).registerUpload(new ByteArrayInputStream("Rar!\u001a\u0007\u0000".getBytes()),
                        "存档.rar", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持 .zip");
    }

    // ---------- 本机目录 ----------

    @Test
    void registersLocalDirWithDisplayNameFromFolder() throws IOException {
        Path world = writeWorld(tempDir.resolve("世界G"), levelDat("1.21.1", 3955, "世界G"));

        WorldUpload upload = store().registerLocalDir(world, null);

        assertThat(upload.name()).isEqualTo("世界G");
        assertThat(upload.versionName()).isEqualTo("1.21.1");
        assertThat(upload.source()).isEqualTo(WorldUpload.SOURCE_LOCAL_DIR);
    }

    @Test
    void rejectsDirWithoutRegion() throws IOException {
        Path dir = tempDir.resolve("不是存档");
        Files.createDirectories(dir);
        Files.write(dir.resolve("level.dat"), levelDat("1.20.4", DATA_VERSION_1_20_4, "x"));

        assertThatThrownBy(() -> store().registerLocalDir(dir, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何 region 目录");
    }

    // ---------- 辅助 ----------

    /** 合成存档：level.dat + 一个空的 region 目录（登记只判目录存在，不读区块）。 */
    private static Path writeWorld(Path dir, byte[] levelDat) throws IOException {
        Files.createDirectories(dir.resolve("region"));
        Files.write(dir.resolve("level.dat"), levelDat);
        return dir;
    }

    private static byte[] levelDat(String versionName, int dataVersion, String levelName) {
        Map<String, Tag> version = new LinkedHashMap<>();
        version.put("Name", new StringTag(versionName));
        Map<String, Tag> data = new LinkedHashMap<>();
        data.put("DataVersion", new IntTag(dataVersion));
        data.put("Version", new CompoundTag(version));
        if (levelName != null) {
            data.put("LevelName", new StringTag(levelName));
        }
        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("Data", new CompoundTag(data));
        return new NbtWriter().writeNamedRoot("", new CompoundTag(root), true);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}
