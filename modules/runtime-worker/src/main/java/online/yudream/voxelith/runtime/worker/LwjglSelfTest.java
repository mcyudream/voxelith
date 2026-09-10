package online.yudream.voxelith.runtime.worker;

import com.google.gson.JsonArray;

import java.util.Map;

/**
 * LWJGL stub 自检：逐项调用 stub 函数，验证无 native 环境下类链接与调用成立。
 * 任一项抛 Throwable 即记入 failures（stub 覆盖缺口的早期信号）。
 */
final class LwjglSelfTest {

    static void run(Map<String, Boolean> checks, JsonArray failures) {
        check(checks, failures, "lwjgl.glfw.init", () -> {
            if (!org.lwjgl.glfw.GLFW.glfwInit()) {
                throw new IllegalStateException("glfwInit 返回 false");
            }
            org.lwjgl.glfw.GLFW.glfwTerminate();
        });
        check(checks, failures, "lwjgl.glfw.window", () -> {
            long window = org.lwjgl.glfw.GLFW.glfwCreateWindow(16, 16, "self-test", 0L, 0L);
            if (window == 0L) {
                throw new IllegalStateException("glfwCreateWindow 返回 0");
            }
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
            org.lwjgl.glfw.GLFW.glfwSwapInterval(0);
            org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);
        });
        check(checks, failures, "lwjgl.gl.capabilities", () -> {
            if (org.lwjgl.opengl.GL.createCapabilities() == null) {
                throw new IllegalStateException("createCapabilities 返回 null");
            }
            org.lwjgl.opengl.GL.destroyCapabilities();
        });
        check(checks, failures, "lwjgl.gl11.query", () -> {
            String version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            if (version == null) {
                throw new IllegalStateException("glGetString 返回 null");
            }
            if (org.lwjgl.opengl.GL11.glGetError() != org.lwjgl.opengl.GL11.GL_NO_ERROR) {
                throw new IllegalStateException("glGetError 非 NO_ERROR");
            }
        });
        check(checks, failures, "lwjgl.gl30.vao", () -> {
            int vao = org.lwjgl.opengl.GL30.glGenVertexArrays();
            org.lwjgl.opengl.GL30.glBindVertexArray(vao);
            org.lwjgl.opengl.GL30.glDeleteVertexArrays(vao);
        });
    }

    private static void check(Map<String, Boolean> checks, JsonArray failures,
                              String name, Runnable probe) {
        try {
            probe.run();
            checks.put(name, true);
        } catch (Throwable t) {
            checks.put(name, false);
            failures.add(name + ": " + t);
        }
    }

    private LwjglSelfTest() {
    }
}
