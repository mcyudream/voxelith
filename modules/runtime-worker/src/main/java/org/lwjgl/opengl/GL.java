package org.lwjgl.opengl;

/** GL 能力上下文 stub（ADR 0001）：无 native，返回空能力对象。 */
public final class GL {

    private GL() {
    }

    public static GLCapabilities createCapabilities() {
        return new GLCapabilities();
    }

    public static void setCapabilities(GLCapabilities capabilities) {
    }

    public static GLCapabilities getCapabilities() {
        return new GLCapabilities();
    }

    public static void destroyCapabilities() {
    }
}
