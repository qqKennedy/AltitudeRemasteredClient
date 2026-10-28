package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.altitude.game.scene.SceneManager;
import em.altitude.game.scene.TitleScene;
import em.common.debug.ImageHelper;
import em.common.io.IOHelper;
import em.common.io.LzmaHelper;
import em.common.io.stream.BitInputStream;
import em.common.io.stream.codec.ListStreamCodec;
import em.common.util.MultipartTask;
import em.opengl.render.RU;
import em.opengl.resource.image.Image;
import em.opengl.resource.image.ImageDescriptor;
import em.opengl.resource.image.ImageManager;
import em.opengl.resource.image.ImagePack;
import em.opengl.resource.image.ImagePackStorageType;
import em.opengl.resource.image.Texture;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.WritableRaster;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faster loading of APEG image packs (JPEG colour plus a separate alpha channel), the textures a map and its
 * scenery are drawn with. Decoding them on the render thread was most of the frame-long stall when joining a
 * server or changing map: the stock loader converts each JPEG to RGBA through BufferedImage.getRGB, one pixel at a
 * time. Here the colour bytes are copied straight from the decoded raster (the same values getRGB returns for this
 * image type, otherwise the stock conversion runs). When the load scene asks an image manager for its precache task
 * (a map's own images on every join or map change), every APEG pack in its folder starts decoding on worker
 * threads; each pack is then uploaded in order on the render thread with the stock texture formats, ids and
 * parameters, taking a decoded result only if its compressed bytes are identical, and decoding on the render thread
 * itself whatever no worker has started yet. Other storage types, or anything unexpected while decoding, fall back
 * to the stock loader before any GL call is made.
 * <p>
 * The game-wide precache set (313 packs, 66 MB, all legacy uncompressed except one APEG pack) used to be read and
 * uploaded inside the first join's load scene. It is now loaded through the stock precache task in ~1 ms slices
 * from the render hook while the title menu is up, so the first join costs the same as any later map.
 */
public final class FastImagePack {
    private FastImagePack() {}

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    /**
     * -Dclient.stockimages=true: always use the stock loader (for comparisons); also turns the menu precache off.
     * -Dclient.menuprecache=false turns only the menu slicing off; -Dclient.menuprecache.ms=N sets the per-frame
     * budget (0 = one pack per frame on the client's nanosecond clock).
     */
    private static final boolean STOCK = Boolean.getBoolean("client.stockimages");
    private static MethodHandle formatFor, prepareId, upload;
    private static boolean unavailable;
    private static ExecutorService workers;

    /** Hook: start of ImagePack.readImageBytes. Null means the stock loader reads the pack. */
    public static Texture[] read(String descriptorPath, ByteBuffer buffer, ImageManager.TextureQuality quality) {
        if (STOCK || unavailable) return null;
        int start = buffer.position();
        try {
            int version = buffer.getInt(start);
            if (ImagePackStorageType.isLegacyVersion(version) || ImagePackStorageType.STORAGE_TYPE_BY_VERSION.get(version) != ImagePackStorageType.APEG_1) return null;
            if (formatFor == null) bind();
            buffer.getInt();
            ImageDescriptor.InternalFormat internalFormat = ImageDescriptor.InternalFormat.values()[buffer.getInt()];
            ImageDescriptor.WrapMode wrapMode = ImageDescriptor.WrapMode.values()[buffer.getInt()];
            ImageDescriptor.TextureFilter filter = ImageDescriptor.TextureFilter.values()[buffer.getInt()];
            int count = buffer.getInt();
            int[] widths = new int[count], heights = new int[count];
            List<byte[]> blobs = new ArrayList<byte[]>(count);
            for (int i = 0; i < count; i++) {
                widths[i] = buffer.getInt(); heights[i] = buffer.getInt();
                int length = (buffer.get() & 255) << 24 | (buffer.get() & 255) << 16 | (buffer.get() & 255) << 8 | buffer.get() & 255;
                byte[] bytes = new byte[length]; buffer.get(bytes); blobs.add(bytes);
            }
            byte[][] rgba = decodeAll(blobs);
            // From here on the same GL calls as the stock loader, in the same order.
            ImageDescriptor desc = new ImageDescriptor(descriptorPath, wrapMode, filter, internalFormat);
            Texture[] textures = new Texture[count];
            for (int i = 0; i < count; i++) {
                int width = widths[i], height = heights[i];
                ImageDescriptor.InternalFormat format = (ImageDescriptor.InternalFormat) formatFor.invoke(descriptorPath, internalFormat, quality, width, height);
                ImageDescriptor textureDescriptor = format == internalFormat ? desc : new ImageDescriptor(desc.getPath(), wrapMode, filter, format);
                int textureId = (int) prepareId.invoke(wrapMode, filter);
                ByteBuffer pixels = uploadBuffer(4 * width * height);
                pixels.put(rgba[i]); pixels.flip(); rgba[i] = null;
                upload.invoke(format, width, height, pixels);
                textures[i] = new Texture(textureId, width, height, textureDescriptor);
                int error = GL11.glGetError();
                if (error != 0) throw new RuntimeException("error=" + error + ", i=" + i + ", w=" + width + ", h=" + height);
            }
            return textures;
        } catch (FallBack e) {
            buffer.position(start);
            return null;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /** One direct buffer reused for uploads (glTexImage2D copies it), limited to the texture's size like a fresh one. */
    private static ByteBuffer scratch;
    private static ByteBuffer uploadBuffer(int bytes) {
        if (scratch == null || scratch.capacity() < bytes) scratch = BufferUtils.createByteBuffer(Math.max(bytes, 1 << 20));
        scratch.clear(); scratch.limit(bytes); return scratch;
    }

    /** Thrown before any GL call to let the stock loader read the pack instead. */
    private static final class FallBack extends Exception {
        FallBack(Throwable cause) { super(cause); }
    }

    private static void bind() throws FallBack {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            formatFor = lookup.unreflect(declared("getInternalFormatBasedOnTexture", String.class, ImageDescriptor.InternalFormat.class, ImageManager.TextureQuality.class, int.class, int.class));
            prepareId = lookup.unreflect(declared("prepareTextureId", ImageDescriptor.WrapMode.class, ImageDescriptor.TextureFilter.class));
            upload = lookup.unreflect(declared("loadTextureFromRgba", ImageDescriptor.InternalFormat.class, int.class, int.class, ByteBuffer.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            throw new FallBack(e);
        }
    }
    private static Method declared(String name, Class<?>... types) throws NoSuchMethodException {
        Method m = ImagePack.class.getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }

    /**
     * Decodes every texture of a pack: prefetched results where the bytes match, otherwise on worker threads when
     * there are several. A task no worker has started yet is run here rather than waited for.
     */
    static byte[][] decodeAll(List<byte[]> blobs) throws FallBack {
        byte[][] out = new byte[blobs.size()][];
        try {
            List<FutureTask<byte[]>> tasks = new ArrayList<FutureTask<byte[]>>();
            for (byte[] blob : blobs) {
                long key = key(blob);
                CLAIMED.add(key);
                Prefetched p = PREFETCHED.remove(key);
                tasks.add(p != null && Arrays.equals(p.blob, blob) ? p.task : new FutureTask<byte[]>(() -> decode(blob)));
            }
            if (tasks.size() > 1) for (FutureTask<byte[]> t : tasks) if (!t.isDone()) workers().execute(t);
            for (int i = 0; i < out.length; i++) { FutureTask<byte[]> t = tasks.get(i); t.run(); out[i] = t.get(); }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FallBack(e);
        } catch (Exception | Error e) {
            throw new FallBack(e);
        }
    }
    private static synchronized ExecutorService workers() {
        if (workers == null) {
            int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
            workers = Executors.newFixedThreadPool(n, r -> { Thread t = new Thread(r, "client-images"); t.setDaemon(true); return t; });
        }
        return workers;
    }

    // ---- Prefetch -----------------------------------------------------------------------------------
    private static final class Prefetched {
        final byte[] blob; final FutureTask<byte[]> task; final long created = System.nanoTime();
        Prefetched(byte[] blob) { this.blob = blob; task = new FutureTask<byte[]>(() -> decode(blob)); }
    }
    private static final Map<Long, Prefetched> PREFETCHED = new ConcurrentHashMap<Long, Prefetched>();
    /** Textures already loaded since the last prefetch started; a worker that reaches one later skips it. */
    private static final Set<Long> CLAIMED = ConcurrentHashMap.newKeySet();
    private static final Map<ImageManager, Boolean> PREFETCHED_MANAGERS = Collections.synchronizedMap(new WeakHashMap<ImageManager, Boolean>());
    private static Field distDir, precacheTask;
    static long key(byte[] blob) { CRC32 crc = new CRC32(); crc.update(blob); return (long) blob.length << 32 ^ crc.getValue(); }

    /**
     * Hook: start of ImageManager.getPrecacheLoadImagesTask. The first time for each manager, queue every APEG pack
     * under its folder (outside the excluded folders) for decoding on the worker threads.
     */
    public static void prefetch(ImageManager manager, List<String> excluded) {
        if (STOCK || unavailable) return;
        try {
            bindManagerFields();
            if (precacheTask.get(manager) != null || PREFETCHED_MANAGERS.put(manager, Boolean.TRUE) != null) return;
            File dir = (File) distDir.get(manager);
            if (dir == null) return;
            long now = System.nanoTime();
            PREFETCHED.values().removeIf(p -> now - p.created > 120_000_000_000L);    // never used, e.g. a map left early
            CLAIMED.clear();
            List<String> skip = excluded == null ? List.of() : new ArrayList<String>(excluded);
            workers().execute(() -> {
                for (Path pack : packFiles(dir, skip)) workers().execute(() -> prefetchPack(pack));
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No prefetch; packs are still decoded when loaded.
        }
    }
    /** Image pack files (named p0, p1, ...) under a manager's folder, outside the excluded subfolders, sorted. */
    private static List<Path> packFiles(File dir, List<String> excluded) {
        Path root = dir.toPath();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().matches("p[0-9]+") && Files.isRegularFile(f))
                    .filter(f -> { String rel = root.relativize(f).toString().replace(File.separatorChar, '/'); return excluded.stream().noneMatch(rel::startsWith); })
                    .sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
    private static void bindManagerFields() throws ReflectiveOperationException {
        if (distDir == null) {
            distDir = ImageManager.class.getDeclaredField("distDir"); distDir.setAccessible(true);
            precacheTask = ImageManager.class.getDeclaredField("precacheImageTask"); precacheTask.setAccessible(true);
        }
    }
    /** Worker thread: read an APEG pack's compressed textures, register them, and decode those still unclaimed. */
    private static void prefetchPack(Path pack) {
        List<Prefetched> mine = new ArrayList<Prefetched>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(pack), 1 << 16))) {
            long size = Files.size(pack);
            new ListStreamCodec<Image>(Image.STREAM_CODEC).read(new BitInputStream(in));
            int version = in.readInt();
            if (ImagePackStorageType.isLegacyVersion(version) || ImagePackStorageType.STORAGE_TYPE_BY_VERSION.get(version) != ImagePackStorageType.APEG_1) return;
            in.readInt(); in.readInt(); in.readInt();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                in.readInt(); in.readInt();
                int length = in.readInt();
                if (length < 0 || length > size) return;
                byte[] blob = new byte[length]; in.readFully(blob);
                Prefetched p = new Prefetched(blob);
                long key = key(blob);
                if (!CLAIMED.contains(key) && PREFETCHED.putIfAbsent(key, p) == null) mine.add(p);
            }
        } catch (IOException | RuntimeException e) {
            return;
        }
        for (Prefetched p : mine) p.task.run();
    }

    // ---- Menu precache ------------------------------------------------------------------------------
    private static final boolean MENU = !"false".equals(System.getProperty("client.menuprecache"));
    private static final double MENU_BUDGET_MS = menuBudget();
    private static double menuBudget() {
        try { return Math.max(0, Double.parseDouble(System.getProperty("client.menuprecache.ms", "1"))); } catch (RuntimeException e) { return 1; }
    }
    static boolean menuDone, menuStarted;
    private static boolean warmed;
    static long menuFrames, menuNanos, menuMaxNanos;

    /**
     * Once per frame, from {@link ClientFrame}. While the title scene is current with the game menu up (after login),
     * run the stock game-wide precache task for one budget slice per frame on the render thread: already cached
     * packs are skipped, the load scene later finds the task complete. Stops for good on the first error.
     */
    public static void menuFrame(AltitudeGame game) {
        if (STOCK || !MENU || menuDone) return;
        try {
            SceneManager sm = game.getSceneManager();
            if (sm == null || !game.hasRenderContext() || !(sm.getCurrentScene() instanceof TitleScene) || !sm.isGameMenuVisible()) return;
            ImageManager im = game.getResourceManager().getImageManager();
            List<String> excluded = game.getExcludePrecacheImageDirPaths();
            MultipartTask<?> task = im.getPrecacheLoadImagesTask(excluded);
            if (task.isComplete()) { menuDone = true; return; }
            if (!menuStarted) { menuStarted = true; warm(im, excluded); }
            long t0 = System.nanoTime();
            boolean done = slice(task, MENU_BUDGET_MS);
            RU.setActiveTexture(null);    // ImagePack binds with GL11 directly; drop the stale RU texture cache
            long dt = System.nanoTime() - t0;
            menuFrames++; menuNanos += dt; menuMaxNanos = Math.max(menuMaxNanos, dt);
            if (done) {
                menuDone = true;
                LOG.info("MenuPrecache: game-wide image packs loaded in the menu over {} frames, {} ms total, max slice {} ms",
                        menuFrames, menuNanos / 1_000_000, menuMaxNanos / 1_000_000);
            }
        } catch (Exception | LinkageError e) {
            menuDone = true;
            LOG.warn("MenuPrecache: stopped: " + e);
        }
    }
    /** One budget slice of the task (a negative budget would mean all of it); true when nothing is left to do. */
    static boolean slice(MultipartTask<?> task, double budgetMs) {
        if (menuDone || task.isComplete()) return true;
        try {
            return task.update(Math.max(0, budgetMs));
        } catch (Exception e) {
            menuDone = true;
            return true;
        }
    }
    static void resetMenuForTest() { menuDone = menuStarted = false; menuFrames = menuNanos = menuMaxNanos = 0; }
    /** Worker thread, once: stream the pack files to nothing so the slices read from the page cache. */
    private static void warm(ImageManager im, List<String> excluded) {
        if (warmed) return;
        warmed = true;
        try {
            bindManagerFields();
            File dir = (File) distDir.get(im);
            if (dir == null) return;
            List<String> skip = excluded == null ? List.of() : new ArrayList<String>(excluded);
            workers().execute(() -> {
                byte[] chunk = new byte[1 << 16];
                for (Path pack : packFiles(dir, skip)) {
                    if (menuDone) return;
                    try (InputStream in = Files.newInputStream(pack); OutputStream sink = OutputStream.nullOutputStream()) {
                        for (int n; (n = in.read(chunk)) > 0;) sink.write(chunk, 0, n);
                    } catch (IOException | RuntimeException e) {
                        // Not warmed; the slice reads it from disk.
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No warm; slices still work.
        }
    }

    /** ApegHelper.loadApeg with the faster RGBA conversion. */
    static byte[] decode(byte[] bytes) throws IOException {
        InputStream is = new ByteArrayInputStream(bytes);
        int alphaEncoder = new DataInputStream(is).readInt();
        byte[] alpha = null;
        if ((alphaEncoder & 0xF) != 0) {
            if ((alphaEncoder & 0x20) != 0) {
                ByteArrayOutputStream alphaBytes = new ByteArrayOutputStream();
                LzmaHelper.decode(is, alphaBytes);
                alpha = alphaBytes.toByteArray();
            } else {
                byte[] compressed = new byte[new DataInputStream(is).readInt()];
                is.read(compressed);
                alpha = IOHelper.decompressBytes(compressed);
            }
        }
        byte[] rgba = toRgba(ImageIO.read(new MemoryCacheImageInputStream(is)));
        if (alpha != null) for (int i = 0; i < alpha.length; i++) rgba[(i << 2) + 3] = alpha[i];
        return rgba;
    }

    /** ImageHelper.toRgba; 3-byte BGR images (what the JPEG reader returns for colour) are copied directly. */
    static byte[] toRgba(BufferedImage image) {
        WritableRaster raster = image.getRaster();
        int width = image.getWidth(), height = image.getHeight();
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR && raster.getDataBuffer() instanceof DataBufferByte data && data.getNumBanks() == 1 && data.getOffset() == 0
                && raster.getSampleModel() instanceof PixelInterleavedSampleModel model && model.getPixelStride() == 3 && model.getScanlineStride() == width * 3
                && raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0) {
            int[] offsets = model.getBandOffsets();
            if (offsets.length == 3 && offsets[0] == 2 && offsets[1] == 1 && offsets[2] == 0) {
                byte[] bgr = data.getData(), rgba = new byte[width * height * 4];
                for (int i = 0, j = 0, n = width * height * 3; i < n; i += 3, j += 4) {
                    rgba[j] = bgr[i + 2]; rgba[j + 1] = bgr[i + 1]; rgba[j + 2] = bgr[i]; rgba[j + 3] = (byte) 255;
                }
                return rgba;
            }
        }
        return ImageHelper.toRgba(image);
    }
}
