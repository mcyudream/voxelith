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

    @Test
    void glStubReportsTextureSizeAndRecordsLastUpload() {
        assertThat(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE))
                .isEqualTo(8192);
        org.lwjgl.opengl.GL11.glTexImage2D(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                1024, 512, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, 0L);
        assertThat(org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH))
                .isEqualTo(1024);
        assertThat(org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT))
                .isEqualTo(512);
    }
}
