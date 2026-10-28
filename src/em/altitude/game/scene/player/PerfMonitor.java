package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.altitude.game.Player;
import em.opengl.render.RU;
import em.opengl.resource.font.Font;
import em.opengl.resource.font.builder.FontList;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * /opt perfhud, /opt perflog and /opt perf. Measures the interval between successive AltitudeGame.render calls
 * (menus included) and, once a second, summarises it with GC pauses, game ticks per second, ping and heap into a
 * {@link PerfStats.Sample}. perfhud draws it top right during play (two lines), perflog appends it to
 * config/client-perf.csv, /opt perf prints it. -Dclient.perflog=true logs from startup.
 */
public final class PerfMonitor {
    private PerfMonitor() {}

    static final boolean LOG_FROM_START = Boolean.getBoolean("client.perflog");
    private static double[] intervals = new double[512];
    private static int count, ticksAtStart;
    private static long lastFrame, windowStart;
    private static boolean gcInstalled, gcAvailable;
    private static volatile PerfStats.Sample last;
    private static ExecutorService logWriter;

    /** The latest one-second sample, or null before the first second. */
    static PerfStats.Sample last() { return last; }

    /** Once per frame, from {@link ClientFrame}. */
    static void frame(AltitudeGame game) {
        long now = System.nanoTime();
        if (!gcInstalled) {
            gcInstalled = true;
            try {
                Gc.install();
                gcAvailable = true;
            } catch (LinkageError e) {
                // Runtime without java.management: no GC figures.
            }
        }
        if (lastFrame == 0) {
            windowStart = now;
            ticksAtStart = game.getUpdateCount();
        } else {
            if (count == intervals.length) intervals = Arrays.copyOf(intervals, count * 2);
            intervals[count++] = (now - lastFrame) / 1e6;
        }
        lastFrame = now;
        if (now - windowStart < 1_000_000_000L) return;
        double seconds = (now - windowStart) / 1e9;
        int ticks = game.getUpdateCount();
        double[] gc = gcAvailable ? Gc.drain() : new double[3];
        Runtime rt = Runtime.getRuntime();
        PerfStats.Sample s = new PerfStats.Sample(System.currentTimeMillis(), sceneName(game), PerfStats.frames(intervals, count, seconds, refreshMs()),
                (int) gc[0], gc[1], gc[2], (ticks - ticksAtStart) / seconds, ping(game), (rt.totalMemory() - rt.freeMemory()) >> 20);
        last = s;
        count = 0;
        windowStart = now;
        ticksAtStart = ticks;
        if (LOG_FROM_START || ClientOptions.on("perflog")) log(s);
    }

    private static String sceneName(AltitudeGame game) {
        try {
            Object scene = game.getSceneManager().getCurrentScene();
            return scene == null ? "none" : scene.getClass().getSimpleName();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static int ping(AltitudeGame game) {
        try {
            if (game.getSceneManager().getCurrentScene() instanceof PlayerScene scene) {
                Player me = scene.getMyPlayer();
                if (me != null) return me.getPing();
            }
        } catch (RuntimeException e) {
            // Not in play.
        }
        return -1;
    }

    /** One refresh of the display the game is on, in ms (60 Hz when unknown). */
    private static double refreshMs() {
        try {
            int hz = (Display.isFullscreen() ? Display.getDisplayMode() : Display.getDesktopDisplayMode()).getFrequency();
            return 1000.0 / (hz > 0 ? hz : 60);
        } catch (RuntimeException | LinkageError e) {
            return 1000.0 / 60;
        }
    }

    /** Appends to config/client-perf.csv on a background thread (header first when the file is new). */
    private static synchronized void log(PerfStats.Sample s) {
        if (logWriter == null) {
            logWriter = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "client-perflog");
                t.setDaemon(true);
                return t;
            });
        }
        String row = s.csv();
        logWriter.execute(() -> {
            Path file = ClientOptions.file().resolveSibling("client-perf.csv");
            try {
                Files.createDirectories(file.getParent());
                boolean fresh = !Files.exists(file);
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    if (fresh) w.write(PerfStats.CSV_HEADER + "\n");
                    w.write(row + "\n");
                }
            } catch (IOException | RuntimeException e) {
                // Skip this row.
            }
        });
    }

    /** Hook: end of PlayerScene.render. Draws the perf lines top right. */
    public static void render(PlayerScene scene) {
        PerfStats.Sample s = last;
        if (s == null || !ClientOptions.on("perfhud")) return;
        try {
            Font font = scene.getGame().getResourceManager().getFont(FontList.VERDANA_11_BOLD_OUTLINE);
            int matrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_SCISSOR_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            HudDraw.hudProjection();
            try {
                HudDraw.resetTextureState();
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                HudDraw.clipToGameArea();
                RU.color(1f, 1f, 1f, 0.9f);
                // Top right, right-aligned: clear of the stock score (top centre) and spawn banner (top left).
                String[] lines = s.lines();
                for (int i = 0; i < lines.length; i++) font.render(lines[i], 1274, 714 - i * (font.getHeight() + 2), Font.RIGHT | Font.TOP);
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(matrix);
                GL11.glPopAttrib();
                HudDraw.resetTextureState();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Display only: never break the frame.
        }
    }

    /** GC pauses from the JMX notifications (they arrive on a JMX thread); drained once a second. */
    static final class Gc {
        private static int pauses;
        private static double totalMs, maxMs;

        static void install() {
            try {
                List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
                boolean zgc = beans.stream().anyMatch(b -> b.getName().startsWith("ZGC"));
                for (GarbageCollectorMXBean b : beans) {
                    if (!(b instanceof NotificationEmitter emitter) || !PerfStats.countsPauses(b.getName(), zgc)) continue;
                    emitter.addNotificationListener((n, handback) -> {
                        // com.sun.management.GarbageCollectionNotificationInfo, read as open data (no jdk.management).
                        if ("com.sun.management.gc.notification".equals(n.getType()) && n.getUserData() instanceof CompositeData data
                                && data.get("gcInfo") instanceof CompositeData info && info.get("duration") instanceof Long ms) {
                            record(ms);
                        }
                    }, null, null);
                }
            } catch (RuntimeException | LinkageError e) {
                // No GC figures (runtime without java.management).
            }
        }

        private static synchronized void record(double ms) {
            pauses++;
            totalMs += ms;
            maxMs = Math.max(maxMs, ms);
        }

        /** Pauses, total ms and longest ms since the last call. */
        static synchronized double[] drain() {
            double[] d = { pauses, totalMs, maxMs };
            pauses = 0;
            totalMs = maxMs = 0;
            return d;
        }
    }
}
