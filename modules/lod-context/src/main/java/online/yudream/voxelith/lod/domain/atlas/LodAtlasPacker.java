package online.yudream.voxelith.lod.domain.atlas;

/**
 * LOD 层级图集页打包：把一层内各瓦片的航拍色图按「世界瓦片网格」拼成一页。
 *
 * <p>槽位定位是 {@code (tileX - minTileX, tileZ - minTileZ)}，与「该层实际存在哪些瓦片」无关：
 * 空洞瓦片只浪费一个槽位，却换来两个好处——一是 UV 矩形在增量重跑后保持稳定，
 * 二是瓦片与槽位的对应关系无需额外元数据（前端只认图集页 URL，UV 已烘焙进 glb）。</p>
 *
 * <p>行序与色图一致：行 0 = 最小世界 Z。glb 侧 flipY=false，v=0 即图集第一行，
 * 与 {@code AerialRaster.downsample} 的 {@code argb[tz*size+tx]} 行主序对齐。</p>
 */
public final class LodAtlasPacker {

    /** 图集页最大边长（像素）。WebGL 设备普遍支持 4096；更弱设备可下调此常量。 */
    public static final int MAX_PAGE_DIM = 4096;

    /** 槽位边长下限：再小就低于远景色图可辨识的精度。 */
    public static final int MIN_SLOT = 16;

    /** 槽位边长对齐粒度（像素）。 */
    private static final int SLOT_QUANTUM = 8;

    private final int level;
    private final int minTileX;
    private final int minTileZ;
    private final int gridWidth;
    private final int gridHeight;
    private final int slotSize;
    private final int width;
    private final int height;

    private LodAtlasPacker(int level, int minTileX, int minTileZ, int gridWidth, int gridHeight, int slotSize) {
        this.level = level;
        this.minTileX = minTileX;
        this.minTileZ = minTileZ;
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.slotSize = slotSize;
        this.width = gridWidth * slotSize;
        this.height = gridHeight * slotSize;
    }

    /**
     * @param level      LOD 层级（≥ 1，决定槽位基础尺寸）
     * @param minTileX   该层最小瓦片 x（含）
     * @param maxTileX   该层最大瓦片 x（含）
     * @param minTileZ   该层最小瓦片 z（含）
     * @param maxTileZ   该层最大瓦片 z（含）
     */
    public static LodAtlasPacker of(int level, int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
        if (maxTileX < minTileX || maxTileZ < minTileZ) {
            throw new IllegalArgumentException("空的瓦片网格: x[" + minTileX + "," + maxTileX
                    + "] z[" + minTileZ + "," + maxTileZ + "]");
        }
        int gridWidth = maxTileX - minTileX + 1;
        int gridHeight = maxTileZ - minTileZ + 1;
        return new LodAtlasPacker(level, minTileX, minTileZ, gridWidth, gridHeight,
                chooseSlotSize(level, gridWidth, gridHeight));
    }

    /**
     * 槽位边长：层级越深越小（L1 = 64、L2 = 32、更深 32），再按页面上限收缩并对齐到 8 像素。
     *
     * <p>为什么随层减半：LOD 是远景色图，层级 L 的瓦片在屏幕上的跨度约为 L1 的 1/2^(L-1)，
     * 保持同一分辨率纯属浪费——按层减半让整页像素量降到约 1/4，直接省下显存与解码时间。
     * 页面上限则保证任何网格下都不会超出设备纹理尺寸上限。</p>
     */
    static int chooseSlotSize(int level, int gridWidth, int gridHeight) {
        int base = Math.max(32, 64 >> Math.max(0, level - 1));
        int fit = MAX_PAGE_DIM / Math.max(gridWidth, gridHeight);
        int slot = Math.min(base, fit);
        slot = (slot / SLOT_QUANTUM) * SLOT_QUANTUM;
        return Math.max(MIN_SLOT, slot);
    }

    public int level() {
        return level;
    }

    public int slotSize() {
        return slotSize;
    }

    public int gridWidth() {
        return gridWidth;
    }

    public int gridHeight() {
        return gridHeight;
    }

    /** 图集页像素宽。 */
    public int width() {
        return width;
    }

    /** 图集页像素高。 */
    public int height() {
        return height;
    }

    /** 分配一页空像素（ARGB，全 0 = 透明；未被瓦片覆盖的槽位保持透明）。 */
    public int[] newPage() {
        return new int[width * height];
    }

    /**
     * 把一片瓦片的色图拷进其槽位。
     *
     * @param page     图集页像素（{@link #newPage()}）
     * @param tileX    瓦片 x
     * @param tileZ    瓦片 z
     * @param argb     瓦片色图（{@code slotSize × slotSize}，行主序，行 0 = 最小世界 Z）
     */
    public void blit(int[] page, int tileX, int tileZ, int[] argb) {
        int col = tileX - minTileX;
        int row = tileZ - minTileZ;
        if (col < 0 || col >= gridWidth || row < 0 || row >= gridHeight) {
            throw new IllegalArgumentException("瓦片 (" + tileX + "," + tileZ + ") 不在图集网格 x["
                    + minTileX + "," + (minTileX + gridWidth - 1) + "] z["
                    + minTileZ + "," + (minTileZ + gridHeight - 1) + "] 内");
        }
        if (argb.length != slotSize * slotSize) {
            throw new IllegalArgumentException("色图尺寸不符: 期望 " + (slotSize * slotSize)
                    + " 像素（" + slotSize + "²），收到 " + argb.length);
        }
        int x0 = col * slotSize;
        int y0 = row * slotSize;
        for (int y = 0; y < slotSize; y++) {
            System.arraycopy(argb, y * slotSize, page, (y0 + y) * width + x0, slotSize);
        }
    }

    /**
     * 槽位的图集 UV 矩形 {@code {u0,v0,u1,v1}}，含**半纹素内缩**：线性过滤下若采样点正好落在
     * 槽位边界，会与相邻槽位插值出串色条纹（远端 4d9e5f7 修的就是这类问题）。
     * 内缩半个纹素后所有采样点都落在槽内，边缘那一列纹素的损失对视角色图不可见。
     */
    public float[] uvRect(int tileX, int tileZ) {
        int col = tileX - minTileX;
        int row = tileZ - minTileZ;
        float inset = 0.5f;
        float u0 = (col * slotSize + inset) / width;
        float v0 = (row * slotSize + inset) / height;
        float u1 = ((col + 1) * slotSize - inset) / width;
        float v1 = ((row + 1) * slotSize - inset) / height;
        return new float[]{u0, v0, u1, v1};
    }
}
