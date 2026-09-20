package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.world.domain.world.EntityEquipment;
import online.yudream.voxelith.world.domain.world.PlacedEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 实体几何注入：把盔甲架这类**实体**补成方块几何，让它们在地图里出现。
 *
 * <p>为什么这么做：实体不在方块数据里，纯方块渲染看不到它们（护甲架、画、掉落物都属于此类）。
 * 这里不还原原版实体模型动画，而是按原版比例摆出**简化盒体**：能一眼认出「这里有个盔甲架、
 * 朝哪边、是不是小型、穿的什么」。规格与取舍：</p>
 *
 * <ul>
 *   <li>高度 32 像素（= 2 方块，与原版盔甲架一致）；底座 12×1×12、双腿 2×11×2、
 *       躯干 4×12×2、手臂 2×12×2（仅 {@code ShowArms} 时画）、头 8×8×8；</li>
 *   <li>{@code Small} 整体缩放 0.5、{@code NoBasePlate} 去掉底座、{@code Rotation[0]} 决定朝向；</li>
 *   <li>本体贴图用**内置**的木纹（{@code voxelith:entity/armor_stand}），不依赖资源包里任何
 *       贴图名——任何版本、任何资源包都能画出来；</li>
 *   <li><b>装备</b>：四格盔甲按原版盔甲盒体（玩家尺寸 + 膨胀 1.0/0.5 像素）包在身上，贴图取
 *       真实的盔甲层贴图（先试 1.21.2+ 的 {@code entity/equipment/humanoid[_leggings]/}，
 *       再回退 {@code models/armor/<材质>_layer_1/2}），UV 用标准盒体展开，所以铁甲看得出
 *       铁甲、钻石甲看得出钻石甲；手持物画成手位上的 16×16 精灵片（原版「生成型」物品的
 *       挤出片，这里省去侧面），方块类手持物画成 8px 小立方；没有手臂的架子不画手持物；</li>
 *   <li>贴图解析结果都进 {@link #prepare} 缓存表并计入 {@link #textureIds()}——多遍渲染的
 *       共享图集在注入前就要用这份清单，缺贴图的装备按件计数跳过（诊断输出用）；</li>
 *   <li>姿态（Pose）按直立处理；染色盔甲不取组件里的染色，按底图绘制；</li>
 *   <li>光照按「满天空光」写入，与地图画面片一致（实体没有烘焙光照数据）。</li>
 * </ul>
 */
public final class EntityInjector implements TexturePixelSource {

    /** 盔甲架贴图 id（内置，不查资源包）。 */
    public static final String ARMOR_STAND_TEXTURE = "voxelith:entity/armor_stand";

    /** 一个方块 = 16 像素（原版实体模型的单位）。 */
    private static final float PIXEL = 1f / 16f;

    /** 盔甲物品 id 的部件后缀 → 材质名（如 minecraft:diamond_chestplate → diamond）。 */
    private static final String[] PIECE_SUFFIXES = {"_helmet", "_chestplate", "_leggings", "_boots"};

    private final TexturePixelSource delegate;
    /** 本注入器供给的贴图（内置木纹 + 运行时解析出的装备贴图，非方形的已补成正方形）。 */
    private final Map<String, AtlasTexture> own = new LinkedHashMap<>();
    /** 装备物品 → 盔甲层贴图 id（键带层级后缀；解析失败记 null，避免反复查包）。 */
    private final Map<String, String> armorTextureOf = new HashMap<>();
    /** 手持物品 → 物品/方块贴图 id（解析失败记 null）。 */
    private final Map<String, String> heldTextureOf = new HashMap<>();
    /** 走「方块小立方」几何的手持物品（没有物品精灵片、有方块贴图的）。 */
    private final Set<String> heldAsBlock = new HashSet<>();

    // 装备渲染计数（诊断输出；inject 时累计，多遍渲染跨批累加）
    private int armorPiecesRendered;
    private int armorPiecesMissingTexture;
    private int heldItemsRendered;
    private int heldItemsMissingTexture;

    public EntityInjector(TexturePixelSource delegate) {
        this.delegate = delegate;
        own.put(ARMOR_STAND_TEXTURE, armorStandTexture());
    }

    @Override
    public Optional<AtlasTexture> load(Identifier textureId) {
        AtlasTexture texture = own.get(textureId.toString());
        if (texture != null) {
            return Optional.of(texture);
        }
        Optional<AtlasTexture> loaded = delegate.load(textureId);
        if (loaded.isPresent() && loaded.get().cellWidth() != loaded.get().cellHeight()) {
            // 图集打包器会把贴图缩放进正方形格：非方形贴图（盔甲层是 64×32）会被纵向拉伸。
            // 这里补齐成方形（底部加透明行）再供给；UV 按「像素 × 16/边长」计算，不受影响。
            AtlasTexture padded = padToSquare(loaded.get());
            own.put(textureId.toString(), padded);
            return Optional.of(padded);
        }
        return loaded;
    }

    /**
     * 预解析一批实体的装备贴图。
     *
     * <p>多遍渲染会**先打一张共享图集供各批复用**，而那张图集的贴图清单在注入之前就要定下来；
     * 盔甲/手持物贴图是运行时按物品 id 解析的，采集产物里当然没有——必须先走这一步把它们
     * 解析进 {@link #textureIds()}，否则这些面会落进品红兜底格。单遍渲染可以不调（注入在
     * 打图集之前完成，按 quad 贴图表打包），调了也无害。</p>
     */
    public void prepare(List<PlacedEntity> entities) {
        for (PlacedEntity entity : entities) {
            if (!entity.isArmorStand()) {
                continue;
            }
            EntityEquipment equipment = entity.equipment();
            resolveArmorTexture(equipment.head(), true);
            resolveArmorTexture(equipment.chest(), true);
            resolveArmorTexture(equipment.legs(), false);
            resolveArmorTexture(equipment.feet(), true);
            if (entity.showArms()) {
                resolveHeldTexture(equipment.mainHand());
                resolveHeldTexture(equipment.offHand());
            }
        }
    }

    /**
     * 本注入器供给的贴图 id（内置木纹 + 已解析的装备贴图）。
     * 多遍渲染预打包共享图集时必须并进去，否则这些面会落品红兜底格。
     */
    public List<String> textureIds() {
        return List.copyOf(own.keySet());
    }

    /** 本次运行已渲染出几何的盔甲件数。 */
    public int armorPiecesRendered() {
        return armorPiecesRendered;
    }

    /** 因找不到盔甲层贴图而跳过的盔甲件数。 */
    public int armorPiecesMissingTexture() {
        return armorPiecesMissingTexture;
    }

    /** 本次运行已渲染出几何的手持物件数。 */
    public int heldItemsRendered() {
        return heldItemsRendered;
    }

    /** 因找不到物品贴图而跳过的手持物件数。 */
    public int heldItemsMissingTexture() {
        return heldItemsMissingTexture;
    }

    /**
     * 把实体几何注入对应区块。只画盔甲架（含装备）；其余实体类型直接忽略（白名单在读取侧）。
     *
     * @return 注入后的新网格表（原表不被修改）
     */
    public Map<ChunkPos, BakedChunkMeshData> inject(List<PlacedEntity> entities,
                                                    Map<ChunkPos, BakedChunkMeshData> meshes) {
        Map<ChunkPos, BakedChunkMeshData> out = new LinkedHashMap<>(meshes);
        for (PlacedEntity entity : entities) {
            if (!entity.isArmorStand()) {
                continue;
            }
            List<BakedQuadData> quads = new ArrayList<>(armorStandQuads(entity, ARMOR_STAND_TEXTURE));
            quads.addAll(armorQuads(entity));
            quads.addAll(heldQuads(entity));
            if (quads.isEmpty()) {
                continue;
            }
            ChunkPos chunk = new ChunkPos(
                    Math.floorDiv((int) Math.floor(entity.x()), 16),
                    Math.floorDiv((int) Math.floor(entity.z()), 16));
            BakedChunkMeshData mesh = out.get(chunk);
            if (mesh == null) {
                mesh = new BakedChunkMeshData(chunk, List.of(), Map.of(), 0);
            }
            List<BakedQuadData> merged = new ArrayList<>(mesh.quads());
            merged.addAll(quads);
            out.put(chunk, new BakedChunkMeshData(chunk, List.copyOf(merged), mesh.missing(),
                    mesh.blocksBaked()));
        }
        return out;
    }

    /** 盔甲架的盒体模型（局部坐标，脚底中心为原点，单位为方块）。 */
    static List<BakedQuadData> armorStandQuads(PlacedEntity entity, String textureId) {
        float scale = entity.small() ? 0.5f : 1f;
        List<float[]> boxes = new ArrayList<>();
        if (!entity.noBasePlate()) {
            boxes.add(box(-6, 0, -6, 6, 1, 6));
        }
        // 双腿：各 2×11×2，从底座上方到腰部
        boxes.add(box(-3, 1, -1, -1, 12, 1));
        boxes.add(box(1, 1, -1, 3, 12, 1));
        // 躯干：4×12×2
        boxes.add(box(-2, 12, -1, 2, 24, 1));
        if (entity.showArms()) {
            boxes.add(box(-4, 12, -1, -2, 24, 1));
            boxes.add(box(2, 12, -1, 4, 24, 1));
        }
        // 头：8×8×8
        boxes.add(box(-4, 24, -4, 4, 32, 4));

        List<BakedQuadData> quads = new ArrayList<>();
        for (float[] bounds : boxes) {
            quads.addAll(boxQuads(entity, bounds, scale, textureId));
        }
        return List.copyOf(quads);
    }

    // ---------------------------------------------------------------- 盔甲

    /**
     * 一段盔甲的盒体：bounds 为模型像素坐标（已转方块），(u0,v0) 是盒体展开在贴图上的锚点，
     * (w,h,d) 是展开尺寸——与原版 {@code CubeListBuilder.texOffs().addBox()} 同一套约定。
     *
     * <p>盒体尺寸 = 原版玩家盔甲部件（头部 8×8×8、躯干 8×12×4、四肢 4×12×4）加原版膨胀
     * （外层 1.0 px、内层护腿 0.5 px），所以盔甲在细瘦的架身上会像原版一样略微外扩。</p>
     */
    private record ArmorBox(float[] bounds, int u0, int v0, int w, int h, int d) {
    }

    private static ArmorBox armorBox(float x0, float y0, float z0, float x1, float y1, float z1,
                                     int u0, int v0, int w, int h, int d) {
        return new ArmorBox(new float[]{
                x0 * PIXEL, y0 * PIXEL, z0 * PIXEL, x1 * PIXEL, y1 * PIXEL, z1 * PIXEL},
                u0, v0, w, h, d);
    }

    private static final ArmorBox HELMET = armorBox(-5, 23, -5, 5, 33, 5, 0, 0, 8, 8, 8);
    private static final ArmorBox CHEST_BODY = armorBox(-5, 11, -3, 5, 25, 3, 16, 16, 8, 12, 4);
    private static final ArmorBox CHEST_ARM_RIGHT = armorBox(-8, 11, -3, -2, 25, 3, 40, 16, 4, 12, 4);
    private static final ArmorBox CHEST_ARM_LEFT = armorBox(2, 11, -3, 8, 25, 3, 40, 16, 4, 12, 4);
    private static final ArmorBox LEGGINGS_BODY = armorBox(-4.5f, 11.5f, -2.5f, 4.5f, 24.5f, 2.5f,
            16, 16, 8, 12, 4);
    private static final ArmorBox LEGGINGS_RIGHT = armorBox(-4.5f, -0.5f, -2.5f, 0.5f, 12.5f, 2.5f,
            0, 16, 4, 12, 4);
    private static final ArmorBox LEGGINGS_LEFT = armorBox(-0.5f, -0.5f, -2.5f, 4.5f, 12.5f, 2.5f,
            0, 16, 4, 12, 4);
    private static final ArmorBox BOOT_RIGHT = armorBox(-5, -1, -3, 1, 13, 3, 0, 16, 4, 12, 4);
    private static final ArmorBox BOOT_LEFT = armorBox(-1, -1, -3, 5, 13, 3, 0, 16, 4, 12, 4);

    /** 一个实体的全部盔甲面片（有哪格画哪格；缺贴图的整格跳过并计数）。 */
    private List<BakedQuadData> armorQuads(PlacedEntity entity) {
        EntityEquipment equipment = entity.equipment();
        List<BakedQuadData> quads = new ArrayList<>();
        quads.addAll(armorPiece(entity, equipment.head(), true, new ArmorBox[]{HELMET}));
        List<ArmorBox> chest = new ArrayList<>();
        chest.add(CHEST_BODY);
        if (entity.showArms()) {
            chest.add(CHEST_ARM_RIGHT);
            chest.add(CHEST_ARM_LEFT);
        }
        quads.addAll(armorPiece(entity, equipment.chest(), true, chest.toArray(new ArmorBox[0])));
        quads.addAll(armorPiece(entity, equipment.legs(), false,
                new ArmorBox[]{LEGGINGS_BODY, LEGGINGS_RIGHT, LEGGINGS_LEFT}));
        quads.addAll(armorPiece(entity, equipment.feet(), true,
                new ArmorBox[]{BOOT_RIGHT, BOOT_LEFT}));
        return quads;
    }

    private List<BakedQuadData> armorPiece(PlacedEntity entity, String itemId, boolean outerLayer,
                                           ArmorBox[] boxes) {
        if (itemId == null) {
            return List.of();
        }
        String textureId = resolveArmorTexture(itemId, outerLayer);
        if (textureId == null) {
            armorPiecesMissingTexture++;
            return List.of();
        }
        armorPiecesRendered++;
        AtlasTexture texture = own.get(textureId);
        int textureSize = Math.max(texture.cellWidth(), texture.cellHeight());
        List<BakedQuadData> quads = new ArrayList<>();
        for (ArmorBox box : boxes) {
            quads.addAll(armorBoxQuads(entity, textureId, textureSize, box));
        }
        return quads;
    }

    /**
     * 盔甲盒体的 6 个外表面：几何与 {@link #boxQuads} 同一套绕序（从外面看左下→右下→右上→左上
     * 为逆时针），UV 改用标准盒体展开——正面区域朝实体面向方向，左右/顶底各归其位。
     */
    private static List<BakedQuadData> armorBoxQuads(PlacedEntity entity, String textureId,
                                                     int textureSize, ArmorBox armorBox) {
        float[] b = armorBox.bounds();
        float x0 = b[0];
        float y0 = b[1];
        float z0 = b[2];
        float x1 = b[3];
        float y1 = b[4];
        float z1 = b[5];
        int u0 = armorBox.u0();
        int v0 = armorBox.v0();
        int w = armorBox.w();
        int h = armorBox.h();
        int d = armorBox.d();

        // 每面：从外面看的 左下/右下/右上/左上（绕序经叉乘校验：与外法线同向）
        float[][][] corners = {
                {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}},
                {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                {{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}},
                {{x1, y0, z1}, {x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}},
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}},
        };
        float[][][] normals = {
                {{1, 0, 0}}, {{-1, 0, 0}}, {{0, 1, 0}}, {{0, -1, 0}}, {{0, 0, 1}}, {{0, 0, -1}},
        };
        // 每面 UV 区（贴图像素，标准盒体展开；+z 是贴图的“正面”区域）
        float[][] uvRect = {
                {u0 + w + d, v0 + d, u0 + w + 2 * d, v0 + d + h},
                {u0, v0 + d, u0 + d, v0 + d + h},
                {u0 + d, v0, u0 + d + w, v0 + d},
                {u0 + d + w, v0, u0 + w + 2 * d, v0 + d},
                {u0 + d, v0 + d, u0 + d + w, v0 + d + h},
                {u0 + w + 2 * d, v0 + d, u0 + 2 * w + 2 * d, v0 + d + h},
        };
        float unit = 16f / textureSize;
        List<BakedQuadData> quads = new ArrayList<>(6);
        for (int f = 0; f < corners.length; f++) {
            float[] rect = uvRect[f];
            // 顶点序是 左下/右下/右上/左上 → uv 依次为 左下/右下/右上/左上（v 轴向下）
            float[] uvs = {
                    rect[0] * unit, rect[3] * unit,
                    rect[2] * unit, rect[3] * unit,
                    rect[2] * unit, rect[1] * unit,
                    rect[0] * unit, rect[1] * unit,
            };
            quads.add(face(entity, scale(entity), textureId, corners[f], normals[f][0], uvs));
        }
        return quads;
    }

    // ---------------------------------------------------------------- 手持物

    /** 一个实体的手持物面片（原版约定：没有手臂的架子不持物）。side=-1 主手（实体右侧），+1 副手。 */
    private List<BakedQuadData> heldQuads(PlacedEntity entity) {
        if (!entity.showArms()) {
            return List.of();
        }
        EntityEquipment equipment = entity.equipment();
        List<BakedQuadData> quads = new ArrayList<>();
        quads.addAll(heldItem(entity, equipment.mainHand(), -1));
        quads.addAll(heldItem(entity, equipment.offHand(), 1));
        return quads;
    }

    /**
     * 单件手持物：「生成型」物品（剑/工具/食物…）画 16×16 精灵片竖在手位（厚 1px 的正反两片）；
     * 方块类物品画 8px 小立方（各面整贴图，足够一眼认出「拿着一个方块」）。
     */
    private List<BakedQuadData> heldItem(PlacedEntity entity, String itemId, int side) {
        if (itemId == null) {
            return List.of();
        }
        String textureId = resolveHeldTexture(itemId);
        if (textureId == null) {
            heldItemsMissingTexture++;
            return List.of();
        }
        heldItemsRendered++;
        if (heldAsBlock.contains(itemId)) {
            return boxQuads(entity,
                    box(side * 3.5f - 4, 9, -2, side * 3.5f + 4, 17, 6), scale(entity), textureId);
        }
        float x0 = (side * 3.5f - 0.5f) * PIXEL;
        float x1 = (side * 3.5f + 0.5f) * PIXEL;
        float y0 = 5 * PIXEL;
        float y1 = 21 * PIXEL;
        float z0 = -6 * PIXEL;
        float z1 = 10 * PIXEL;
        List<BakedQuadData> quads = new ArrayList<>(2);
        quads.add(face(entity, scale(entity), textureId, new float[][]{
                {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}}, new float[]{1, 0, 0}));
        quads.add(face(entity, scale(entity), textureId, new float[][]{
                {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}}, new float[]{-1, 0, 0}));
        return quads;
    }

    private static float scale(PlacedEntity entity) {
        return entity.small() ? 0.5f : 1f;
    }

    // ---------------------------------------------------------------- 贴图解析

    /** 解析一件盔甲的层贴图：先 1.21.2+ 的 equipment 布局，再回退 models/armor 旧路径。 */
    private String resolveArmorTexture(String itemId, boolean outerLayer) {
        String key = itemId + (outerLayer ? "#1" : "#2");
        if (armorTextureOf.containsKey(key)) {
            return armorTextureOf.get(key);
        }
        String material = armorMaterial(itemId);
        String resolved = material == null ? null : outerLayer
                ? resolveTexture("minecraft:entity/equipment/humanoid/" + material,
                                 "minecraft:models/armor/" + material + "_layer_1")
                : resolveTexture("minecraft:entity/equipment/humanoid_leggings/" + material,
                                 "minecraft:models/armor/" + material + "_layer_2");
        armorTextureOf.put(key, resolved);
        return resolved;
    }

    /** 解析手持物贴图：先物品精灵片（textures/item/），没有再试方块贴图（textures/block/）。 */
    private String resolveHeldTexture(String itemId) {
        if (itemId == null || heldTextureOf.containsKey(itemId)) {
            return heldTextureOf.get(itemId);
        }
        String path = pathOf(itemId);
        String resolved = resolveTexture("minecraft:item/" + path);
        if (resolved != null) {
            heldTextureOf.put(itemId, resolved);
            return resolved;
        }
        resolved = resolveTexture("minecraft:block/" + path);
        heldTextureOf.put(itemId, resolved);
        if (resolved != null) {
            heldAsBlock.add(itemId);
        }
        return resolved;
    }

    /** 逐个候选 id 查贴图，命中（并缓存进本注入器）就返回该 id；全落空返回 null。 */
    private String resolveTexture(String... candidates) {
        for (String candidate : candidates) {
            if (own.containsKey(candidate)) {
                return candidate;
            }
            Optional<AtlasTexture> loaded = delegate.load(Identifier.parse(candidate));
            if (loaded.isPresent()) {
                own.put(candidate, padToSquare(loaded.get()));
                return candidate;
            }
        }
        return null;
    }

    /** minecraft:diamond_chestplate → diamond；空槽或不是盔甲部件返回 null。 */
    static String armorMaterial(String itemId) {
        if (itemId == null) {
            return null;
        }
        String path = pathOf(itemId);
        for (String suffix : PIECE_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return null;
    }

    private static String pathOf(String itemId) {
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }

    /** 非方形贴图补成正方形（底部补透明行），避免打包器缩放进方格时纵向拉伸。 */
    private static AtlasTexture padToSquare(AtlasTexture texture) {
        if (texture.cellWidth() == texture.cellHeight()) {
            return texture;
        }
        int size = Math.max(texture.cellWidth(), texture.cellHeight());
        int[] padded = new int[size * size];
        for (int y = 0; y < texture.cellHeight(); y++) {
            System.arraycopy(texture.argb(), y * texture.cellWidth(),
                    padded, y * size, texture.cellWidth());
        }
        return new AtlasTexture(texture.id(), size, size, padded);
    }

    // ---------------------------------------------------------------- 盒体几何

    /** 像素坐标（16 像素 = 1 方块）的轴对齐盒体。 */
    private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new float[]{x0 * PIXEL, y0 * PIXEL, z0 * PIXEL, x1 * PIXEL, y1 * PIXEL, z1 * PIXEL};
    }

    /** 一个盒体的 6 个外表面（顶点按「从外面看逆时针」排，保证正面剔除下可见），每面铺满整张贴图。 */
    private static List<BakedQuadData> boxQuads(PlacedEntity entity, float[] b, float scale,
                                                String textureId) {
        float x0 = b[0];
        float y0 = b[1];
        float z0 = b[2];
        float x1 = b[3];
        float y1 = b[4];
        float z1 = b[5];
        List<BakedQuadData> quads = new ArrayList<>(6);
        // 每个面：4 个角 + 外法线（顺序经单测校验：绕序叉乘必须等于该法线且指向盒外）
        quads.add(face(entity, scale, textureId, new float[][]{
                {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}}, new float[]{1, 0, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, {x0, y0, z0}}, new float[]{-1, 0, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}}, new float[]{0, 1, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}}, new float[]{0, -1, 0}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}}, new float[]{0, 0, 1}));
        quads.add(face(entity, scale, textureId, new float[][]{
                {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}}, new float[]{0, 0, -1}));
        return quads;
    }

    /** 单个面：按实体位置/朝向/缩放变换到世界坐标，铺满整张贴图，并写入满天空光。 */
    private static BakedQuadData face(PlacedEntity entity, float scale, String textureId,
                                      float[][] corners, float[] normal) {
        return face(entity, scale, textureId, corners, normal,
                new float[]{0, 16, 16, 16, 16, 0, 0, 0});
    }

    /** 单个面（显式 UV，按顶点序 左下/右下/右上/左上 各给一组 u,v）。 */
    private static BakedQuadData face(PlacedEntity entity, float scale, String textureId,
                                      float[][] corners, float[] normal, float[] uvs) {
        float yawRad = (float) Math.toRadians(entity.yaw());
        float cos = (float) Math.cos(yawRad);
        float sin = (float) Math.sin(yawRad);
        float[] positions = new float[12];
        for (int i = 0; i < 4; i++) {
            float lx = corners[i][0] * scale;
            float ly = corners[i][1] * scale;
            float lz = corners[i][2] * scale;
            // 绕 Y 轴按实体 yaw 旋转（yaw=0 朝南 +Z，yaw=90 朝西 -X），再平移到实体位置
            positions[i * 3] = (float) entity.x() + lx * cos - lz * sin;
            positions[i * 3 + 1] = (float) entity.y() + ly;
            positions[i * 3 + 2] = (float) entity.z() + lx * sin + lz * cos;
        }
        float[] worldNormal = new float[]{
                normal[0] * cos - normal[2] * sin,
                normal[1],
                normal[0] * sin + normal[2] * cos,
        };
        // skyLight 的约定是**原始等级 0..15**（TileMeshAssembler 再 ×17 打包成 ubyte），
        // 写 255 会被二次相乘溢出成 239；实体没有烘焙光照，给满天空光即可。
        byte[] sky = {15, 15, 15, 15};
        byte[] zero = {0, 0, 0, 0};
        return new BakedQuadData(positions, uvs, worldNormal, textureId,
                -1, true, BakedQuadData.NON_TERRAIN_FACE, -1, sky, zero, zero, false);
    }

    /**
     * 内置木纹贴图（16×16）：盔甲架是木制的，深浅两色竖纹 + 深色描边，
     * 不依赖任何资源包贴图名，所以任何版本都能画。
     */
    private static AtlasTexture armorStandTexture() {
        int light = 0xFFC08A4E;
        int dark = 0xFF9C6B3F;
        int edge = 0xFF6E4A2A;
        int[] argb = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean border = x == 0 || y == 0 || x == 15 || y == 15;
                argb[y * 16 + x] = border ? edge : ((x % 4 == 0) ? dark : light);
            }
        }
        return new AtlasTexture(Identifier.parse(ARMOR_STAND_TEXTURE), 16, 16, argb);
    }
}
