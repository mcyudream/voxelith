package online.yudream.voxelith.server.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.voxelith.server.preview.WorldPreviewRenderer;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.world.LevelInfo;
import online.yudream.voxelith.world.domain.world.WorldReader;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;
import online.yudream.voxelith.world.infrastructure.nbt.NbtReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 存档仓库：把「一份可渲染的存档」登记到 {@code <upload-dir>/<id>/}。
 * 上传压缩包、上传单个 level.dat、直接指向本机目录三种来源在这里统一成同一份描述（world.json）。
 *
 * <p>目录布局：</p>
 * <pre>
 * upload-dir/
 *   └── swust-2f3a1c/
 *       ├── world.json     描述（id/名称/版本/可用维度/来源）
 *       └── world/         压缩包解包结果（LOCAL_DIR 来源无此目录）
 * </pre>
 *
 * <p>为什么不把上传的存档塞进 publish-dir：publish-dir 是「渲染产物的事实来源」，
 * 由 FileSystemMapRepository 扫 manifest.json 出地图列表；存档是输入，放进去会被当成一张空地图。</p>
 *
 * <p>为什么单独上传 level.dat 也要支持：level.dat 只有几 KB，但它是存档的身份证——
 * 里面的 DataVersion 决定要不要换模型集，LevelName 是展示名。它在哪台机器上、属于哪个目录，
 * 服务端可以自己从候选根目录里认出来（见 {@link #registerLevelDat}），
 * 于是「只丢一个 level.dat 过来」也能登记成功，省掉打几个 GB 压缩包的功夫。</p>
 */
public class WorldStore {

    private static final Logger log = LoggerFactory.getLogger(WorldStore.class);

    private static final String LEVEL_DAT = "level.dat";

    /** level.dat 正常只有几 KB（装了大型数据包也到不了 MB 级），超出这个量级说明拿错文件了。 */
    private static final int MAX_LEVEL_DAT_BYTES = 64 * 1024 * 1024;

    /** 找候选存档时的下探深度：容纳 Prism 之类启动器的 instances/<实例>/.minecraft/saves/<世界>。 */
    private static final int WORLD_SEARCH_DEPTH = 4;

    /** 候选存档数上限：避免把整个盘的存档都读一遍。 */
    private static final int WORLD_SEARCH_LIMIT = 500;

    /** 这些目录名不是存档根，且底下动辄上千个文件，搜到就直接跳过。 */
    private static final Set<String> NON_WORLD_DIRS = Set.of(
            "region", "entities", "poi", "playerdata", "data", "advancements", "stats",
            "logs", "crash-reports", "datapacks", "DIM-1", "DIM1", ".git");

    /** 维度 id → 相对存档根的 region 目录。 */
    private static final List<String[]> DIMENSION_REGION_DIRS = List.of(
            new String[]{"minecraft:overworld", "region"},
            new String[]{"minecraft:the_nether", "DIM-1/region"},
            new String[]{"minecraft:the_end", "DIM1/region"});

    private final Path uploadDir;
    private final ObjectMapper objectMapper;
    private final AnvilWorldReader reader = new AnvilWorldReader();
    private final NbtReader nbtReader = new NbtReader();
    /** 额外的存档搜索根（除了 .minecraft/saves 与已登记本机存档的上级目录）。 */
    private final List<Path> extraWorldRoots;

    public WorldStore(Path uploadDir, ObjectMapper objectMapper) {
        this(uploadDir, objectMapper, List.of());
    }

    /**
     * @param extraWorldRoots 只上传 level.dat 时用来找它所属存档的额外根目录
     */
    public WorldStore(Path uploadDir, ObjectMapper objectMapper, List<Path> extraWorldRoots) {
        this.uploadDir = uploadDir.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.extraWorldRoots = List.copyOf(extraWorldRoots);
    }

    public Path uploadDir() {
        return uploadDir;
    }

    /** 全部已登记存档（按登记时间倒序）。 */
    public List<WorldUpload> list() {
        if (!Files.isDirectory(uploadDir)) {
            return List.of();
        }
        List<WorldUpload> result = new ArrayList<>();
        try (Stream<Path> children = Files.list(uploadDir)) {
            for (Path dir : children.filter(Files::isDirectory).toList()) {
                readDescriptor(dir).ifPresent(result::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描上传目录失败: " + uploadDir, e);
        }
        result.sort(Comparator.comparing(WorldUpload::createdAt).reversed());
        return result;
    }

    public Optional<WorldUpload> find(String id) {
        if (!WorldNames.isSafeId(id)) {
            return Optional.empty();
        }
        return readDescriptor(uploadDir.resolve(id));
    }

    // ---------- 登记入口 ----------

    /**
     * 登记一份上传文件，按内容分流：
     * {@code PK} 开头当压缩包解包，gzip 开头（level.dat 的压缩形态）当存档身份证去本机认领目录。
     * 浏览器拿不到文件的原始路径，所以「认领」这件事只能靠服务端自己找——见 {@link #registerLevelDat}。
     *
     * @throws IllegalArgumentException 两种格式都不是，或 level.dat 在本机找不到归属
     */
    public WorldUpload registerUpload(InputStream upload, String fileName, String name) throws IOException {
        PushbackInputStream stream = new PushbackInputStream(upload, 8);
        byte[] head = stream.readNBytes(4);
        if (head.length == 0) {
            throw new IllegalArgumentException("上传的文件为空: " + fileName);
        }
        stream.unread(head);
        if (isZip(head)) {
            return registerArchive(stream, fileName, name);
        }
        if (isGzip(head)) {
            return registerLevelDat(readLevelDatBytes(stream, fileName), name);
        }
        throw new IllegalArgumentException("不认识的文件（" + fileName + "）：只支持 .zip 存档压缩包或 level.dat"
                + "（gzip 压缩的 NBT）。.rar / .7z 请先解压，或转成 .zip 再传。");
    }

    /**
     * 登记本机已有存档目录（不复制文件，只记路径）。
     *
     * @throws IllegalArgumentException 目录不是有效存档
     */
    public WorldUpload registerLocalDir(Path dir, String name) {
        Path world = normalizeWorldRoot(dir);
        String id = uniqueId(name == null || name.isBlank() ? world.getFileName().toString() : name);
        WorldUpload upload = describe(id, name, world, WorldUpload.SOURCE_LOCAL_DIR);
        writeDescriptor(uploadDir.resolve(id), upload);
        return upload;
    }

    /**
     * 只上传一个 level.dat：在本机把它所属的存档目录认出来，登记为 LOCAL_DIR。
     *
     * <p>判定顺序是先严后宽：先找 level.dat 与上传内容<b>逐字节相同</b>的存档（同一份文件，
     * 只是没在浏览器里带着路径过来），再退一步按 level.dat 里的 {@code LevelName} 与目录名匹配
     * （换了机器/被游戏重写过时间戳的副本只能走到这一步）。两条都不命中就报错并列出搜过的根目录，
     * 让人知道该把上级目录配进 {@code yudream.voxelith.upload.world-roots} 还是老实传压缩包。</p>
     *
     * @throws IllegalArgumentException 一个都没找到，或找到多个无法区分
     */
    public WorldUpload registerLevelDat(byte[] levelDat, String name) {
        LevelDatMeta meta = readLevelDatMeta(levelDat);
        List<Path> roots = candidateWorldRoots();
        List<Path> sameContent = new ArrayList<>();
        List<Path> sameName = new ArrayList<>();
        for (Path root : roots) {
            for (Path world : findWorldDirs(root)) {
                if (sameContent(world, levelDat)) {
                    sameContent.add(world);
                } else if (meta.sameWorldName(world.getFileName().toString())) {
                    sameName.add(world);
                }
            }
        }
        List<Path> matched = sameContent.isEmpty() ? sameName : sameContent;
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("没在本机找到这份 level.dat 所属的存档（"
                    + meta.describe() + "）。已搜索：" + roots.stream().map(Path::toString).toList()
                    + "。如果存档在别的机器上，请把整个存档目录（含 region/）打成 .zip 上传；"
                    + "如果在本机，可以用「本机目录」直接填路径，或把它的上级目录加进 "
                    + "yudream.voxelith.upload.world-roots 后再传一次。");
        }
        if (matched.size() > 1) {
            throw new IllegalArgumentException("找到 " + matched.size()
                    + " 个 level.dat 相同的存档，无法判断是哪一个，请用「本机目录」指定其一："
                    + matched.stream().map(Path::toString).toList());
        }
        return registerOrReuse(matched.get(0), name);
    }

    /** 删除登记（LOCAL_DIR 只删描述，不动用户的存档目录）。 */
    public boolean delete(String id) {
        Optional<WorldUpload> upload = find(id);
        if (upload.isEmpty()) {
            return false;
        }
        deleteRecursively(uploadDir.resolve(id));
        return true;
    }

    // ---------- 压缩包 ----------

    /**
     * 解包到 {@code <upload-dir>/<id>/world/}，自动跳过压缩包自带的外壳目录。
     *
     * <p>包内只有 level.dat（没带 region）时解包结果没有任何渲染价值，于是清掉它，
     * 拿这个 level.dat 去本机认领真正的存档目录——「压缩包里只有一个 level.dat」和
     * 「直接传一个 level.dat」在语义上本来就该是同一件事。</p>
     *
     * @throws IllegalArgumentException 解包后既没有 level.dat 也没有 region 目录
     */
    private WorldUpload registerArchive(InputStream archive, String fileName, String name) {
        String id = uniqueId(stripExtension(fileName));
        Path target = uploadDir.resolve(id);
        Path worldDir = target.resolve("world");
        Path extractedLevelDat = null;
        try {
            Files.createDirectories(worldDir);
            unzip(archive, worldDir);
            // 压缩包可能多包了几层目录，找有 region 的那一层当存档根
            List<Path> roots = findWorldDirs(worldDir);
            Optional<Path> root = roots.stream().filter(WorldStore::hasRegionDir).findFirst()
                    .or(() -> roots.stream().findFirst());
            if (root.isPresent() && hasRegionDir(root.get())) {
                WorldUpload upload = describe(id, name, root.get(), WorldUpload.SOURCE_ARCHIVE);
                writeDescriptor(target, upload);
                return upload;
            }
            extractedLevelDat = root.map(dir -> dir.resolve(LEVEL_DAT)).orElse(null);
        } catch (IOException e) {
            // 半成品目录留着不会出现在列表里（没有描述文件），却会挤占 id 与磁盘——解包失败就清干净
            deleteRecursively(target);
            throw new UncheckedIOException("解包存档失败: " + fileName, e);
        }
        if (extractedLevelDat == null) {
            deleteRecursively(target);
            throw new IllegalArgumentException("压缩包里没找到 level.dat: " + fileName);
        }
        byte[] levelDat;
        try {
            levelDat = readLevelDatBytes(Files.newInputStream(extractedLevelDat), fileName);
        } catch (IOException e) {
            throw new UncheckedIOException("读取解包出的 level.dat 失败: " + fileName, e);
        } finally {
            deleteRecursively(target);
        }
        log.info("压缩包 {} 内只有 level.dat，改按它在本机认领存档目录", fileName);
        return registerLevelDat(levelDat, name);
    }

    /**
     * 解包：拒绝目录穿越条目（zip slip），跳过 macOS 元数据目录。
     * 单条上限按 {@code ZipEntry.getSize()} 不做限制——存档里的区块本就可能很大，
     * 真正的保护是「只解到 upload-dir 之内」。
     *
     * <p>zip 条目名的编码没有统一标准：中文 Windows 资源管理器（以及本机 libarchive/bsdtar）
     * 按 ANSI 码页（GBK）写名字且不带 UTF-8 标志位，新式工具写 UTF-8 并置标志位。
     * JDK 的 ZipInputStream 对<b>带 UTF-8 标志位</b>的条目一律按 UTF-8 解（与构造 charset
     * 无关），<b>不带标志位</b>的条目才用构造 charset——因此把构造 charset 配成系统
     * ANSI 码页（中文 Windows = GBK）：Explorer 的名字按 GBK 解对，UTF-8 的名字由标志位
     * 兜住。若按默认 UTF-8 构造，GBK 名字会在第一个中文条目就抛 malformed input
     * （网页上传表现为 400「Input length = 1」，整个存档传不进来）。</p>
     */
    private static void unzip(InputStream archive, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Charset ansiNames = Charset.forName(
                System.getProperty("sun.jnu.encoding", "GBK"), StandardCharsets.UTF_8);
        try (ZipInputStream zip = new ZipInputStream(archive, ansiNames)) {
            ZipEntry entry;
            byte[] buffer = new byte[1 << 16];
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store")) {
                    continue;
                }
                Path resolved = root.resolve(entryName).normalize();
                if (!resolved.startsWith(root)) {
                    throw new IOException("压缩包内含非法路径条目: " + entryName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Files.createDirectories(resolved.getParent());
                try (var out = Files.newOutputStream(resolved)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    // ---------- level.dat 认领 ----------

    /** level.dat 里与「这是哪个存档」相关的字段。 */
    private record LevelDatMeta(String versionName, int dataVersion, String levelName) {

        /**
         * 目录名与 LevelName 对得上：换了机器、时间戳被游戏重写过的副本只能靠这一条认。
         * 除了全等，也认「一边包含另一边」——存档文件夹常被改名（加日期前缀、补个『存档』后缀），
         * 而 LevelName 是建世界时输入的，往往只占文件夹名的一段。
         */
        boolean sameWorldName(String dirName) {
            if (levelName == null || levelName.length() < 2 || dirName.length() < 2) {
                return false;
            }
            String folder = dirName.toLowerCase();
            String world = levelName.toLowerCase();
            return folder.equals(world) || folder.contains(world) || world.contains(folder);
        }

        String describe() {
            return "版本 " + versionName + " / DataVersion " + dataVersion
                    + (levelName == null || levelName.isBlank() ? "" : " / 世界名 " + levelName);
        }
    }

    private LevelDatMeta readLevelDatMeta(byte[] levelDat) {
        CompoundTag root;
        try {
            root = nbtReader.readNamedRootAuto(levelDat);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("这个文件不是 level.dat（NBT 解析失败）：" + e.getMessage(), e);
        }
        if (!root.contains("Data")) {
            throw new IllegalArgumentException("这个文件不是 level.dat（根标签里没有 Data）");
        }
        CompoundTag data = root.getCompound("Data");
        CompoundTag version = data.contains("Version") ? data.getCompound("Version") : CompoundTag.empty();
        return new LevelDatMeta(
                version.getStringOrDefault("Name", "unknown"),
                data.getIntOrDefault("DataVersion", 0),
                data.getStringOrDefault("LevelName", null));
    }

    /**
     * 找 level.dat 的候选根目录：显式配置的 → 常见 saves 目录 → 已登记本机存档的上级目录。
     * 最后一条是关键——存档通常并排放在同一个文件夹里，登记过一个就能顺着找到其余。
     */
    private List<Path> candidateWorldRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        addRoots(roots, extraWorldRoots);
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            addRoots(roots, List.of(Path.of(appData, ".minecraft", "saves")));
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            addRoots(roots, List.of(
                    Path.of(home, ".minecraft", "saves"),
                    Path.of(home, "AppData", "Roaming", ".minecraft", "saves")));
        }
        for (WorldUpload uploaded : list()) {
            if (WorldUpload.SOURCE_LOCAL_DIR.equals(uploaded.source())) {
                Path parent = Path.of(uploaded.worldDir()).getParent();
                if (parent != null) {
                    addRoots(roots, List.of(parent));
                }
            }
        }
        return List.copyOf(roots);
    }

    /** 只留真实存在的目录；{@code ~} 开头的写成用户目录（配置文件里好写）。 */
    private static void addRoots(Set<Path> roots, List<Path> candidates) {
        String home = System.getProperty("user.home");
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String raw = candidate.toString();
            if (raw.startsWith("~") && home != null && !home.isBlank()) {
                candidate = Path.of(home + raw.substring(1));
            }
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                roots.add(normalized);
            }
        }
    }

    /**
     * 在根目录下找「含 level.dat 的目录」。命中一个就不再下探——存档内部只有 region 等大目录，
     * 继续走既没意义又慢（region 目录动辄上千个文件）。
     */
    private static List<Path> findWorldDirs(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), WORLD_SEARCH_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root) && NON_WORLD_DIRS.contains(dir.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (Files.isRegularFile(dir.resolve(LEVEL_DAT))) {
                                found.add(dir);
                                return found.size() >= WORLD_SEARCH_LIMIT
                                        ? FileVisitResult.TERMINATE : FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            // 没权限的目录不该让整次查找失败：跳过它继续找别的
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描候选存档目录失败: {}", root, e);
        }
        return found;
    }

    /**
     * 逐字节比对 level.dat。先比长度，省掉读大文件；不能只比大小——游戏每次存档都会重写
     * level.dat（里面有时间戳），同尺寸不同内容很常见。
     */
    private static boolean sameContent(Path worldDir, byte[] levelDat) {
        try {
            Path file = worldDir.resolve(LEVEL_DAT);
            return Files.size(file) == levelDat.length && Arrays.equals(Files.readAllBytes(file), levelDat);
        } catch (IOException e) {
            return false;
        }
    }

    /** 同一个存档重复登记只会让列表变脏（只传 level.dat 时很容易撞上），已有登记直接复用。 */
    private WorldUpload registerOrReuse(Path worldDir, String name) {
        Path normalized = worldDir.toAbsolutePath().normalize();
        for (WorldUpload existing : list()) {
            if (Path.of(existing.worldDir()).toAbsolutePath().normalize().equals(normalized)) {
                return existing;
            }
        }
        return registerLocalDir(normalized, name);
    }

    private static byte[] readLevelDatBytes(InputStream stream, String fileName) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_LEVEL_DAT_BYTES + 1);
        if (bytes.length > MAX_LEVEL_DAT_BYTES) {
            throw new IllegalArgumentException("文件太大（>64MB），不像是 level.dat: " + fileName);
        }
        return bytes;
    }

    private static boolean isZip(byte[] head) {
        return head.length >= 2 && head[0] == 'P' && head[1] == 'K';
    }

    private static boolean isGzip(byte[] head) {
        return head.length >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
    }

    // ---------- 存档描述 ----------

    /** 解析存档根：压缩包可能自带一层外壳目录，向下找 level.dat 所在目录。 */
    private Path normalizeWorldRoot(Path candidate) {
        return findWorldRoot(candidate).orElseThrow(
                () -> new IllegalArgumentException("不是有效的存档目录（找不到 level.dat）: " + candidate));
    }

    private Optional<Path> findWorldRoot(Path candidate) {
        Path dir = candidate.toAbsolutePath().normalize();
        if (Files.isRegularFile(dir.resolve(LEVEL_DAT))) {
            return Optional.of(dir);
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (Files.isRegularFile(child.resolve(LEVEL_DAT))) {
                    return Optional.of(child);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取目录失败: " + dir, e);
        }
        return Optional.empty();
    }

    private WorldUpload describe(String id, String name, Path worldDir, String source) {
        LevelInfo level;
        try {
            level = reader.readLevelInfo(worldDir);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("读取 level.dat 失败: " + e.getMessage(), e);
        }
        List<String> dimensions = new ArrayList<>();
        for (String[] dimension : DIMENSION_REGION_DIRS) {
            if (Files.isDirectory(worldDir.resolve(dimension[1]))) {
                dimensions.add(dimension[0]);
            }
        }
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("存档内没有任何 region 目录: " + worldDir);
        }
        String display = name == null || name.isBlank()
                ? worldDir.getFileName().toString() : name.trim();
        return new WorldUpload(id, display, worldDir.toString(),
                level.versionName(), level.dataVersion(), dimensions, source, Instant.now());
    }

    private static boolean hasRegionDir(Path worldDir) {
        for (String[] dimension : DIMENSION_REGION_DIRS) {
            if (Files.isDirectory(worldDir.resolve(dimension[1]))) {
                return true;
            }
        }
        return false;
    }

    private Optional<WorldUpload> readDescriptor(Path dir) {
        Path file = dir.resolve("world.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), WorldUpload.class));
        } catch (IOException e) {
            log.warn("损坏的存档描述，已忽略: {}", file);
            return Optional.empty();
        }
    }

    private void writeDescriptor(Path dir, WorldUpload upload) {
        try {
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("world.json").toFile(), upload);
        } catch (IOException e) {
            throw new UncheckedIOException("写存档描述失败: " + dir, e);
        }
    }

    /**
     * 目录名（= id）：名称折成安全片段 + 短哈希。
     * 判重看描述文件而不是目录本身——上次登记中途失败留下的空目录不该让新登记被迫改名。
     */
    private String uniqueId(String name) {
        String base = WorldNames.slug(name, "world");
        String hash = WorldNames.shortHash(name);
        String id = base + "-" + hash;
        int suffix = 1;
        while (readDescriptor(uploadDir.resolve(id)).isPresent()
                || Files.exists(uploadDir.resolve(id).resolve("world"))) {
            id = base + "-" + hash + "-" + suffix++;
        }
        return id;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除上传产物失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("删除目录失败: {}", dir, e);
        }
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "world";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ---------- 只读查询 ----------

    /** 供预览服务使用的维度目录解析（与 scan/bake 一致）。 */
    public static Path dimensionDir(WorldUpload upload, String dimension) {
        return WorldPreviewRenderer.dimensionDir(Path.of(upload.worldDir()), dimension);
    }

    /**
     * 维度规模统计（只读 region 头表，不解析区块 NBT，十万级区块也能秒级返回）。
     *
     * @param regionCount 有内容的 region 数
     * @param chunkCount  非空区块数
     * @param blockMinX..blockMaxZ 有内容区块的方块包围盒（含端点，按区块对齐）
     */
    public record DimensionStats(int regionCount, long chunkCount,
                                 int blockMinX, int blockMaxX, int blockMinZ, int blockMaxZ) {
    }

    public DimensionStats stats(WorldUpload upload, String dimension) {
        Map<RegionPos, List<WorldReader.ChunkRef>> regions =
                reader.scanRegions(dimensionDir(upload, dimension));
        long chunkCount = 0;
        int minChunkX = Integer.MAX_VALUE, maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE, maxChunkZ = Integer.MIN_VALUE;
        for (List<WorldReader.ChunkRef> refs : regions.values()) {
            for (WorldReader.ChunkRef ref : refs) {
                chunkCount++;
                minChunkX = Math.min(minChunkX, ref.pos().x());
                maxChunkX = Math.max(maxChunkX, ref.pos().x());
                minChunkZ = Math.min(minChunkZ, ref.pos().z());
                maxChunkZ = Math.max(maxChunkZ, ref.pos().z());
            }
        }
        if (chunkCount == 0) {
            return new DimensionStats(0, 0, 0, 0, 0, 0);
        }
        return new DimensionStats(regions.size(), chunkCount,
                minChunkX * 16, maxChunkX * 16 + 15, minChunkZ * 16, maxChunkZ * 16 + 15);
    }
}
