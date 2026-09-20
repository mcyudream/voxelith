package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.sharedkernel.vo.MissingTexture;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.tile.domain.tile.TileEncoder;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import online.yudream.voxelith.tile.domain.tile.TileMeshAssembler;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * tile 链路用例：收集贴图 → 打包图集 → 按瓦片组装几何 → glb 编码 → 落盘 + 报告。
 */
public class GenerateTilesUseCase {

    /** 缺失贴图的品红兜底（固定占图集第 0 格，与 AtlasLayout 缺失映射 (0,0) 对齐）。 */
    private static final Identifier FALLBACK_TEXTURE =
            Identifier.parse(MissingTexture.ID);

    private final TexturePixelSource pixelSource;
    private final ImageCodec imageCodec;
    private final TileEncoder tileEncoder;
    private final TileArtifactSink sink;

    public GenerateTilesUseCase(TexturePixelSource pixelSource, ImageCodec imageCodec,
                                TileEncoder tileEncoder, TileArtifactSink sink) {
        this.pixelSource = pixelSource;
        this.imageCodec = imageCodec;
        this.tileEncoder = tileEncoder;
        this.sink = sink;
    }

    public TileOutcome generate(TileCommand command) {
        Map<TilePos, List<BakedQuadData>> tiles = TileMeshAssembler.group(command.meshes());

        AtlasReuse reused = command.reuseAtlas();
        AtlasLayout layout;
        byte[] atlasPng;
        int textureCount;
        int atlasSize;
        Path atlasFile;
        List<String> missingTextures;
        int untexturedQuads;
        if (reused != null) {
            layout = reused.layout();
            atlasPng = reused.png();
            textureCount = layout.cellIndex().size();
            atlasSize = layout.pixelSize();
            atlasFile = sink.writeAtlas(command.outputDir(), atlasPng);
            // 图集可能是增量扩容过的：布局文件跟着写一份，发布/续跑都按最新布局走
            sink.writeAtlasLayout(command.outputDir(), layout);
            Unmapped unmapped = countUnmapped(layout, command.meshes());
            missingTextures = unmapped.missing();
            untexturedQuads = unmapped.untextured();
        } else {
            AtlasBuild build = packAtlas(command.meshes());
            AtlasResult atlas = build.atlas();
            layout = atlas.layout();
            atlasPng = imageCodec.encodePng(atlas.pixelSize(), atlas.pixelSize(), atlas.argb());
            textureCount = layout.cellIndex().size();
            atlasSize = atlas.pixelSize();
            atlasFile = sink.writeAtlas(command.outputDir(), atlasPng);
            sink.writeAtlasLayout(command.outputDir(), layout);
            missingTextures = build.missing();
            untexturedQuads = build.untexturedQuads();
        }

        // 共享图集模式：atlas.png 仍然落盘（清单要引用），但瓦片 glb 不再内嵌它
        byte[] embeddedPng = command.encode().embedImage() ? atlasPng : null;

        TileMeshAssembler assembler = new TileMeshAssembler();
        List<TileOutcome.TileSummary> summaries = new ArrayList<>();
        for (Map.Entry<TilePos, List<BakedQuadData>> tile : tiles.entrySet()) {
            if (tile.getValue().isEmpty()) {
                continue;
            }
            TileGeometry geometry = assembler.assemble(
                    tile.getKey().x(), tile.getKey().z(), tile.getValue(), layout);
            byte[] glb = tileEncoder.encode(geometry, embeddedPng, command.encode());
            sink.writeTile(command.outputDir(), tile.getKey(), glb);
            summaries.add(new TileOutcome.TileSummary(
                    tile.getKey(), geometry.quadCount(), geometry.vertexCount(), glb.length, sha1(glb),
                    geometry.worldMin(), geometry.worldMax()));
        }

        Path reportFile = sink.writeReport(command.outputDir(), summaries, textureCount, atlasSize);
        return new TileOutcome(summaries, atlasFile, reportFile, textureCount, atlasSize,
                missingTextures, untexturedQuads);
    }

