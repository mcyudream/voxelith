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
    public static final int GL_RGBA8 = 0x8058;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    public static final int GL_TEXTURE_WRAP_T = 0x2803;
    public static final int GL_CLAMP = 0x2900;
    public static final int GL_REPEAT = 0x2901;
    public static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    public static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
    public static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
    public static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
    public static final int GL_TEXTURE_WIDTH = 0x1000;
    public static final int GL_TEXTURE_HEIGHT = 0x1001;
    public static final int GL_MAX_TEXTURE_SIZE = 0x0D33;

    private static int nextTexture = 1;
    private static int lastTexWidth;
    private static int lastTexHeight;

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
        if (pname == GL_MAX_TEXTURE_SIZE) {
            return 8192;
        }
        return 0;
    }

    public static int glGenTextures() {
        return nextTexture++;
    }

    public static void glGenTextures(int[] textures) {
        for (int i = 0; i < textures.length; i++) {
            textures[i] = nextTexture++;
        }
    }

    public static void glBindTexture(int target, int texture) {
    }

    public static void glDeleteTextures(int texture) {
    }

    public static void glDeleteTextures(int[] textures) {
    }

    public static void glTexParameteri(int target, int pname, int param) {
    }

    public static void glTexParameterf(int target, int pname, float param) {
    }

    public static void glPixelStorei(int pname, int param) {
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, long pixels) {
        lastTexWidth = width;
        lastTexHeight = height;
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, java.nio.ByteBuffer pixels) {
        lastTexWidth = width;
        lastTexHeight = height;
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, java.nio.IntBuffer pixels) {
        lastTexWidth = width;
        lastTexHeight = height;
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                       int format, int type, long pixels) {
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                       int format, int type, java.nio.ByteBuffer pixels) {
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        if (pname == GL_TEXTURE_WIDTH) {
            return lastTexWidth;
        }
        if (pname == GL_TEXTURE_HEIGHT) {
            return lastTexHeight;
        }
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
