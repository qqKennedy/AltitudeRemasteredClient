package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.opengl.render.RU;
import em.opengl.ui.GLFrame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /opt window normal|resizable, applied when the window is next created. resizable: the windowed-mode window can be
 * dragged to any size or maximised; each resize redoes the stock screen setup (viewport and HUD projection), so the
 * HUD stays letterboxed at 1280x720 virtual units.
 * <p>
 * Borderless fullscreen needs no code: the stock graphicsOptions fullscreen="false" undecorated="true"
 * overrideResolution="W,H" overrideLocation="0,0" in options.xml already do it.
 */
public final class WindowMode {
    private WindowMode() {}

    private static final Logger LOG = LoggerFactory.getLogger(WindowMode.class);
    private static final Field CURRENT = field(GLFrame.class, "currentDisplayMode"), SCISSOR_DEPTH = field(RU.class, "scissorDepth"),
            DISPLAY_IMPL = field(Display.class, "display_impl"), DISPLAY_WIDTH = field(Display.class, "width"), DISPLAY_HEIGHT = field(Display.class, "height");
    private static final Method IMPL_RESIZED = implMethod("wasResized"), IMPL_WIDTH = implMethod("getWidth"), IMPL_HEIGHT = implMethod("getHeight");
    private static boolean failed;

    private static Method implMethod(String name) {
        try {
            Method m = Class.forName("org.lwjgl.opengl.DisplayImplementation").getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Hook: end of GLFrame.setDisplayMode (display created or changed), with its fullscreen argument. */
    public static void afterCreate(boolean fullscreen) {
        try {
            if (!fullscreen) Display.setResizable("resizable".equals(ClientOptions.get("window")));
        } catch (RuntimeException | LinkageError e) {
            // Stock fixed-size window.
        }
    }

    /**
     * Once per frame, from {@link ClientFrame}: after a resize, redo the stock screen setup for the new size. The game
     * calls Display.swapBuffers and Display.processMessages but never Display.update, which is where LWJGL refreshes
     * Display.getWidth/getHeight and wasResized; so this reads the window's resize flag and size from LWJGL's display
     * implementation, as Display.update does, and stores the new size in Display for the rest of the game.
     */
    static void poll(AltitudeGame game) {
        if (failed) return;
        try {
            if (!Display.isCreated() || !Display.isResizable() || Display.isFullscreen()) return;
            Object impl = DISPLAY_IMPL.get(null);
            if (!(Boolean) IMPL_RESIZED.invoke(impl)) return;
            int w = (Integer) IMPL_WIDTH.invoke(impl), h = (Integer) IMPL_HEIGHT.invoke(impl);
            if (w <= 0 || h <= 0) return; // minimised
            DISPLAY_WIDTH.setInt(null, w);
            DISPLAY_HEIGHT.setInt(null, h);
            if (CURRENT != null) CURRENT.set(game.getFrame(), new DisplayMode(w, h));
            // setupScreen pushes a letterbox scissor that intersects any scissor still on RU's stack (32 deep): at the
            // start of a frame only its own previous one is there, so unwind it first.
            if (SCISSOR_DEPTH != null) for (int guard = 0; SCISSOR_DEPTH.getInt(null) >= 0 && guard < 32; guard++) RU.popScissor();
            GL11.glViewport(0, 0, w, h);
            RU.setupScreen(w, h, 1280, 720, !game.getOptions().getGraphicsOptions().isStrechToFillScreen());
            LOG.info("WindowMode: window resized to {}x{}", w, h);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true; // the stock setup stays
            LOG.warn("WindowMode: resizing turned off", e);
        }
    }
}
