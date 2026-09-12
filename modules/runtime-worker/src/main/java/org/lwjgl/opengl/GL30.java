package org.lwjgl.opengl;

/** GL30 stub（继承 GL11 常量/方法，对齐真实 LWJGL 层次）：VAO 等对象返回自增假句柄。 */
public class GL30 extends GL11 {

    public static final int GL_FRAMEBUFFER = 0x8D40;
    public static final int GL_ARRAY_BUFFER = 0x8892;
    public static final int GL_STATIC_DRAW = 0x88E4;

    private static int nextObject = 1;

    protected GL30() {
    }

    public static int glGenVertexArrays() {
        return nextObject++;
    }

    public static void glBindVertexArray(int array) {
    }

    public static void glDeleteVertexArrays(int array) {
    }

    public static int glGenFramebuffers() {
        return nextObject++;
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
    }

    public static void glDeleteFramebuffers(int framebuffer) {
    }

    public static void glGenerateMipmap(int target) {
    }
}
