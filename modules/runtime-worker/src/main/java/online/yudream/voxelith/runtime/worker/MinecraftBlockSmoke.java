package online.yudream.voxelith.runtime.worker;

/**
 * 无头启动前序门面：委托当前游戏 ClassLoader 匹配的 {@link HarvestAdapter}。
 */
public final class MinecraftBlockSmoke {

    public static final int MIN_EXPECTED_BLOCKS = FabricHarvestAdapter.MIN_EXPECTED_BLOCKS;

    public static int countRegisteredBlocks(ClassLoader gameClassLoader) throws Exception {
        HarvestAdapter adapter = HarvestAdapters.resolve(gameClassLoader);
        return adapter.bootstrapRegistries(gameClassLoader);
    }

    private MinecraftBlockSmoke() {
    }
}
