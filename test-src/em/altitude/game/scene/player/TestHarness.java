package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.altitude.game.Player;
import em.altitude.game.menu.TutorialList;
import em.altitude.game.scene.SceneManager;
import em.altitude.game.scene.TitleScene;
import em.altitude.game.server.config.BotConfig;
import em.altitude.game.server.config.TutorialConfig;
import em.opengl.render.RU;
import em.opengl.resource.image.Image;
import em.opengl.resource.image.ImageManager;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TEST BUILD ONLY. Drives the client for unattended visual tests, controlled by -D properties and command files:
 * <pre>
 * -Dclient.test.dir=DIR         control folder: *.cmd files are run once and deleted; screenshots go here too, unless
 *                               an "out" command names another folder
 * -Dclient.test.skirmish=MAP    once offline login reaches the menu, start a local skirmish on MAP (e.g. ball_arcade)
 * -Dclient.test.bots=N          bots in that skirmish (default 6); the camera follows a bot
 * </pre>
 * Command file lines: "shot NAME" (save the next frame as NAME.png), "gallery on|world|off" (draw every plane sprite,
 * or a sample of the other world art, in a fixed grid over the game, for like-for-like comparisons), "out DIR" (write
 * later screenshots to DIR, so one running client can serve several test runs), "rescan" (re-read the hdsprites
 * replacement folder), "chat TEXT" (deliver TEXT as a server chat line, e.g. the sudden-death announcement), "clock SECONDS"
 * (give the round a clock, which the local skirmish lacks), "quit".
 * Progress goes to the log as "TestHarness: ...".
 * Hooks: start and end of AltitudeGame.render.
 */
public final class TestHarness {
    private TestHarness() {}

