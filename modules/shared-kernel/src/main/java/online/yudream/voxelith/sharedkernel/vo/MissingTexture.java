package online.yudream.voxelith.sharedkernel.vo;

/**
 * 「无贴图」哨兵约定（跨上下文共享）。
 *
 * <p>装饰类 mod（方块小镇等）的模型常出现两类面：</p>
 * <ul>
 *   <li>没有 {@code texture} 键的面 —— 原版语义是<b>不渲染</b>该面（bake 侧直接跳过）；</li>
 *   <li>引用了未声明贴图变量的面（如 {@code #missing}）—— 原版语义是渲染<b>missing 贴图</b>
 *       （黑/品红棋盘格），几何保留。</li>
 * </ul>
 *
 * <p>第二类需要一个双方都认得的 id：bake 侧把解析不到的面标成它，tile 侧把它的 UV 落到
 * 图集兜底格（品红/黑棋盘格）。放在 shared-kernel 是因为 bake-context 与 tile-context
 * 都要用它，而两者不得互相依赖对方的 domain。</p>
 */
public final class MissingTexture {

    /** 无贴图哨兵 id；同时也是瓦片图集第 0 格的兜底贴图 id。 */
    public static final String ID = "yudream:block/_missing";

    private MissingTexture() {
    }
}
