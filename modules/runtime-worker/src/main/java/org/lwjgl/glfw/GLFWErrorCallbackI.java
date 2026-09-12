package org.lwjgl.glfw;

/** LWJGL GLFW 错误回调接口 stub，避免 RenderSystem 反射扫描时 ClassNotFound。 */
@FunctionalInterface
public interface GLFWErrorCallbackI {
    void invoke(int error, long description);
}
