package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.sharedkernel.vo.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型几何烘焙器：把展开后的模型元素烘焙成 quad 列表。
 * 顺序与原版一致：面投影（含默认 uv 推导与 uv 旋转）→ 元素旋转 → 变体旋转（先 x 后 y）。
 * uvLock 一期不实现（旋转变体的 uv 按默认投影取值）。
 */
public final class ModelGeometryBaker {

    private static final float[] CENTER = {8, 8, 8};

    /**
     * 引用不到贴图时的哨兵 id：原版对这种面渲染成 missing 贴图（而不是丢掉几何）。
     * 用哨兵而不是 null，是因为 null 会一路流到贴图采样与图集打包处抛 NPE；
     * 哨兵在资源侧查不到，tile 侧会把它落到图集兜底格（品红/黑棋盘格）。
     */
    public static final String MISSING_TEXTURE = online.yudream.voxelith.sharedkernel.vo.MissingTexture.ID;

    public List<Quad> bake(ModelData model, int variantX, int variantY) {
        List<Quad> quads = new ArrayList<>();
        for (ModelData.ElementData element : model.elements()) {
            for (var entry : element.faces().entrySet()) {
                Direction dir = Direction.byName(entry.getKey());
                ModelData.FaceData face = entry.getValue();
                // 没有 texture 键的面按原版语义「不渲染」：直接跳过而不是产出一个无贴图四边形。
                // 大量装饰类 mod（如方块小镇）会用这种面做占位/开关；放过去会一路 null 到图集打包处崩掉。
                if (face.texture() == null) {
                    continue;
                }

                float[][] corners = FaceProjection.corners(dir, element.from(), element.to());
                float[][] uvs = FaceProjection.uvs(dir, element.from(), element.to(), face.uv(), face.rotation());
                float[] normal = {dir.nx(), dir.ny(), dir.nz()};
                float[] cullVec = null;
                if (face.cullface() != null) {
                    Direction cull = Direction.byName(face.cullface());
                    cullVec = new float[]{cull.nx(), cull.ny(), cull.nz()};
                }

                if (element.rotation() != null && element.rotation().angle() != 0) {
                    ModelData.RotationData rotation = element.rotation();
                    for (float[] corner : corners) {
                        ModelRotation.rotateElement(
                                corner, rotation.origin(), rotation.axis(), rotation.angle(), rotation.rescale());
                    }
                    ModelRotation.rotateElementVector(normal, rotation.axis(), rotation.angle());
                    if (cullVec != null) {
                        ModelRotation.rotateElementVector(cullVec, rotation.axis(), rotation.angle());
                    }
                }

                if (variantX != 0 || variantY != 0) {
                    for (float[] corner : corners) {
                        ModelRotation.rotateVariant(corner, variantX, variantY);
                    }
                    ModelRotation.rotateVariantVector(normal, variantX, variantY);
                    if (cullVec != null) {
                        ModelRotation.rotateVariantVector(cullVec, variantX, variantY);
                    }
                }

                quads.add(new Quad(
                        flatten(corners), flatten(uvs), normalize(normal),
                        resolveTexture(model, face.texture()),
                        cullVec == null ? null
                                : ModelRotation.nearest(cullVec[0], cullVec[1], cullVec[2]).name().toLowerCase(),
                        face.tintIndex(), element.shade(), dir.name().toLowerCase()));
            }
        }
        return quads;
    }

    private static String resolveTexture(ModelData model, String reference) {
        if (reference == null) {
            return null;
        }
        String key = reference.startsWith("#") ? reference.substring(1) : reference;
        String resolved = model.textures().get(key);
        // 引用了未声明的变量：原版渲染成 missing 贴图，这里用哨兵 id 保持「有几何、无贴图」语义
        return resolved == null ? MISSING_TEXTURE : resolved;
    }

    private static float[] normalize(float[] v) {
        double len = Math.sqrt(v[0] * (double) v[0] + v[1] * (double) v[1] + v[2] * (double) v[2]);
        if (len == 0) {
            return v;
        }
        return new float[]{(float) (v[0] / len), (float) (v[1] / len), (float) (v[2] / len)};
    }

    private static float[] flatten(float[][] rows) {
        float[] flat = new float[rows.length * rows[0].length];
        for (int i = 0; i < rows.length; i++) {
            System.arraycopy(rows[i], 0, flat, i * rows[i].length, rows[i].length);
        }
        return flat;
    }
}
