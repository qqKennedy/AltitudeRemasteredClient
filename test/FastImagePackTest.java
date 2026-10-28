package em.altitude.game.scene.player;

import em.common.debug.ImageHelper;
import em.common.timing.SystemNanoTimeProvider;
import em.common.timing.TimeHelper;
import em.common.util.MultipartTask;
import em.opengl.resource.image.ApegHelper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;

public class FastImagePackTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    static BufferedImage random(int type, int w, int h, Random r) {
        BufferedImage img = new BufferedImage(w, h, type);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt());
        return img;
    }
    /** An APEG blob as the pack writer stores it: alpha encoder, optional deflated alpha, then the JPEG. */
    static byte[] apeg(BufferedImage colour, byte[] alpha) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(out);
        data.writeInt(alpha == null ? 0 : 8);
        if (alpha != null) {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(z)) { d.write(alpha); }
            data.writeInt(z.size()); data.write(z.toByteArray());
        }
        ImageIO.write(colour, "jpg", data); data.flush();
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Random r = new Random(7);
        BufferedImage bgr = random(BufferedImage.TYPE_3BYTE_BGR, 37, 23, r);
        check(Arrays.equals(FastImagePack.toRgba(bgr), ImageHelper.toRgba(bgr)), "3-byte BGR copied exactly");
        BufferedImage sub = bgr.getSubimage(3, 2, 20, 11);
        check(Arrays.equals(FastImagePack.toRgba(sub), ImageHelper.toRgba(sub)), "sub-image (offset raster) falls back exactly");
        BufferedImage gray = random(BufferedImage.TYPE_BYTE_GRAY, 16, 9, r);
        check(Arrays.equals(FastImagePack.toRgba(gray), ImageHelper.toRgba(gray)), "grey image falls back exactly");
        BufferedImage argb = random(BufferedImage.TYPE_INT_ARGB, 12, 5, r);
        check(Arrays.equals(FastImagePack.toRgba(argb), ImageHelper.toRgba(argb)), "ARGB image falls back exactly");
        BufferedImage colour = random(BufferedImage.TYPE_3BYTE_BGR, 64, 48, r);
        byte[] alpha = new byte[64 * 48]; r.nextBytes(alpha);
        for (byte[] a : new byte[][] { null, alpha }) {
            byte[] blob = apeg(colour, a);
            check(Arrays.equals(FastImagePack.decode(blob), ApegHelper.loadApeg(new java.io.ByteArrayInputStream(blob)).getRgba()), "APEG decode matches stock, alpha " + (a != null));
            check(FastImagePack.key(blob) == FastImagePack.key(blob.clone()), "content key is stable");
        }
        menuSlices();
        System.out.println("PASS: fast image-pack decoding matches the stock RGBA conversion (BGR, offset, grey, ARGB) and APEG loader (with and without alpha); menu precache slices honour the budget, the done latch and a failing task.");
    }

    // ---- Menu precache slices (the client installs the nanosecond clock; the stock default is milliseconds) ----
    static void spinMs(double ms) { double t0 = TimeHelper.getTimeMs(); while (TimeHelper.getTimeMs() - t0 < ms) Thread.onSpinWait(); }
    static MultipartTask<Integer> task(int[] calls, double spin, boolean fail) {
        return new MultipartTask<Integer>(List.of(1, 2, 3, 4, 5)) {
            @Override public boolean process(Integer s, double budget) throws Exception {
                calls[0]++;
                if (fail) throw new IOException("x");
                spinMs(spin);
                return true;
            }
        };
    }
    static void menuSlices() {
        TimeHelper.setTimeProvider(new SystemNanoTimeProvider());
        int[] calls = { 0 };
        FastImagePack.resetMenuForTest();
        MultipartTask<Integer> t = task(calls, 0.05, false);
        check(!FastImagePack.slice(t, 0), "budget 0: first slice is not the end");
        check(calls[0] == 1, "budget 0: one subtask per slice, got " + calls[0]);
        int slices = 1;
        while (true) { slices++; if (FastImagePack.slice(t, 0)) break; }
        check(slices == 5 && calls[0] == 5 && t.isComplete(), "budget 0: 5 subtasks over 5 slices, got " + slices + "/" + calls[0]);
        check(FastImagePack.slice(t, 0) && calls[0] == 5, "a complete task is not processed again");
        calls[0] = 0;
        MultipartTask<Integer> failing = task(calls, 0, true);
        check(FastImagePack.slice(failing, 0) && FastImagePack.slice(failing, 0) && calls[0] == 1, "a failing task latches; no second process() call, got " + calls[0]);
        check(FastImagePack.menuDone, "failure sets the done latch");
        FastImagePack.resetMenuForTest();
        calls[0] = 0;
        FastImagePack.slice(task(calls, 2, false), 1);
        check(calls[0] == 1, "1 ms budget, 2 ms subtasks: exactly one, got " + calls[0]);
        calls[0] = 0;
        FastImagePack.slice(task(calls, 2, false), 10);
        check(calls[0] > 1, "10 ms budget, 2 ms subtasks: more than one, got " + calls[0]);
        calls[0] = 0;
        FastImagePack.slice(task(calls, 2, false), 0);
        check(calls[0] == 1, "budget 0, 2 ms subtasks: exactly one, got " + calls[0]);
        calls[0] = 0;
        FastImagePack.slice(task(calls, 0, false), -5);
        check(calls[0] == 1, "negative budget is clamped to one subtask, got " + calls[0]);
        check(!FastImagePack.menuDone, "budget cases leave the latch clear");
    }
}