    /**
     * 打包图集，并顺手记下两类「会变成品红」的面：
     * 引用了资源包里没有的贴图（多半是资源包与世界版本不匹配）、以及压根没有贴图 id 的面。
     */
    /**
     * 这批网格真正会用到的贴图 id（与 {@link #packAtlas} 的收集口径一致）。
     *
     * <p>增量扩图集要先知道「这次会用到哪些贴图」，才能把已发布图集里缺的补上；
     * 口径必须与打包时完全一致，否则会出现「补齐了但打包时又漏掉」的错位。</p>
     */
    public static Set<String> usedTextures(Map<ChunkPos, BakedChunkMeshData> meshes) {
        TreeSet<String> used = new TreeSet<>();
        for (BakedChunkMeshData mesh : meshes.values()) {
            for (BakedQuadData quad : mesh.quads()) {
                String texture = quad.texture();
                if (texture != null && !MissingTexture.ID.equals(texture)) {
                    used.add(texture);
                }
            }
        }
        return java.util.Collections.unmodifiableSet(used);
    }

    private AtlasBuild packAtlas(Map<ChunkPos, BakedChunkMeshData> meshes) {
        TreeSet<String> used = new TreeSet<>();
        int untexturedQuads = 0;
        for (BakedChunkMeshData mesh : meshes.values()) {
            for (BakedQuadData quad : mesh.quads()) {
                String texture = quad.texture();
                // 无贴图的面（部分 mod 模型）不参与图集：不加进 used，其 UV 由
                // AtlasLayout.mapUv 的「未知 id」分支落到第 0 格兜底贴图（品红/黑棋盘格）
                if (texture == null || MissingTexture.ID.equals(texture)) {
                    untexturedQuads++;
                    continue;
                }
                used.add(texture);
            }
        }

        List<AtlasTexture> textures = new ArrayList<>();
        textures.add(fallbackTexture());
        List<String> missing = new ArrayList<>();
        for (String id : used) {
            Identifier identifier = Identifier.parse(id);
            Optional<AtlasTexture> loaded = pixelSource.load(identifier);
            if (loaded.isEmpty()) {
                missing.add(id);
            }
            textures.add(loaded.orElseGet(() -> blank(identifier)));
        }
        return new AtlasBuild(new AtlasPacker().pack(textures), List.copyOf(missing), untexturedQuads);
    }

    private record AtlasBuild(AtlasResult atlas, List<String> missing, int untexturedQuads) {
    }

    /**
     * 预打一张共享图集并落盘（多遍渲染用）。
     *
     * <p>多遍渲染必须让每一批用**同一张**图集：瓦片里的 UV 是按布局算的，各批各打一张的话，
     * 先落盘的瓦片在新布局下会整体错位/错色。贴图集合由调用方给（采集产物的贴图全表 + 地图画 id），
     * 是实际用量的超集——多打一些格子换取跨批布局稳定。</p>
     *
     * @return 各批复用的图集（布局 + png）
     */
    public AtlasReuse packSharedAtlas(Path outputDir, Collection<String> textureIds) {
        List<AtlasTexture> textures = new ArrayList<>();
        textures.add(fallbackTexture());
        for (String id : new TreeSet<>(textureIds)) {
            Identifier identifier = Identifier.parse(id);
            textures.add(pixelSource.load(identifier).orElseGet(() -> blank(identifier)));
        }
        AtlasResult result = new AtlasPacker().pack(textures);
        byte[] png = imageCodec.encodePng(result.pixelSize(), result.pixelSize(), result.argb());
        sink.writeAtlas(outputDir, png);
        sink.writeAtlasLayout(outputDir, result.layout());
        return new AtlasReuse(result.layout(), png);
    }

    /** 复用图集时统计两类会渲染成兜底格的面：贴图不在图集里、以及压根没有贴图 id。 */
    private static Unmapped countUnmapped(AtlasLayout layout, Map<ChunkPos, BakedChunkMeshData> meshes) {
        Set<String> missing = new TreeSet<>();
        int untextured = 0;
        for (BakedChunkMeshData mesh : meshes.values()) {
            for (BakedQuadData quad : mesh.quads()) {
                String texture = quad.texture();
                if (texture == null || MissingTexture.ID.equals(texture)) {
                    untextured++;
                } else if (!layout.cellIndex().containsKey(texture)) {
                    missing.add(texture);
                }
            }
        }
        return new Unmapped(List.copyOf(missing), untextured);
    }

    private record Unmapped(List<String> missing, int untextured) {
    }

    private static AtlasTexture fallbackTexture() {
        int[] argb = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean magenta = ((x / 8) + (y / 8)) % 2 == 0;
                argb[y * 16 + x] = magenta ? 0xFFFF00DC : 0xFF000000;
            }
        }
        return new AtlasTexture(FALLBACK_TEXTURE, 16, 16, argb);
    }

    private static AtlasTexture blank(Identifier id) {
        int[] argb = new int[256];
        Arrays.fill(argb, 0xFFFF00DC);
        return new AtlasTexture(id, 16, 16, argb);
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
