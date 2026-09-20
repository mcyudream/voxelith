package online.yudream.voxelith.tile.domain.tile;

/**
 * 瓦片几何编码选项。默认全部关闭，输出与一期相同的未压缩 float32 glb。
 *
 * <p>{@code quantize} 启用 {@code KHR_mesh_quantization}：POSITION/NORMAL/TEXCOORD_0
 * 分别量化为 i16 / i8 / u16（归一化），体积约降到 1/2～1/3，解码由 three.js GLTFLoader 原生完成。
 * 图集 PNG 不量化（像素贴图保持无损）。meshopt 熵编码（EXT_meshopt_compression）二期再接 JNI/CLI。</p>
 *
 * <p>{@code embedImage=false} 时瓦片只写 UV、不内嵌图集 PNG：贴图由清单的 {@code atlas}
 * 引用提供（前端挂一张共享纹理）。hires 图集约 1.7MB，逐瓦片内嵌等于每片 glb 都背一份副本，
 * 上千片就是几个 GB 的重复下载；而前端本来就会把内嵌副本换成共享纹理。材质仍按纹理瓦片输出
 * （alphaMode MASK + alphaCutoff），所以树叶等裁剪面不受影响。</p>
 *
 * <p>{@code meshopt} 启用 {@code EXT_meshopt_compression} 熵编码：POSITION/NORMAL/TEXCOORD_0
 * 与索引走 meshoptimizer 位流（纯 Java 编码器，见 tile-context infrastructure.meshopt），
 * 解码由 three.js 自带的 meshopt 解码器完成。与 {@code quantize} 可叠加：
 * 量化先缩小元素宽度，熵编码再压一遍冗余，两者叠加可把几何压到原始 float32 的 1/4 上下。</p>
 */
public record EncodeOptions(boolean quantize, boolean linearFilter, boolean embedImage, boolean meshopt) {

    public EncodeOptions(boolean quantize, boolean linearFilter, boolean embedImage) {
        this(quantize, linearFilter, embedImage, false);
    }

    public EncodeOptions(boolean quantize) {
        this(quantize, false, true, false);
    }

    public static EncodeOptions uncompressed() {
        return new EncodeOptions(false, false, true, false);
    }

    public static EncodeOptions quantized() {
        return new EncodeOptions(true, false, true, false);
    }

    /**
     * LOD 航拍色图：线性过滤，避免方块色图呈马赛克。
     * 仍内嵌 PNG——增量重跑时逐瓦片色图就是这么嵌的；全量的分层图集页用 {@link #sharedAtlas()}。
     */
    public static EncodeOptions lodColormap() {
        return new EncodeOptions(false, true, true, false);
    }

    /** hires 共享图集：UV 与材质照常，但不内嵌 PNG（贴图由清单 atlas 引用）。 */
    public static EncodeOptions sharedAtlas() {
        return new EncodeOptions(false, false, false, false);
    }

    /**
     * 生产档：量化 + meshopt 熵编码 + 不内嵌图集。
     * glb 只留量化后的顶点位流与索引位流，贴图由清单的共享图集提供。
     */
    public static EncodeOptions compressed() {
        return new EncodeOptions(true, false, false, true);
    }

    /** 在现有选项上开关 meshopt 熵编码。 */
    public EncodeOptions withMeshopt(boolean enabled) {
        return new EncodeOptions(quantize, linearFilter, embedImage, enabled);
    }
}
