import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Makes upscaled sprites consistent with stock (iterative back-projection): the error between stock and the upscaled
 * image shrunk back (box average over k x k, premultiplied alpha, as in Fidelity) is spread back over the upscaled
 * pixels with bilinear weights, repeated until it is gone. The AI keeps its fine detail, but anything it invented at
 * the stock pixel scale (moved markings, wrong colours, soft alpha edges) is pulled back to the stock art.
 * Usage: java BackProject STOCK_DIR UPSCALED_DIR OUT_DIR [iterations=20]
 */
public class BackProject {
    public static void main(String[] a) throws Exception {
        File stockDir = new File(a[0]), upDir = new File(a[1]), outDir = new File(a[2]);
        int iterations = a.length > 3 ? Integer.parseInt(a[3]) : 20;
        outDir.mkdirs();
        File[] files = stockDir.listFiles((d, n) -> n.endsWith(".png"));
        if (files == null) throw new IllegalArgumentException("No PNGs in " + stockDir);
        int n = 0;
        for (File f : files) {
            File u = new File(upDir, f.getName());
            if (!u.isFile()) continue;
            ImageIO.write(project(ImageIO.read(f), ImageIO.read(u), iterations), "png", new File(outDir, f.getName()));
            n++;
        }
        System.out.println("BackProject: " + n + " textures, " + iterations + " iterations");
    }

    static BufferedImage project(BufferedImage stock, BufferedImage up, int iterations) {
        int w = stock.getWidth(), h = stock.getHeight(), k = up.getWidth() / w, W = w * k, H = h * k;
        if (k < 1 || up.getWidth() != W || up.getHeight() != H) throw new IllegalArgumentException("not an integer upscale");
        float[][] lo = premultiplied(stock), hi = premultiplied(up);
        float[][] err = new float[4][w * h];
        for (int it = 0; it < iterations; it++) {
            for (int c = 0; c < 4; c++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        float s = 0;
                        for (int dy = 0; dy < k; dy++) for (int dx = 0; dx < k; dx++) s += hi[c][(y * k + dy) * W + x * k + dx];
                        err[c][y * w + x] = lo[c][y * w + x] - s / (k * k);
                    }
                }
                for (int Y = 0; Y < H; Y++) {
                    // Bilinear sample of the low-res error at the centre of this high-res pixel.
                    float fy = (Y + 0.5f) / k - 0.5f;
                    int y0 = (int) Math.floor(fy);
                    float ty = fy - y0;
                    int ya = Math.max(0, y0), yb = Math.min(h - 1, y0 + 1);
                    for (int X = 0; X < W; X++) {
                        float fx = (X + 0.5f) / k - 0.5f;
                        int x0 = (int) Math.floor(fx);
                        float tx = fx - x0;
                        int xa = Math.max(0, x0), xb = Math.min(w - 1, x0 + 1);
                        float e = (err[c][ya * w + xa] * (1 - tx) + err[c][ya * w + xb] * tx) * (1 - ty)
                                + (err[c][yb * w + xa] * (1 - tx) + err[c][yb * w + xb] * tx) * ty;
                        int i = Y * W + X;
                        hi[c][i] = Math.max(0, Math.min(c == 3 ? 255 : hi[3][i], hi[c][i] + e));
                    }
                }
            }
        }
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < W * H; i++) {
            float al = hi[3][i];
            int A = Math.round(al), r = 0, g = 0, b = 0;
            if (al > 0.5f) {
                r = Math.min(255, Math.round(hi[0][i] * 255 / al));
                g = Math.min(255, Math.round(hi[1][i] * 255 / al));
                b = Math.min(255, Math.round(hi[2][i] * 255 / al));
            }
            out.setRGB(i % W, i / W, A << 24 | r << 16 | g << 8 | b);
        }
        return out;
    }

    /** Planes r, g, b (premultiplied, 0..alpha) and a (0..255). */
    static float[][] premultiplied(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[][] p = new float[4][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = img.getRGB(x, y), i = y * w + x;
                float al = v >>> 24;
                p[0][i] = ((v >> 16) & 255) * al / 255;
                p[1][i] = ((v >> 8) & 255) * al / 255;
                p[2][i] = (v & 255) * al / 255;
                p[3][i] = al;
            }
        }
        return p;
    }
}
