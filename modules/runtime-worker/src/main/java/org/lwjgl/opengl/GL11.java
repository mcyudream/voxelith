package org.lwjgl.opengl;

/** GL11 stub：常量与查询函数返回固定值，绘制/状态函数为 no-op。 */
public class GL11 {

    public static final int GL_NO_ERROR = 0;
    public static final int GL_FALSE = 0;
    public static final int GL_TRUE = 1;
    public static final int GL_VENDOR = 0x1F00;
    public static final int GL_RENDERER = 0x1F01;
    public static final int GL_VERSION = 0x1F02;
    public static final int GL_EXTENSIONS = 0x1F03;
    public static final int GL_TRIANGLES = 0x0004;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_UNSIGNED_INT = 0x1405;
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_RGB = 0x1907;

    protected GL11() {
    }

    public static String glGetString(int name) {
        return switch (name) {
            case GL_VENDOR -> "voxelith-stub";
            case GL_RENDERER -> "headless";
            case GL_VERSION -> "1.1-stub";
            case GL_EXTENSIONS -> "";
            default -> "";
        };
    }

    public static int glGetError() {
        return GL_NO_ERROR;
    }

    public static int glGetInteger(int pname) {
        return 0;
    }

    public static void glEnable(int cap) {
    }

    public static void glDisable(int cap) {
    }

    public static void glClearColor(float red, float green, float blue, float alpha) {
    }

    public static void glClear(int mask) {
    }

    public static void glViewport(int x, int y, int width, int height) {
    }
}
