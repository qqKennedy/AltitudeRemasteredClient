package em.altitude.game.scene.player;

import em.opengl.ui.GLFrame;
import org.lwjgl.opengl.Display;

/**
 * /opt framepacing: replaces the stock frame limiter (GLFrame.sleep). The stock limiter sleeps a whole number of
 * milliseconds and moves that number by 1 ms every frame toward the target rate, so frame times alternate between
 * neighbouring millisecond steps. This keeps a fixed deadline per frame instead: sleep while more than SPIN_NS
 * remains, then spin to the deadline. A frame more than one period late restarts the schedule instead of rushing.
 * A hidden window, no target rate, or the option off: the stock limiter runs.
 * -Dclient.framepacing=true forces it on (for measurements).
 */
public final class FramePacer {
    private FramePacer() {}

    /** Windows sleeps overshoot by up to about 1-2 ms; the remainder is spun. */
    static final long SPIN_NS = 2_000_000L;
    static final boolean FORCED = Boolean.getBoolean("client.framepacing");
    static volatile boolean enabled = FORCED;
    private static long deadline;

    /** Once per frame, from {@link ClientFrame}. */
    static void setEnabled(boolean on) { enabled = on || FORCED; }

    /** Hook: start of GLFrame.sleep(); true when the frame was paced here and the stock sleep is skipped. */
    public static boolean sleep(GLFrame frame) {
        if (!enabled || !Display.isVisible()) { deadline = 0; return false; }
        double target = frame.getCycleTargetMs();
        if (!(target > 0)) { deadline = 0; return false; }
        frame.getGamePerformanceMonitor().beginCycle(); // as the stock sleep does
        long now = System.nanoTime();
        deadline = next(deadline, now, Math.round(target * 1e6));
        waitUntil(deadline);
        return true;
    }

    /** The next deadline: one period after the last one, or now on the first frame or when more than a period late. */
    static long next(long last, long now, long period) {
        long d = last + period;
        return last == 0 || now - d > period ? now : d;
    }

    private static void waitUntil(long deadline) {
        for (long left; (left = deadline - System.nanoTime()) > 0;) {
            if (left > SPIN_NS) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
