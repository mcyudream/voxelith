package online.yudream.voxelith.tile.domain.tile;

/**
 * 一个瓦片的几何（顶点坐标为瓦片局部坐标，worldMin/worldMax 为世界包围盒）。
 * 按透明度拆为两个分段：opaque（alphaMode MASK）与 translucent（水等，alphaMode BLEND），
 * glb 中各自成为独立 primitive；无半透明面片时 translucent 为空分段。
 *
 * Segment 内 colors 为 4 顶点 × RGB 染色（0~255，无染色面为白色），glb 中以 ubyte normalized
 * COLOR_0 编码；lights 为 4 顶点 × (sky, block, ao)：sky/block = 光照等级 ×17，ao = 遮挡级数 ×85，
 * glb 中以 ubyte normalized _LIGHT 编码。
 */
public record TileGeometry(Segment opaque, Segment translucent,
                           float[] worldMin, float[] worldMax) {

    /** 单分段几何（瓦片局部坐标）。 */
    public record Segment(float[] positions, float[] normals, float[] uvs,
                          byte[] colors, byte[] lights, int[] indices) {

        public static Segment empty() {
            return new Segment(new float[0], new float[0], new float[0],
                    new byte[0], new byte[0], new int[0]);
        }

        public boolean isEmpty() {
            return indices.length == 0;
        }

        public int vertexCount() {
            return positions.length / 3;
        }

        public int quadCount() {
            return indices.length / 6;
        }
    }

    public int vertexCount() {
        return opaque.vertexCount() + translucent.vertexCount();
    }

    public int quadCount() {
        return opaque.quadCount() + translucent.quadCount();
    }
}
