import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Fidelity check for upscaled sprites, without the game: each upscaled PNG is shrunk back to the stock size (box
 * average over k x k pixels) and compared with the stock PNG of the same key. Colour error is measured on
 * alpha-premultiplied RGB (so invisible pixels do not count), alpha error separately, both as RMS in 0-255 units, plus
 * the worst single pixel. Writes fidelity.csv (worst first) and fidelity-worst.png (stock, upscaled, shrunk-back error
 * map for the worst textures). Fails (exit 1) when any texture is over the limits.
 * Usage: java Fidelity STOCK_DIR UPSCALED_DIR OUT_DIR [maxRgbRms=10] [maxAlphaRms=8]
 */
public class Fidelity {
    record Result(String key, int k, double rgbRms, double alphaRms, int worst, BufferedImage stock, BufferedImage up, BufferedImage err) {}

    public static void main(String[] args) throws Exception {
        File stockDir = new File(args[0]), upDir = new File(args[1]);
        Path out = Path.of(args[2]);
        double maxRgb = args.length > 3 ? Double.parseDouble(args[3]) : 10, maxAlpha = args.length > 4 ? Double.parseDouble(args[4]) : 8;
        Files.createDirectories(out);
        List<Result> results = new ArrayList<>();
        File[] files = stockDir.listFiles((d, n) -> n.endsWith(".png"));
        if (files == null) throw new IllegalArgumentException("No PNGs in " + stockDir);
        for (File f : files) {
            File u = new File(upDir, f.getName());
            if (!u.isFile()) continue;
            results.add(compare(f.getName().replace(".png", ""), ImageIO.read(f), ImageIO.read(u)));
        }
        results.sort((a, b) -> Double.compare(score(b), score(a)));
        StringBuilder csv = new StringBuilder("key,scale,rgb_rms,alpha_rms,worst_pixel\n");
        int bad = 0;
        for (Result r : results) {
            csv.append(String.format(Locale.ROOT, "%s,%d,%.2f,%.2f,%d%n", r.key, r.k, r.rgbRms, r.alphaRms, r.worst));
            if (r.rgbRms > maxRgb || r.alphaRms > maxAlpha) bad++;
        }
        Files.writeString(out.resolve("fidelity.csv"), csv);
        sheet(results.subList(0, Math.min(8, results.size())), out.resolve("fidelity-worst.png").toFile());
        double meanRgb = results.stream().mapToDouble(Result::rgbRms).average().orElse(0);
        System.out.printf(Locale.ROOT, "Fidelity: %d textures, mean RGB RMS %.2f, worst %s (RGB %.2f, alpha %.2f); %d over the limits (RGB %.1f, alpha %.1f)%n",
                results.size(), meanRgb, results.isEmpty() ? "-" : results.get(0).key, results.isEmpty() ? 0 : results.get(0).rgbRms,
                results.isEmpty() ? 0 : results.get(0).alphaRms, bad, maxRgb, maxAlpha);
        if (bad > 0) System.exit(1);
    }

    static double score(Result r) { return r.rgbRms + r.alphaRms * 0.5; }

    static Result compare(String key, BufferedImage stock, BufferedImage up) {
        int w = stock.getWidth(), h = stock.getHeight(), k = up.getWidth() / w;
        if (k < 1 || up.getWidth() != w * k || up.getHeight() != h * k) throw new IllegalArgumentException(key + ": not an integer upscale");
        BufferedImage err = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double rgbSq = 0, aSq = 0;
        int worst = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] avg = new double[4]; // premultiplied r, g, b, and a, averaged over the k x k block
                for (int dy = 0; dy < k; dy++) {
                    for (int dx = 0; dx < k; dx++) {
                        int p = up.getRGB(x * k + dx, y * k + dy);
                        double a = (p >>> 24) / 255.0;
                        avg[0] += ((p >> 16) & 255) * a; avg[1] += ((p >> 8) & 255) * a; avg[2] += (p & 255) * a; avg[3] += p >>> 24;
                    }
                }
                for (int i = 0; i < 4; i++) avg[i] /= k * k;
                int s = stock.getRGB(x, y);
                double sa = (s >>> 24) / 255.0;
                double dr = avg[0] - ((s >> 16) & 255) * sa, dg = avg[1] - ((s >> 8) & 255) * sa, db = avg[2] - (s & 255) * sa, da = avg[3] - (s >>> 24);
                double d2 = (dr * dr + dg * dg + db * db) / 3;
                rgbSq += d2;
                aSq += da * da;
                int e = (int) Math.min(255, Math.max(Math.sqrt(d2), Math.abs(da)));
                worst = Math.max(worst, e);
                int v = Math.min(255, e * 4);
                err.setRGB(x, y, v << 16 | (v / 3) << 8);
            }
        }
        int n = w * h;
        return new Result(key, k, Math.sqrt(rgbSq / n), Math.sqrt(aSq / n), worst, stock, up, err);
    }

    /** Rows of: stock (nearest k x), upscaled, error map (nearest k x; brighter = further from stock). */
    static void sheet(List<Result> rows, File file) throws Exception {
        int width = 0, height = 0;
        for (Result r : rows) { width = Math.max(width, r.up.getWidth() * 3 + 40); height += r.up.getHeight() + 40; }
        BufferedImage out = new BufferedImage(Math.max(width, 1), Math.max(height, 1), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0x1c3a52));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setFont(new Font("Segoe UI", Font.BOLD, 18));
        int y = 0;
        for (Result r : rows) {
            int W = r.up.getWidth(), H = r.up.getHeight();
            g.setColor(Color.WHITE);
            g.drawString(String.format(Locale.ROOT, "%s   RGB %.2f  alpha %.2f  worst %d", r.key, r.rgbRms, r.alphaRms, r.worst), 10, y + 26);
            g.drawImage(r.stock, 10, y + 36, W, H, null);
            g.drawImage(r.up, 20 + W, y + 36, null);
            g.drawImage(r.err, 30 + 2 * W, y + 36, W, H, null);
            y += H + 40;
        }
        g.dispose();
        ImageIO.write(out, "png", file);
    }
}
