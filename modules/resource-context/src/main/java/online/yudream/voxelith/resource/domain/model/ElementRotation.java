package online.yudream.voxelith.resource.domain.model;

/**
 * 模型元素的旋转定义（MC 限制 angle ∈ {-45,-22.5,0,22.5,45}，单轴）。
 */
public record ElementRotation(float[] origin, Axis axis, float angle, boolean rescale) {

    public enum Axis {
        X, Y, Z;

        public static Axis byName(String name) {
            return Axis.valueOf(name.toUpperCase());
        }
    }
}
