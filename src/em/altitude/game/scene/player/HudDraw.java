package em.altitude.game.scene.player;

import em.opengl.render.RU;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Shared drawing state for client HUD overlays: the stock 1280x720 HUD projection, scissoring and texture state.
 * Overlays push GL state, call {@link #hudProjection()}, draw, pop, and always finish with
 * {@link #resetTextureState()}: RU caches the texture enable and binding, and a glPopAttrib that disagrees with that
 * cache once turned the whole screen white.
 */
final class HudDraw {
    private HudDraw() {}

    private static Field orthoRightField, orthoTopField;
    private static boolean orthoFieldsRead;
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    static float hudRight = 1280, hudTop = 720, hudOffsetX, hudOffsetY;

    /** Load the projection the stock HUD draws with (RU's letterboxed ortho), so overlays line up with it. */
    static void hudProjection() {
        if (!orthoFieldsRead) {
            orthoFieldsRead = true;
            try {
                orthoRightField = RU.class.getDeclaredField("orthoRight");
                orthoRightField.setAccessible(true);
                orthoTopField = RU.class.getDeclaredField("orthoTop");
                orthoTopField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                orthoRightField = orthoTopField = null;
            }
        }
        float right = 1280, top = 720;
        if (orthoRightField != null) {
            try {
                right = orthoRightField.getFloat(null);
                top = orthoTopField.getFloat(null);
            } catch (ReflectiveOperationException e) {
                // Keep 1280x720.
            }
        }
        if (!(right > 0 && top > 0)) { right = 1280; top = 720; }
        hudRight = right;
        hudTop = top;
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, right, 0, top, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        RU.loadIdentityAndTranslate();
        MATRIX.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        hudOffsetX = MATRIX.get(12);
        hudOffsetY = MATRIX.get(13);
    }

    /** Scissor to a rectangle given in HUD units, through the projection loaded by hudProjection. */
    static void scissorHud(float left, float bottom, float width, float height) {
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        float vx = VIEWPORT.get(0), vy = VIEWPORT.get(1), vw = VIEWPORT.get(2), vh = VIEWPORT.get(3);
        if (vw <= 0 || vh <= 0) return;
        float sx = vw / hudRight, sy = vh / hudTop, ox = vx + hudOffsetX * sx, oy = vy + hudOffsetY * sy;
        int x = Math.round(ox + left * sx), y = Math.round(oy + bottom * sy);
        GL11.glScissor(x, y, Math.max(1, Math.round(ox + (left + width) * sx) - x), Math.max(1, Math.round(oy + (bottom + height) * sy) - y));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
    }

    /** Keep overlays inside the 1280x720 game area (out of letterbox bars). Restore with GL_SCISSOR_BIT. */
    static void clipToGameArea() { scissorHud(0, 0, 1280, 720); }

    /** Leave a consistent untextured state, so the next sprite or font rebinds its texture. */
    static void resetTextureState() {
        RU.setActiveTexture(null);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
