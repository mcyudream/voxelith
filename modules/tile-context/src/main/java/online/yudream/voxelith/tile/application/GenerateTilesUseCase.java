package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.ImageCodec;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.tile.domain.tile.TileEncoder;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import online.yudream.voxelith.tile.domain.tile.TileMeshAssembler;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * tile 链路用例：收集贴图 → 打包图集 → 按瓦片组装几何 → glb 编码 → 落盘 + 报告。
 */
public class GenerateTilesUseCase {

    /** 缺失贴图的品红兜底（固定占图集第 0 格，与 AtlasLayout 缺失映射 (0,0) 对齐）。 */
    private static final Identifier FALLBACK_TEXTURE = new Identifier("yudream", "block/_missing");

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
        if (reused != null) {
            layout = reused.layout();
            atlasPng = reused.png();
            textureCount = layout.cellIndex().size();
            atlasSize = layout.pixelSize();
            atlasFile = sink.writeAtlas(command.outputDir(), atlasPng);
        } else {
            AtlasResult atlas = packAtlas(command.meshes());
            layout = atlas.layout();
            atlasPng = imageCodec.encodePng(atlas.pixelSize(), atlas.pixelSize(), atlas.argb());
            textureCount = layout.cellIndex().size();
            atlasSize = atlas.pixelSize();
            atlasFile = sink.writeAtlas(command.outputDir(), atlasPng);
        }

        TileMeshAssembler assembler = new TileMeshAssembler();
        List<TileOutcome.TileSummary> summaries = new ArrayList<>();
        for (Map.Entry<TilePos, List<BakedQuadData>> tile : tiles.entrySet()) {
            if (tile.getValue().isEmpty()) {
                continue;
            }
            TileGeometry geometry = assembler.assemble(
                    tile.getKey().x(), tile.getKey().z(), tile.getValue(), layout);
            byte[] glb = tileEncoder.encode(geometry, atlasPng, command.encode());
            sink.writeTile(command.outputDir(), tile.getKey(), glb);
            summaries.add(new TileOutcome.TileSummary(
                    tile.getKey(), geometry.quadCount(), geometry.vertexCount(), glb.length, sha1(glb),
                    geometry.worldMin(), geometry.worldMax()));
        }

        Path reportFile = sink.writeReport(command.outputDir(), summaries, textureCount, atlasSize);
        return new TileOutcome(summaries, atlasFile, reportFile, textureCount, atlasSize);
    }

    private AtlasResult packAtlas(Map<ChunkPos, BakedChunkMeshData> meshes) {
        TreeSet<String> used = new TreeSet<>();
        for (BakedChunkMeshData mesh : meshes.values()) {
            for (BakedQuadData quad : mesh.quads()) {
                used.add(quad.texture());
            }
        }

        List<AtlasTexture> textures = new ArrayList<>();
        textures.add(fallbackTexture());
        for (String id : used) {
            Identifier identifier = Identifier.parse(id);
            Optional<AtlasTexture> loaded = pixelSource.load(identifier);
            textures.add(loaded.orElseGet(() -> blank(identifier)));
        }
        return new AtlasPacker().pack(textures);
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
