package online.yudream.voxelith.runtime.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 进程内链接冒烟：LWJGL stub 类可加载、可调用、返回安全默认值。 */
class LwjglStubSmokeTest {

    @Test
    void glfwStubLinksAndReturnsSafeDefaults() {
        assertThat(org.lwjgl.glfw.GLFW.glfwInit()).isTrue();
        long window = org.lwjgl.glfw.GLFW.glfwCreateWindow(16, 16, "t", 0L, 0L);
        assertThat(window).isPositive();
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
        org.lwjgl.glfw.GLFW.glfwSwapBuffers(window);
        org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);
        org.lwjgl.glfw.GLFW.glfwTerminate();
    }

    @Test
    void glStubLinksAndReturnsSafeDefaults() {
        assertThat(org.lwjgl.opengl.GL.createCapabilities()).isNotNull();
        assertThat(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION))
                .isNotBlank();
        assertThat(org.lwjgl.opengl.GL11.glGetError())
                .isEqualTo(org.lwjgl.opengl.GL11.GL_NO_ERROR);
        int vao = org.lwjgl.opengl.GL30.glGenVertexArrays();
        assertThat(vao).isPositive();
        org.lwjgl.opengl.GL.destroyCapabilities();
    }
}
