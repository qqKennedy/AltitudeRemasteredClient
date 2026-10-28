package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.opengl.ui.GLFrame;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /opt fps auto (default): the frame-rate target follows the monitor's refresh rate. GLFrame.setDisplayMode sets the
 * target from the display mode's frequency, which a window reports as 0, so every windowed player gets 60 fps; a
 * fullscreen mode is capped at 180. Here the target becomes the refresh rate of the mode (fullscreen) or of the
 * desktop (windowed). /opt fps stock keeps the stock target, and a frameRate override in options.xml
 * (overrideTargetFps, applied after setDisplayMode by AltitudeGame.postInitFrame) still wins. Game ticks are separate
 * from frames, so nothing but drawing changes.
 */
public final class FrameTarget {
    private FrameTarget() {}

    private static final Logger LOG = LoggerFactory.getLogger(FrameTarget.class);
    private static String applied;
    private static boolean fullscreen;

    /** Hook: end of GLFrame.setDisplayMode, with its fullscreen argument. */
    public static void afterCreate(GLFrame frame, boolean fullscreenMode) {
        fullscreen = fullscreenMode;
        applied = null;
        apply(frame, 0);
    }

    /** Once per frame, from {@link ClientFrame}: applies a change of /opt fps at once. */
    static void frame(AltitudeGame game) {
        if (applied != null && applied.equals(ClientOptions.get("fps"))) return;
        try {
            apply(game.getFrame(), game.getOptions().getGraphicsOptions().getOverrideTargetFps());
        } catch (RuntimeException | LinkageError e) {
            applied = ClientOptions.get("fps"); // stock target; don't retry every frame
        }
    }

    private static void apply(GLFrame frame, int override) {
        String mode = ClientOptions.get("fps");
        applied = mode;
        if (override > 0 || frame == null) return; // the options.xml override wins
        try {
            int hz = frequency(fullscreen ? Display.getDisplayMode() : Display.getDesktopDisplayMode());
            int fps = "auto".equals(mode) ? target(hz) : 0;
            if (fps <= 0) fps = stock(frequency(Display.getDisplayMode()));
            if (Math.abs(frame.getCycleTargetMs() - 1000.0 / fps) < 1e-6) return;
            frame.setCycleTargetMs(1000.0 / fps);
            LOG.info("FrameTarget: {} fps ({}, monitor {} Hz)", fps, "auto".equals(mode) ? "the monitor's refresh rate" : "stock", hz);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("FrameTarget: kept the stock frame-rate target: {}", e.toString());
        }
    }

    private static int frequency(DisplayMode m) { return m == null ? 0 : m.getFrequency(); }

    /** The auto target for a refresh rate: the rate itself when it is a real one, else 0 (keep stock). */
    static int target(int hz) { return hz >= 30 && hz <= 1000 ? hz : 0; }

    /** What GLFrame.setDisplayMode picks for a reported frequency: 60 when none is reported, at most 180. */
    static int stock(int hz) { return hz <= 0 ? 60 : Math.min(hz, 180); }
}
