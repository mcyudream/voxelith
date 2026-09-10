package org.lwjgl.opengl;

/** GLCapabilities stub：能力标志一律 false（headless 无真实 GL 实现）。 */
public final class GLCapabilities {

    public final boolean OpenGL11 = false;
    public final boolean OpenGL30 = false;
    public final boolean OpenGL45 = false;

    GLCapabilities() {
    }
}
