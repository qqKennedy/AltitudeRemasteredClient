package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.altitude.game.scene.SceneManager;
import em.altitude.game.ui.ManagerToken;
import em.altitude.game.ui.Panel;
import em.altitude.game.ui.TextArea;
import em.altitude.game.ui.Window;
import em.common.debug.ImageHelper;
import em.common.io.FileHelper;
import em.common.math.regulator.Delay;
import em.opengl.render.Color;
import em.opengl.render.RU;
import em.opengl.render.Screenshot;
import em.opengl.resource.font.builder.FontList;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screenshots without the freeze. Stock SceneManager.takeScreenshot reads the screen, converts it and encodes the PNG
 * on the render thread, which stalls the game for hundreds of ms at 4K. Here only the pixel read (the same
 * Screenshot.getScreenRgb call, so the same pixels) stays on the render thread; the flip, the stock conversion
 * (ImageHelper.createImageFromBytes) and the PNG write run on a worker. When the file is written, the stock "Saved"
 * console line and dialog appear on the next frame. Same folder and file name as stock. Anything unexpected before the
 * worker starts falls back to the stock method.
 */
public final class Screenshots {
    private Screenshots() {}

    private static final Logger LOG = LoggerFactory.getLogger(Screenshots.class);
    private static final ConcurrentLinkedQueue<String> SAVED = new ConcurrentLinkedQueue<>();
    private static ExecutorService worker;
    /** Set by the test harness to time the stock method against this one. */
    static volatile boolean stock;

    /** Hook: start of SceneManager.takeScreenshot(); true when taken here and the stock method is skipped. */
    public static boolean take(SceneManager scenes) {
        if (stock) return false;
        try {
            File dir = new File(scenes.getGame().getDirectoryManager().getDataDirectory(), "screenshots");
            FileHelper.ensureFileIsDirectory(dir);
            File file = new File(dir, "altitude " + ((SimpleDateFormat) field("SCREENSHOT_DATE_FORMAT").get(null)).format(new Date()) + ".png");
            int w = RU.getDisplayWidth(), h = RU.getDisplayHeight();
            byte[] rgb = Screenshot.getScreenRgb(0, 0, w, h, false);
            worker().execute(() -> {
                try {
                    // As Screenshot.getScreenAsBufferedImage: rows bottom-up from GL, flipped, then the stock conversion.
                    byte[] flipped = new byte[rgb.length];
                    int row = w * 3;
                    for (int y = 0; y < h; y++) System.arraycopy(rgb, y * row, flipped, (h - y - 1) * row, row);
                    BufferedImage img = ImageHelper.createImageFromBytes(flipped, w, h, false);
                    ImageIO.write(img, "png", file);
                    SAVED.add("Saved \"" + file.getName() + "\" to \"" + dir.getName() + "\"");
                } catch (Exception | LinkageError e) {
                    LOG.error("Error taking screenshot", e);
                }
            });
            return true;
        } catch (Exception | LinkageError e) {
            LOG.warn("Screenshots: stock screenshot instead: {}", e.toString());
            return false;
        }
    }

    private static synchronized ExecutorService worker() {
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "client-screenshots"); t.setDaemon(true); return t; });
        return worker;
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field f = SceneManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Once per frame, from {@link ClientFrame}: for each finished file, the stock console line and dialog. */
    static void frame(AltitudeGame game) {
        for (String message; (message = SAVED.poll()) != null; ) {
            try {
                SceneManager scenes = game.getSceneManager();
                scenes.getConsoleUI().appendConsoleText(message, false);
                if (PlayerScene.CAPTURE_MODE || PlayerScene.CAPTURE_MODE_SCREENSHOT) continue;
                dialog(game, scenes, message);
            } catch (Exception | LinkageError e) {
                LOG.warn("Screenshots: {} (no dialog: {})", message, e.toString());
            }
        }
    }

    /** The stock dialog: a green line at the bottom left, replacing any earlier one, closed by the stock timer. */
    private static void dialog(AltitudeGame game, SceneManager scenes, String message) throws ReflectiveOperationException {
        Field windowField = field("screenshotDialogWindow");
        Window old = (Window) windowField.get(scenes);
        if (old != null) {
            old.setVisible(false);
            windowField.set(scenes, null);
        }
        Window window = new Window(null);
        Panel panel = window.addPanel(0, 0);
        TextArea text = panel.addTextArea(message, game.getResourceManager().getFont(FontList.VERDANA_16_BOLD_OUTLINE), Color.GREEN, 0, 0);
        text.setText("");
        ManagerToken token = text.append(message, true);
        field("screenshotDialogManagerToken").set(scenes, token);
        text.setSize(text.getPreferredSize());
        panel.setSize(panel.getPreferredSize());
        window.setSize(window.getPreferredSize());
        window.setPos(0, 720 - window.getHeight());
        window.setVisible(true);
        windowField.set(scenes, window);
        ((Delay) field("screenshotDialogTimer").get(scenes)).reset();
    }
}
