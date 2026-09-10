package org.lwjgl.glfw;

/**
 * GLFW stub（headless worker 用，ADR 0001）：全部函数返回安全默认值，无 native 调用。
 * 签名对齐 LWJGL 3.3.x。覆盖缺口会在 worker 自检/运行期以 NoSuchMethodError 暴露，
 * 按缺口补方法即可。
 */
public final class GLFW {

    public static final int GLFW_FALSE = 0;
    public static final int GLFW_TRUE = 1;
    public static final int GLFW_VISIBLE = 0x00020004;
    public static final int GLFW_CLIENT_API = 0x00022001;
    public static final int GLFW_OPENGL_API = 0x00030001;
    public static final int GLFW_CONTEXT_VERSION_MAJOR = 0x00022002;
    public static final int GLFW_CONTEXT_VERSION_MINOR = 0x00022003;

    private static long nextWindow = 1;

    private GLFW() {
    }

    public static boolean glfwInit() {
        return true;
    }

    public static void glfwTerminate() {
    }

    public static void glfwWindowHint(int hint, int value) {
    }

    public static long glfwCreateWindow(int width, int height, CharSequence title,
                                        long monitor, long share) {
        return nextWindow++;
    }

    public static void glfwDestroyWindow(long window) {
    }

    public static void glfwMakeContextCurrent(long window) {
    }

    public static long glfwGetCurrentContext() {
        return 0L;
    }

    public static void glfwSwapInterval(int interval) {
    }

    public static void glfwSwapBuffers(long window) {
    }

    public static void glfwPollEvents() {
    }

    public static void glfwShowWindow(long window) {
    }

    public static boolean glfwWindowShouldClose(long window) {
        return false;
    }

    public static void glfwSetWindowShouldClose(long window, boolean value) {
    }

    public static long glfwGetPrimaryMonitor() {
        return 0L;
    }

    public static String glfwGetVersionString() {
        return "3.3.0-stub";
    }
}
