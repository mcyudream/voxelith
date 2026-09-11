package online.yudream.voxelith.tile.domain.tile;

/**
 * 瓦片几何编码选项。默认全部关闭，输出与一期相同的未压缩 float32 glb。
 *
 * <p>{@code quantize} 启用 {@code KHR_mesh_quantization}：POSITION/NORMAL/TEXCOORD_0
 * 分别量化为 i16 / i8 / u16（归一化），体积约降到 1/2～1/3，解码由 three.js GLTFLoader 原生完成。
 * 图集 PNG 不量化（像素贴图保持无损）。meshopt 熵编码（EXT_meshopt_compression）二期再接 JNI/CLI。
 */
public record EncodeOptions(boolean quantize, boolean linearFilter) {

    public EncodeOptions(boolean quantize) {
        this(quantize, false);
    }

    public static EncodeOptions uncompressed() {
        return new EncodeOptions(false, false);
    }

    public static EncodeOptions quantized() {
        return new EncodeOptions(true, false);
    }

    /** LOD 航拍色图：线性过滤，避免方块色图呈马赛克。 */
    public static EncodeOptions lodColormap() {
        return new EncodeOptions(false, true);
    }
}
