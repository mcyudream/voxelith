package online.yudream.voxelith.tile.infrastructure.artifact;

import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.tile.TileMeshAssembler;
import online.yudream.voxelith.tile.infrastructure.glb.GlbTileInspector;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从已落盘的 tiles/hires 与 tiles/lod 扫描 glb，重建 {@link TileOutcome} 供 manifest 发布。
 * 全量烘焙按 region 写出后内存摘要丢失时使用，不重新烘焙。
 */
public final class DiskTileIndexer {

    private DiskTileIndexer() {
    }

    public static TileOutcome index(Path tilesDir) {
        List<TileOutcome.TileSummary> summaries = new ArrayList<>();
        Path hires = tilesDir.resolve("tiles").resolve("hires");
        if (Files.isDirectory(hires)) {
            indexTree(hires, summaries, true);
        }
        Path lod = tilesDir.resolve("tiles").resolve("lod");
        if (Files.isDirectory(lod)) {
            indexTree(lod, summaries, false);
        }
        summaries.sort(Comparator
                .comparingInt((TileOutcome.TileSummary t) -> t.pos().level())
                .thenComparingInt(t -> t.pos().x())
                .thenComparingInt(t -> t.pos().z()));

        int atlasSize = 0;
        int textureCount = 1;
        Path layoutFile = tilesDir.resolve(AtlasLayoutFiles.FILE_NAME);
        if (Files.isRegularFile(layoutFile)) {
            AtlasLayout layout = AtlasLayoutFiles.read(layoutFile);
            atlasSize = layout.pixelSize();
            textureCount = Math.max(1, layout.cellIndex().size());
        }
        return new TileOutcome(summaries, tilesDir.resolve("atlas.png"),
                tilesDir.resolve("tile-report.json"), textureCount, atlasSize);
    }

    private static void indexTree(Path root, List<TileOutcome.TileSummary> summaries, boolean hires) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith(".glb")).toList()) {
                summaries.add(summarize(root, file, hires));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描瓦片失败: " + root, e);
        }
    }

    private static TileOutcome.TileSummary summarize(Path treeRoot, Path file, boolean hires) {
        try {
            TilePos pos = parsePos(treeRoot, file, hires);
            GlbTileInspector.GeometryInfo geometry = GlbTileInspector.inspect(file);
            int coverage = pos.coverage(TileMeshAssembler.HIRES_TILE_SIZE);
            float originX = pos.x() * (float) coverage;
            float originZ = pos.z() * (float) coverage;
            float[] min = {
                    geometry.localMin()[0] + originX,
                    geometry.localMin()[1],
                    geometry.localMin()[2] + originZ
            };
            float[] max = {
                    geometry.localMax()[0] + originX,
                    geometry.localMax()[1],
                    geometry.localMax()[2] + originZ
            };
            int bytes = Math.toIntExact(Files.size(file));
            return new TileOutcome.TileSummary(
                    pos, geometry.quads(), geometry.quads() * 4, bytes, sha1(file), min, max);
        } catch (IOException e) {
            throw new UncheckedIOException("读取瓦片失败: " + file, e);
        }
    }

    private static TilePos parsePos(Path treeRoot, Path file, boolean hires) {
        Path relative = treeRoot.relativize(file);
        int nameCount = relative.getNameCount();
        if (hires) {
            if (nameCount != 2) {
                throw new IllegalStateException("hires 路径应为 {x}/{z}.glb: " + relative);
            }
            int x = Integer.parseInt(relative.getName(0).toString());
            int z = Integer.parseInt(stripGlb(relative.getName(1).toString()));
            return TilePos.hires(x, z);
        }
        if (nameCount != 3) {
            throw new IllegalStateException("lod 路径应为 {level}/{x}/{z}.glb: " + relative);
        }
        int level = Integer.parseInt(relative.getName(0).toString());
        int x = Integer.parseInt(relative.getName(1).toString());
        int z = Integer.parseInt(stripGlb(relative.getName(2).toString()));
        return new TilePos(level, x, z);
    }

    private static String stripGlb(String name) {
        if (!name.endsWith(".glb")) {
            throw new IllegalStateException("不是 glb: " + name);
        }
        return name.substring(0, name.length() - 4);
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
