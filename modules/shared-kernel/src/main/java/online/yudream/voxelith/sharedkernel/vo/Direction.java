package online.yudream.voxelith.sharedkernel.vo;

/**
 * MC 六个轴向方向。单位法向量与游戏内一致（east = +x, up = +y, south = +z）。
 */
public enum Direction {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    private final int nx;
    private final int ny;
    private final int nz;

    Direction(int nx, int ny, int nz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
    }

    public int nx() {
        return nx;
    }

    public int ny() {
        return ny;
    }

    public int nz() {
        return nz;
    }

    public Direction opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    /** 解析 MC 资源中的方向名（"north"、"up" 等），大小写不敏感。 */
    public static Direction byName(String name) {
        return Direction.valueOf(name.toUpperCase());
    }
}
