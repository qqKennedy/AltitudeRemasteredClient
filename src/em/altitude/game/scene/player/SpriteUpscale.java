package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.game.resource.GameResourceManager;
import em.opengl.render.RU;
import em.opengl.resource.displaylist.DisplayListManager;
import em.opengl.resource.image.ImageDescriptor;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /opt hdsprites (on by default): textures are uploaded from higher-resolution replacements when one exists. A
 * replacement is a PNG named after the stock texture's content key (see {@link #key}), exactly 2, 3 or 4 times the stock
 * size, from the official HD sprite pack next to the client jar (see {@link SpritePack}). The
 * game keeps the stock texture size for layout and texture coordinates are normalized, so the replacement draws in the
 * same place at the same size, just with more pixels (mipmapped, so smaller windows stay smooth). Anything without a
 * valid replacement is uploaded as stock.
 * <p>
 * Only the scale the window needs is uploaded: the game draws the 1280x720 screen scaled to the window, so a window up
 * to 720 high gets stock textures, up to 1440 high 2x, and above that the pack's scale (3x; 4x art would be used above
 * 2160). The render thread never decodes a PNG: worker threads first convert every replacement to the
 * scale needed, as raw RGBA rows in the stock bottom-up order with their mipmaps, into config/client-sprites-cache
 * (once per replacement and scale; file names carry the source's content stamp, and stale files are deleted). Uploading
 * is then a memory-mapped read. Textures are reloaded (the stock sequence of AltitudeGame.changeDisplayMode) once the
 * cache for the new state is ready: after switching the option, after a resize that changes the scale, and at start-up
 * if textures were already loaded before it was ready.
 */
public final class SpriteUpscale {
    private SpriteUpscale() {}

    private static final Logger LOG = LoggerFactory.getLogger(SpriteUpscale.class);
    private static final int MAGIC = 0x48445332; // "HDS2"
    private static Map<String, SpritePack.Source> sources;
    private static ZipFile pack;
    private static boolean toldMissing;
    private static int replaced;

    static File dir() {
        String dir = System.getProperty("client.spritedir");
        return dir != null ? new File(dir) : ClientOptions.file().resolveSibling("client-sprites").toFile();
    }
    static File cacheRoot() { return new File(dir().getParentFile(), "client-sprites-cache"); }
    static File cacheDir(int k) { return new File(cacheRoot(), "hds2-" + k + "x"); }
    /** Cache file of a replacement at a scale: named by key and content stamp, so a changed source never hits an old file. */
    static File cacheFile(int k, String key, SpritePack.Source s) { return new File(cacheDir(k), key + "-" + s.stamp() + ".rgba"); }

    /** Content key of a stock texture: RGBA byte count and CRC-32, as "%08x-%08x". */
    static String key(ByteBuffer rgba, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(rgba.duplicate().position(offset).limit(offset + length));
        return String.format(Locale.ROOT, "%08x-%08x", length, crc.getValue());
    }

    /** Re-read the pack and the folder on next use (test harness); takes effect at the next reload. */
    static synchronized void rescan() {
        sources = null;
        if (pack != null) { try { pack.close(); } catch (IOException e) { /* reopened on next use */ } pack = null; }
    }

    /**
     * Replacements by texture key: the official pack's (see {@link SpritePack}); in the test build any pack, overridden
     * by loose PNGs in the folder.
     */
    static synchronized Map<String, SpritePack.Source> sources() {
        if (sources == null) {
            sources = new HashMap<>();
            maxScale = 4;
            File packFile = SpritePack.packFile();
            if (packFile != null && packFile.isFile()) {
                try {
                    pack = new ZipFile(packFile);
                    SpritePack.Contents c = SpritePack.readPack(pack, SpritePack.TEST_BUILD ? null : SpritePack.MANIFEST_SHA256);
                    sources.putAll(c.textures());
                    maxScale = c.scale();
                } catch (IOException | RuntimeException e) {
                    LOG.warn("SpriteUpscale: ignored {}: {}", packFile, e.toString());
                }
            }
            int fromPack = sources.size();
            Map<String, SpritePack.Source> loose = SpritePack.TEST_BUILD ? SpritePack.readDir(dir()) : Map.of();
            if (!loose.isEmpty()) maxScale = 4;
            sources.putAll(loose);
            LOG.info("SpriteUpscale: {} replacement textures ({} from {}, {} loose{})", sources.size(), fromPack, packFile, loose.size(),
                    SpritePack.TEST_BUILD ? " from " + dir() : "");
        }
        return sources;
    }
    /** Highest scale the replacements have: the pack's scale (4 with loose PNGs, which may be any of 2-4x). */
    private static int maxScale = 4;


    /** Upload scale wanted for a window: 1 (stock) up to 720 high, then one step per 720 lines of the 1280x720 screen. */
    static int scaleFor(int width, int height) {
        double s = Math.min(width / 1280.0, height / 720.0);
        return Math.max(1, Math.min(4, (int) Math.ceil(s - 0.05)));
    }

    /** Scale the loaded textures were uploaded at (1 = stock); read by the upload hook on the render thread. */
    private static volatile int uploadScale = 1;
    /** Uploads that kept stock although a replacement exists, because its cache was not ready yet. */
    private static int missed;
    private static int pendingScale;
    private static Future<?> pending;
    private static boolean started;
    private static int lastWidth, lastHeight;
    private static long sizeChanged;
    private static ExecutorService workers;

    /**
     * Once per frame, from {@link ClientFrame}: works out the scale wanted from the option and the window size (after
     * the size has been stable for a second), prepares its cache on worker threads, and reloads the textures once it is
     * ready. Switching off reloads at once.
     */
    static void frame(AltitudeGame game) {
        try {
            int w = Display.getWidth(), h = Display.getHeight();
            long now = System.nanoTime();
            if (w != lastWidth || h != lastHeight) { lastWidth = w; lastHeight = h; sizeChanged = now; }
            if (started && now - sizeChanged < 1_000_000_000L) return;
            boolean first = !started;
            started = true;
            boolean on = ClientOptions.on("hdsprites");
            boolean none = on && sources().isEmpty();
            int want = on && !none ? Math.min(scaleFor(w, h), maxScale) : 1;
            if (none && !toldMissing) {
                toldMissing = true;
                LOG.info("SpriteUpscale: hdsprites is on, but no HD sprite pack is installed ({} next to the client jar)", SpritePack.FILE_NAME);
            }
            if (on != lastOn) {
                lastOn = on;
                if (want == 1 && uploadScale == 1 && pending == null)
                    LOG.info("SpriteUpscale: hdsprites {}, but a {}x{} window needs stock textures: nothing to reload", on ? "on" : "off", w, h);
            }
            if (pending != null) {
                if (!pending.isDone()) return;
                pending = null;
                // A cache started at start-up needs no reload unless a texture was uploaded stock while it was built.
                if (pendingScale == want) { apply(game, want, !pendingAtStart || missed > 0); return; }
            }
            if (want == uploadScale) return;
            if (want == 1) { apply(game, 1, true); return; }
            pendingScale = want;
            pendingAtStart = first;
            pending = prepare(want);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("SpriteUpscale: frame failed", e);
        }
    }
    private static boolean pendingAtStart, lastOn;

    private static void apply(AltitudeGame game, int scale, boolean reload) {
        uploadScale = scale;
        missed = 0;
        if (!reload) { LOG.info("SpriteUpscale: replacements ready at {}x", scale); return; }
        long t0 = System.nanoTime();
        RU.setActiveTexture(null);
        for (GameResourceManager rm : game.getActiveResourceManagers()) rm.getImageManager().freeTextures();
        DisplayListManager.invalidateActiveDisplayLists();
        for (GameResourceManager rm : game.getActiveResourceManagers()) rm.getImageManager().reloadImages();
        // Font bitmaps are textures too: freed above, so reloaded as changeDisplayMode does (stockfonts draws them).
        for (GameResourceManager rm : game.getActiveResourceManagers()) rm.getFontManager().reloadImages();
        LOG.info("SpriteUpscale: textures reloaded with hdsprites {} at {}x in {} ms", scale > 1 ? "on" : "off", scale, (System.nanoTime() - t0) / 1_000_000);
    }

    private static synchronized ExecutorService workers() {
        if (workers == null) {
            int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
            workers = Executors.newFixedThreadPool(n, r -> { Thread t = new Thread(r, "client-sprites"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
        }
        return workers;
    }

    /**
     * Worker threads: convert every replacement that has no cache file at this scale yet. Then delete what is no longer
     * needed: other cache files in this scale's folder (replaced or removed sources) and the folders of other scales.
     */
    private static Future<?> prepare(int k) {
        Map<String, SpritePack.Source> list = new HashMap<>(sources());
        File out = cacheDir(k);
        return workers().submit(() -> {
            long t0 = System.nanoTime();
            out.mkdirs();
            AtomicInteger converted = new AtomicInteger(), failed = new AtomicInteger();
            List<Future<?>> parts = new ArrayList<>();
            Set<String> keep = new HashSet<>();
            for (Map.Entry<String, SpritePack.Source> e : list.entrySet()) {
                String key = e.getKey();
                SpritePack.Source source = e.getValue();
                File cache = cacheFile(k, key, source);
                keep.add(cache.getName());
                if (cache.isFile()) continue;
                parts.add(workers().submit(() -> {
                    try {
                        convert(key, source.read(), cache, k);
                        converted.incrementAndGet();
                    } catch (Exception | LinkageError ex) {
                        failed.incrementAndGet();
                        LOG.warn("SpriteUpscale: could not convert {}: {}", key, ex.toString());
                    }
                }));
            }
            // This task holds one worker; the others run the parts. Run any part not started yet here, then wait.
            for (Future<?> f : parts) if (f instanceof Runnable r && !f.isDone()) r.run();
            for (Future<?> f : parts) { try { f.get(); } catch (Exception e) { /* counted above */ } }
            int removed = cleanCache(out, keep);
            long mb = warm(out, keep) >> 20;
            LOG.info("SpriteUpscale: cache at {}x ready in {} ms ({} converted, {} failed, {} total, {} old files removed, {} MB read ahead)", k,
                    (System.nanoTime() - t0) / 1_000_000, converted.get(), failed.get(), list.size(), removed, mb);
        });
    }

    /**
     * Reads the cache files once on this worker thread, so the operating system has them in memory: the uploads that
     * follow on the render thread then map pages already read instead of waiting on the disk. Returns the bytes read.
     */
    static long warm(File dir, Set<String> names) {
        long total = 0;
        ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
        for (String name : names) {
            try (FileChannel ch = FileChannel.open(new File(dir, name).toPath(), StandardOpenOption.READ)) {
                for (int n; (n = ch.read(buf.clear())) > 0; ) total += n;
            } catch (IOException e) {
                // Missing or unreadable: the upload keeps stock for it.
            }
        }
        return total;
    }

    /** Deletes cache files in the current folder not in keep, and every other folder under the cache root. */
    static int cleanCache(File current, Set<String> keep) {
        int removed = 0;
        File[] here = current.listFiles();
        if (here != null) for (File f : here) if (!keep.contains(f.getName()) && f.delete()) removed++;
        File[] others = current.getParentFile().listFiles(f -> f.isDirectory() && !f.equals(current));
        if (others != null) {
            for (File d : others) {
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) if (f.isFile() && f.delete()) removed++;
                d.delete();
            }
        }
        return removed;
    }

    /**
     * Decode a replacement PNG, resample it to k times the stock size (area average on premultiplied colour; exact box
     * average for whole ratios) unless it already is, and write a cache file: magic, width, height, mipmap level
     * count, then every level down to 1x1 (each the previous one area-averaged to half size) as RGBA rows bottom-up.
     */
    static void convert(String key, byte[] png, File cache, int k) throws IOException {
        long stockPixels = Long.parseLong(key.substring(0, 8), 16) / 4;
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("not an image");
        int W = img.getWidth(), H = img.getHeight();
        int fileK = (int) Math.round(Math.sqrt((double) W * H / stockPixels));
        if (fileK < 2 || fileK > 4 || (long) W * H != stockPixels * fileK * fileK) throw new IOException("not 2-4x of the stock size");
        int kk = Math.min(k, fileK), w = W / fileK * kk, h = H / fileK * kk;
        int[] argb = img.getRGB(0, 0, W, H, null, 0, W);
        if (kk != fileK) argb = resample(argb, W, H, w, h);
        File tmp = new File(cache.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp.toPath()), 1 << 16))) {
            out.writeInt(MAGIC); out.writeInt(w); out.writeInt(h); out.writeInt(levels(w, h));
            for (int lw = w, lh = h;; ) {
                byte[] row = new byte[lw * 4];
                for (int y = lh - 1; y >= 0; y--) {
                    for (int x = 0, i = 0; x < lw; x++) {
                        int p = argb[y * lw + x];
                        row[i++] = (byte) (p >> 16); row[i++] = (byte) (p >> 8); row[i++] = (byte) p; row[i++] = (byte) (p >>> 24);
                    }
                    out.write(row);
                }
                if (lw == 1 && lh == 1) break;
                int nw = Math.max(1, lw / 2), nh = Math.max(1, lh / 2);
                argb = resample(argb, lw, lh, nw, nh);
                lw = nw; lh = nh;
            }
        }
        Files.move(tmp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Mipmap levels down to 1x1 (each level half the size, rounded down, at least 1). */
    static int levels(int w, int h) { int n = 1; while (w > 1 || h > 1) { w = Math.max(1, w / 2); h = Math.max(1, h / 2); n++; } return n; }
    /** Bytes of all mipmap levels. */
    static long levelBytes(int w, int h) {
        long n = 0;
        while (true) { n += (long) w * h * 4; if (w == 1 && h == 1) return n; w = Math.max(1, w / 2); h = Math.max(1, h / 2); }
    }

    /** Area-average resample of ARGB pixels in premultiplied colour, separably (rows, then columns). */
    static int[] resample(int[] argb, int W, int H, int w, int h) {
        float[][] p = new float[4][W * H];
        for (int i = 0; i < W * H; i++) {
            int v = argb[i];
            float a = (v >>> 24) / 255f;
            p[0][i] = ((v >> 16) & 255) * a; p[1][i] = ((v >> 8) & 255) * a; p[2][i] = (v & 255) * a; p[3][i] = v >>> 24;
        }
        float[][] q = new float[4][];
        for (int c = 0; c < 4; c++) q[c] = axis(axis(p[c], W, H, w, true), w, H, h, false);
        int[] out = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            float a = q[3][i];
            int A = Math.round(Math.min(255, Math.max(0, a))), r = 0, g = 0, b = 0;
            if (a > 0.5f) {
                r = Math.round(Math.min(255, q[0][i] * 255 / a)); g = Math.round(Math.min(255, q[1][i] * 255 / a)); b = Math.round(Math.min(255, q[2][i] * 255 / a));
            }
            out[i] = A << 24 | r << 16 | g << 8 | b;
        }
        return out;
    }
    /** Area average along one axis: rows (from W to w columns) or columns (from H to h rows, width W). */
    private static float[] axis(float[] src, int W, int H, int n, boolean rows) {
        int from = rows ? W : H, lines = rows ? H : W;
        float[] out = new float[rows ? n * H : W * n];
        double scale = (double) from / n;
        for (int j = 0; j < n; j++) {
            double lo = j * scale, hi = lo + scale;
            for (int s = (int) lo; s < Math.min(from, Math.ceil(hi)); s++) {
                float wgt = (float) ((Math.min(hi, s + 1) - Math.max(lo, s)) / scale);
                if (wgt <= 0) continue;
                for (int l = 0; l < lines; l++) {
                    if (rows) out[l * n + j] += src[l * W + s] * wgt;
                    else out[j * W + l] += src[s * W + l] * wgt;
                }
            }
        }
        return out;
    }

    /**
     * Hook: start of ImagePack.loadTextureFromRgba (the texture is already bound). True when a replacement was
     * uploaded from the cache; the stock buffer is then advanced past the stock pixels, as the stock method does.
     */
    public static boolean upload(ImageDescriptor.InternalFormat format, int width, int height, ByteBuffer rgba) {
        if (!ClientOptions.on("hdsprites")) return false;
        try {
            int length = width * height * 4;
            Map<String, SpritePack.Source> all = sources();
            if (all.isEmpty() || rgba.remaining() < length) return false;
            String key = key(rgba, rgba.position(), length);
            SpritePack.Source source = all.get(key);
            if (source == null) return false;
            int k = uploadScale;
            if (k < 2) { missed++; return false; }
            File cache = cacheFile(k, key, source);
            if (!cache.isFile()) { missed++; return false; }
            ByteBuffer big;
            try (FileChannel ch = FileChannel.open(cache.toPath(), StandardOpenOption.READ)) {
                big = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.BIG_ENDIAN);
            }
            int w = big.getInt(4), h = big.getInt(8), levels = big.getInt(12), kk = w / width;
            if (big.getInt(0) != MAGIC || kk < 2 || w != width * kk || h != height * kk || levels != levels(w, h) || big.capacity() != 16 + levelBytes(w, h)) {
                LOG.warn("SpriteUpscale: {} does not match {}x{}, kept stock", cache.getName(), width, height);
                return false;
            }
            // Mipmaps come precomputed: letting the driver generate them took ten times as long as the upload itself.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 0x813D, levels - 1); // GL_TEXTURE_MAX_LEVEL
            int at = 16;
            for (int level = 0, lw = w, lh = h; level < levels; level++, lw = Math.max(1, lw / 2), lh = Math.max(1, lh / 2)) {
                big.position(at);
                at += lw * lh * 4;
                big.limit(at);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, format.glConstant, lw, lh, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, big.slice());
                big.limit(big.capacity());
            }
            rgba.position(rgba.position() + length);
            if (++replaced % 25 == 1) LOG.info("SpriteUpscale: {} textures replaced (last {} at {}x)", replaced, key, kk);
            return true;
        } catch (Exception | LinkageError e) {
            LOG.warn("SpriteUpscale: kept stock texture", e);
            return false;
        }
    }
}
