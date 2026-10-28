package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.altitude.game.scene.SceneManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.opengl.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /opt cursorlock play: while flying, the cursor stays inside the game window (or the fullscreen screen), so a stray
 * click mid-match can't land on a second monitor and take focus away. Released in the game menu, the console and chat,
 * while the plane is dead (respawn and plane selection), when the window loses focus, and on quit. Uses LWJGL's own
 * Win32 clip (WindowsDisplay.setupCursorClipping, the ClipCursor call behind its grabbed-mouse mode) through reflection,
 * re-applied every frame, which also follows window moves and resizes and LWJGL resetting it on activation. Cursor
 * positions inside the window are unchanged. Any lookup or call failure turns it off for the session (stock cursor).
 */
public final class CursorLock {
    private CursorLock() {}

    private static final Logger LOG = LoggerFactory.getLogger(CursorLock.class);
    private static Method setup, reset, hwnd;
    private static Field impl;
    private static boolean looked, failed, clipped;

    /** True when the cursor should be confined: the option on, the window focused, flying, and no menu, console or chat open. */
    static boolean shouldLock(boolean option, boolean active, boolean playing, boolean alive, boolean chatting, boolean menu, boolean console) {
        return option && active && playing && alive && !chatting && !menu && !console;
    }

    /** Once per frame, from {@link ClientFrame}: confine or release the cursor. */
    static void frame(AltitudeGame game) {
        if (failed) return;
        try {
            boolean lock = "play".equals(ClientOptions.get("cursorlock")) && Display.isCreated() && wanted(game);
            if (lock) clip();
            else release();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private static boolean wanted(AltitudeGame game) {
        SceneManager sm = game.getSceneManager();
        if (sm == null || !(sm.getCurrentScene() instanceof PlayerScene scene)) return false;
        return shouldLock(true, Display.isActive(), true, scene.isMyPlaneAlive(), scene.isChatting(), sm.isGameMenuVisible(),
                sm.getConsoleUI() != null && sm.getConsoleUI().isVisible());
    }

    /** Hook: start of AltitudeGame.onFocusChanged(Z): release at once on focus loss (a minimised window renders no frames). */
    public static void focus(boolean focused) {
        if (!focused) releaseQuietly();
    }

    /** Hook: start of AltitudeGame.shutdown(): never leave the clip set on quit. */
    public static void shutdown() {
        releaseQuietly();
    }

    private static void releaseQuietly() {
        try {
            release();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private static void clip() throws ReflectiveOperationException {
        if (!lookUp()) return;
        long h = (Long) hwnd.invoke(impl.get(null));
        if (h == 0) return;
        setup.invoke(null, h);
        if (!clipped) LOG.info("CursorLock: cursor kept in the game window");
        clipped = true;
    }

    private static void release() throws ReflectiveOperationException {
        if (!clipped) return;
        clipped = false;
        if (lookUp()) reset.invoke(null);
        LOG.info("CursorLock: cursor released");
    }

    /** LWJGL 2.9.3's Windows display: static package-private clip calls and the window handle of the live display. */
    private static boolean lookUp() {
        if (looked) return !failed;
        looked = true;
        try {
            Class<?> wd = Class.forName("org.lwjgl.opengl.WindowsDisplay");
            impl = Display.class.getDeclaredField("display_impl");
            impl.setAccessible(true);
            if (!wd.isInstance(impl.get(null))) throw new IllegalStateException("not the Windows display: " + impl.get(null));
            setup = wd.getDeclaredMethod("setupCursorClipping", long.class);
            reset = wd.getDeclaredMethod("resetCursorClipping");
            hwnd = wd.getDeclaredMethod("getHwnd");
            for (Method m : new Method[] {setup, reset, hwnd}) m.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    private static void fail(Throwable e) {
        if (failed) return;
        failed = true;
        LOG.warn("CursorLock: turned off (stock cursor): {}", e.toString());
        if (clipped && reset != null) {
            clipped = false;
            try { reset.invoke(null); } catch (ReflectiveOperationException | RuntimeException | LinkageError e2) { /* nothing more to do */ }
        }
    }
}
