package online.yudream.voxelith.sharedkernel.color;

/**
 * sRGB ↔ 线性光色彩空间换算（IEC 61966-2-1 分段曲线）。
 *
 * 约定：纹理像素、群系染色等一切"创作侧"颜色按 sRGB 字节（0-255）处理；
 * 写进 glb COLOR_0 的顶点色一律为**线性空间字节**（three.js 顶点色不做色彩空间转换，
 * 直接进入线性工作空间参与乘法与光照）。凡涉及平均、乘法等运算必须在线性空间进行，
 * 否则输出端再经一次线性→sRGB 编码会导致中间调被提亮、LOD 与 hires 色域不一致。
 */
public final class ColorSpace {

    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final int[] LINEAR_TO_SRGB = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < 256; i++) {
            double l = i / 255.0;
            double c = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055;
            LINEAR_TO_SRGB[i] = (int) Math.round(c * 255.0);
        }
    }

    private ColorSpace() {
    }

    /** 单个 sRGB 字节通道 → 线性分量（0.0-1.0）。 */
    public static float srgbChannelToLinear(int channel) {
        return SRGB_TO_LINEAR[channel & 0xFF];
    }

    /** 单个线性字节通道（0-255 量化） → sRGB 字节。 */
    public static int linearChannelToSrgb(int channel) {
        return LINEAR_TO_SRGB[Math.clamp(channel, 0, 255)];
    }

    /** sRGB 0xRRGGBB → 线性空间 0xRRGGBB（每通道独立转换后按 0-255 量化）。 */
    public static int srgbToLinearRgb(int rgb) {
        int r = Math.round(SRGB_TO_LINEAR[(rgb >> 16) & 0xFF] * 255);
        int g = Math.round(SRGB_TO_LINEAR[(rgb >> 8) & 0xFF] * 255);
        int b = Math.round(SRGB_TO_LINEAR[rgb & 0xFF] * 255);
        return (r << 16) | (g << 8) | b;
    }

    /** 线性空间 0xRRGGBB → sRGB 0xRRGGBB。 */
    public static int linearToSrgbRgb(int rgb) {
        int r = LINEAR_TO_SRGB[Math.clamp((rgb >> 16) & 0xFF, 0, 255)];
        int g = LINEAR_TO_SRGB[Math.clamp((rgb >> 8) & 0xFF, 0, 255)];
        int b = LINEAR_TO_SRGB[Math.clamp(rgb & 0xFF, 0, 255)];
        return (r << 16) | (g << 8) | b;
    }
}
