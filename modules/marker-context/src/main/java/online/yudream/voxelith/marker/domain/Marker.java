package online.yudream.voxelith.marker.domain;

import java.util.List;

/**
 * 地图标注（sealed：类型即 schema 的 discriminated union）。
 *
 * <p>与 {@code @yudream/voxelith-core} 的 {@code markerSchema} 对齐：前端 zod 校验、
 * 后端 record 双份模型互为依据，任一侧加类型都要同步另一侧。</p>
 *
 * <p>坐标都是**世界坐标**（方块），渲染层负责减浮点原点（靠场景根平移，见 MarkerLayer）。</p>
 */
public sealed interface Marker
        permits Marker.Poi, Marker.Line, Marker.Shape, Marker.Extrude, Marker.Box {

    String id();

    String label();

    MarkerStyle style();

    /** 低于该视距隐藏（方块，0 = 不隐藏）。 */
    double minDistance();

    /** 高于该视距隐藏（方块）。 */
    double maxDistance();

    /** 三维世界坐标。 */
    record Vec3(double x, double y, double z) {
    }

    /** XZ 平面顶点（多边形与区域轮廓用）。 */
    record Vec2(double x, double z) {
    }

    /** 点标注：图钉 + 名称，最常见的一类。 */
    record Poi(String id, String label, MarkerStyle style, double minDistance, double maxDistance,
               Vec3 position, String detailHtml) implements Marker {
    }

    /** 折线（巡逻路线、边界示意）。 */
    record Line(String id, String label, MarkerStyle style, double minDistance, double maxDistance,
                List<Vec3> points) implements Marker {

        public Line {
            points = List.copyOf(points);
            requirePoints(points.size(), 2, "折线至少需要 2 个点");
        }
    }

    /** XZ 多边形（可带洞），在 shapeY 高度铺一层。 */
    record Shape(String id, String label, MarkerStyle style, double minDistance, double maxDistance,
                 List<Vec2> shape, List<List<Vec2>> holes, double shapeY) implements Marker {

        public Shape {
            shape = List.copyOf(shape);
            holes = holes == null ? List.of() : List.copyOf(holes);
            requirePoints(shape.size(), 3, "多边形至少需要 3 个顶点");
        }
    }

    /** XZ 多边形沿 Y 拉伸的棱柱（体块示意，如建筑轮廓）。 */
    record Extrude(String id, String label, MarkerStyle style, double minDistance, double maxDistance,
                   List<Vec2> shape, List<List<Vec2>> holes, double shapeMinY,
                   double shapeMaxY) implements Marker {

        public Extrude {
            shape = List.copyOf(shape);
            holes = holes == null ? List.of() : List.copyOf(holes);
            requirePoints(shape.size(), 3, "拉伸体至少需要 3 个顶点");
            if (shapeMaxY <= shapeMinY) {
                throw new IllegalArgumentException("shapeMaxY 必须大于 shapeMinY: "
                        + shapeMinY + " -> " + shapeMaxY);
            }
        }
    }

    /** 轴对齐盒子（划定范围、标出地下空间）。 */
    record Box(String id, String label, MarkerStyle style, double minDistance, double maxDistance,
               Vec3 min, Vec3 max) implements Marker {

        public Box {
            if (max.x() < min.x() || max.y() < min.y() || max.z() < min.z()) {
                throw new IllegalArgumentException("盒子 max 必须各轴都不小于 min");
            }
        }
    }

    private static void requirePoints(int count, int least, String message) {
        if (count < least) {
            throw new IllegalArgumentException(message + "，收到 " + count + " 个");
        }
    }
}
