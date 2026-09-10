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

    public List<Quad> bake(ModelData model, int variantX, int variantY) {
        List<Quad> quads = new ArrayList<>();
        for (ModelData.ElementData element : model.elements()) {
            for (var entry : element.faces().entrySet()) {
                Direction dir = Direction.byName(entry.getKey());
                ModelData.FaceData face = entry.getValue();

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
        return model.textures().get(key);
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