    private static final Logger LOG = LoggerFactory.getLogger(TestHarness.class);
    static final String DIR = System.getProperty("client.test.dir");
    static final String SKIRMISH = System.getProperty("client.test.skirmish");
    static final int BOTS = Integer.getInteger("client.test.bots", 6);
    private static boolean started, matchLogged;
    private static String gallery = "off";
    private static long nextPoll, nextFollow;
    private static String shotName;
    private static volatile String outDir = DIR;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "test-shots");
        t.setDaemon(true);
        return t;
    });

    private static volatile long lastFrame;
    private static Thread renderThread;

    /** Logs the render thread's stack when no frame has started for 10 s (the bundled runtime has no jstack). */
    private static void startWatchdog() {
        Thread w = new Thread(() -> {
            boolean reported = false;
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                boolean stuck = System.nanoTime() - lastFrame > 10_000_000_000L;
                if (stuck && !reported) {
                    StringBuilder sb = new StringBuilder("TestHarness: no frame for 10 s; render thread:");
                    for (StackTraceElement e : renderThread.getStackTrace()) sb.append("\n    at ").append(e);
                    LOG.warn(sb.toString());
                }
                reported = stuck;
            }
        }, "test-watchdog");
        w.setDaemon(true);
        w.start();
    }

    /** Hook: start of AltitudeGame.render. */
    public static void frame(AltitudeGame game) {
        if (DIR == null) return;
        lastFrame = System.nanoTime();
        if (renderThread == null) { renderThread = Thread.currentThread(); startWatchdog(); }
        try {
            long now = System.nanoTime();
            if (now >= nextPoll) {
                nextPoll = now + 250_000_000L;
                readCommands(game);
            }
            SceneManager sm = game.getSceneManager();
            if (sm == null) return;
            if (SKIRMISH != null && !started && sm.getCurrentScene() instanceof TitleScene && sm.isGameMenuVisible()) {
                started = true;
                BotConfig bots = TutorialList.BOT_CONFIG.clone();
                bots.setNumberOfBots(BOTS);
                bots.setBotSpectateThreshold(BOTS);
                sm.getGameMenu().startTutorial(new TutorialConfig("Skirmish", SKIRMISH, "Win", bots, null, false));
                LOG.info("TestHarness: starting skirmish {} with {} bots", SKIRMISH, BOTS);
            }
            if (sm.getCurrentScene() instanceof PlayerScene scene) {
                // The plane selection opens at the start of a match and covers the view; the test player only watches.
                if (scene.isPlaneSelectionOpen()) scene.getPlaneSelectionUi().setVisible(false);
                if (now >= nextFollow) {
                    nextFollow = now + 3_000_000_000L;
                    follow(scene);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("TestHarness: frame failed", e);
        }
    }

    /** Keep the chase camera on a living bot, so planes stay on screen. */
    private static void follow(PlayerScene scene) {
        Player target = scene.getChaseCameraSpecificPlayerTarget();
        if (target != null && target.getPlane() != null) return;
        for (Object o : scene.getPlayers()) {
            Player p = (Player) o;
            if (p.getPlayerId() != scene.getMyPlayerId() && p.getPlane() != null) {
                scene.setChaseCameraSpecificPlayerTarget(p.getPlayerId());
                if (!matchLogged) {
                    matchLogged = true;
                    LOG.info("TestHarness: match running, following player {}", p.getPlayerId());
                }
                return;
            }
        }
    }

    private static void readCommands(AltitudeGame game) {
        File[] files = new File(DIR).listFiles((d, n) -> n.endsWith(".cmd"));
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    String[] w = line.trim().split("\\s+");
                    if (w[0].equals("shot") && w.length > 1) shotName = w[1];
                    else if (w[0].equals("out") && w.length > 1) outDir = line.trim().substring(4).trim();
                    else if (w[0].equals("rescan")) SpriteUpscale.rescan();
                    else if (w[0].equals("gallery")) gallery = w.length > 1 ? w[1] : "off";
                    else if (w[0].equals("chat") && w.length > 1 && game.getSceneManager().getCurrentScene() instanceof PlayerScene scene) {
                        scene.onChatEvent(-1, false, line.trim().substring(5).trim()); // as the server sends it: player id -1
                    }
                    else if (w[0].equals("clock") && w.length > 1) setClock(game, Float.parseFloat(w[1]));
                    else if (w[0].equals("quit")) { LOG.info("TestHarness: quit"); System.exit(0); }
                    LOG.info("TestHarness: command {}", line.trim());
                }
                Files.delete(f.toPath());
            } catch (Exception e) {
                LOG.warn("TestHarness: command file {} failed", f, e);
            }
        }
    }

    /**
     * A round clock of the given seconds on this client's game mode, as a server with a round time limit gives it (the
     * local skirmish has none). Display only: the server still runs the round without one.
     */
    private static void setClock(AltitudeGame game, float seconds) throws ReflectiveOperationException {
        Field f = em.altitude.game.GameMode.class.getDeclaredField("roundDelay");
        f.setAccessible(true);
        f.set(game.getGameMode(), new em.common.math.regulator.Delay(em.altitude.game.Const.sec(seconds)));
    }

    /** Hook: end of AltitudeGame.render (before the buffers are swapped). */
    public static void endFrame(AltitudeGame game) {
        if (DIR == null) return;
        try {
            if (!gallery.equals("off")) drawGallery(game);
            if (shotName != null) {
                String name = shotName;
                shotName = null;
                saveShot(name);
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("TestHarness: endFrame failed", e);
        }
    }

    private static void saveShot(String name) {
        String dir = outDir;
        int w = Display.getWidth(), h = Display.getHeight();
        ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        WRITER.execute(() -> {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = ((h - 1 - y) * w + x) * 4;
                    img.setRGB(x, y, (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | (px.get(i + 2) & 255));
                }
            }
            try {
                Path out = Path.of(dir, name + ".png");
                ImageIO.write(img, "png", out.toFile());
                LOG.info("TestHarness: shot {} {}x{}", out, w, h);
            } catch (Exception e) {
                LOG.warn("TestHarness: shot {} failed", name, e);
            }
        });
    }

    private static Field cachedField;
    private static final Map<String, List<String>> PINNED = new java.util.HashMap<>();

    /** World art besides the planes, for "gallery world": one folder of images per group, a few images each. */
    private static final String[] WORLD = { "render/skins/", "render/map/", "render/powerups/", "planes/", "effects/", "map/" };

    @SuppressWarnings("unchecked")
    private static Map<String, Image> loadedImages(AltitudeGame game) throws ReflectiveOperationException {
        ImageManager im = game.getResourceManager().getImageManager();
        if (cachedField == null) {
            cachedField = ImageManager.class.getDeclaredField("cachedImageByPath");
            cachedField.setAccessible(true);
        }
        return new TreeMap<>((Map<String, Image>) cachedField.get(im));
    }

    /** Loaded world images outside render/planes, grouped by folder, spread evenly over at most 180 cells. */
    private static List<String> worldSprites(AltitudeGame game) throws ReflectiveOperationException {
        Map<String, List<String>> folders = new TreeMap<>();
        for (Map.Entry<String, Image> e : loadedImages(game).entrySet()) {
            String path = e.getKey();
            boolean world = false;
            for (String p : WORLD) world |= path.startsWith(p);
            if (!world || path.startsWith("planes/powerup/wall/beam") || path.contains("texture")) continue;
            folders.computeIfAbsent(path.substring(0, path.lastIndexOf('/')), k -> new ArrayList<>()).add(path);
        }
        int each = Math.max(1, 180 / Math.max(1, folders.size()));
        List<String> picked = new ArrayList<>();
        for (List<String> f : folders.values()) for (int i = 0; i < each && i < f.size(); i++) picked.add(f.get(i * f.size() / Math.min(each, f.size())));
        return picked.subList(0, Math.min(180, picked.size()));
    }

    /** Every loaded plane sprite (render/planes), grouped by plane type and team colour, a few angles each. */
    private static List<String> gallerySprites(AltitudeGame game) throws ReflectiveOperationException {
        Map<String, Image> all = loadedImages(game);
        Map<String, List<String>> groups = new TreeMap<>();
        for (Map.Entry<String, Image> e : all.entrySet()) {
            String path = e.getKey();
            if (!path.startsWith("render/planes/") || path.startsWith("render/planes/random/")) continue;
            String[] parts = path.split("/");
            if (parts.length != 4) continue; // render/planes/TYPE/COLOUR_ANGLE.png
            String colour = parts[3].substring(0, parts[3].lastIndexOf('_'));
            groups.computeIfAbsent(parts[2] + "/" + colour, k -> new ArrayList<>()).add(path);
        }
        List<String> picked = new ArrayList<>();
        for (List<String> g : groups.values()) for (int i = 0; i < 3 && i * 7 < g.size(); i++) picked.add(g.get(i * 7));
        return picked;
    }

    /** Dark backing over the HUD area and the sprites in a fixed grid, in the stock 1280x720 HUD projection. */
    private static void drawGallery(AltitudeGame game) {
        List<Image> sprites = new ArrayList<>();
        try {
            // The page is chosen once per mode and kept, so later shots show the same images even as more load.
            List<String> paths = PINNED.get(gallery);
            if (paths == null) PINNED.put(gallery, paths = gallery.equals("world") ? worldSprites(game) : gallerySprites(game));
            Map<String, Image> loaded = loadedImages(game);
            for (String p : paths) if (loaded.get(p) != null) sprites.add(loaded.get(p));
        } catch (ReflectiveOperationException e) {
            return;
        }
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
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0.11f, 0.23f, 0.32f, 1f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(0, 0); GL11.glVertex2f(1280, 0); GL11.glVertex2f(1280, 720); GL11.glVertex2f(0, 720);
            GL11.glEnd();
            int cols = 18, cell = 70;
            for (int i = 0; i < sprites.size(); i++) {
                Image img = sprites.get(i);
                // World art comes in all sizes: shrink it to its cell (the plane page keeps the stock size).
                float s = gallery.equals("world") ? Math.min(1f, (cell - 4f) / Math.max(img.getWidth(), img.getHeight())) : 1f;
                float x = 10 + (i % cols) * cell + (cell - img.getWidth() * s) / 2f, y = 710 - (i / cols + 1) * cell + (cell - img.getHeight() * s) / 2f;
                RU.color(1f, 1f, 1f, 1f);
                RU.push();
                RU.translate(x, y);
                RU.scale(s);
                img.render();
                RU.pop();
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrix);
            GL11.glPopAttrib();
            HudDraw.resetTextureState();
        }
    }
}
