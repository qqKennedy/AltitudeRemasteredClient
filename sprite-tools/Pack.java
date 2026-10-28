package em.altitude.game.scene.player;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * Builds the HD sprite pack the client reads (see SpritePack). Two steps, so the fidelity check runs in between:
 * <pre>
 * java Pack scale MASTERS_DIR OUT_DIR K            area-average every master PNG down to K x its stock size
 * java Pack zip DIR EXPORT_INDEX OUT.zip K "PIPELINE TEXT"
 * </pre>
 * The zip is reproducible: entries are stored (PNG is already compressed) in sorted order with a fixed date, so the
 * same PNGs always give the same pack. EXPORT_INDEX is SpriteExport's output, for where each texture is in the game.
 * Uses the client's own resampler and checksum, so the pack and the client agree.
 */
public class Pack {
    private static final long FIXED_TIME = 1_577_836_800_000L; // 2020-01-01, any constant will do

    public static void main(String[] a) throws Exception {
        if (a[0].equals("scale")) scale(new File(a[1]), new File(a[2]), Integer.parseInt(a[3]));
        else if (a[0].equals("zip")) zip(new File(a[1]), new File(a[2]), new File(a[3]), Integer.parseInt(a[4]), a[5]);
        else throw new IllegalArgumentException("scale or zip");
    }

    static void scale(File in, File out, int k) throws Exception {
        out.mkdirs();
        int n = 0;
        for (File f : sortedPngs(in)) {
            String key = f.getName().substring(0, f.getName().length() - 4);
            long stockPixels = Long.parseLong(key.substring(0, 8), 16) / 4;
            BufferedImage img = ImageIO.read(f);
            int W = img.getWidth(), H = img.getHeight(), fileK = (int) Math.round(Math.sqrt((double) W * H / stockPixels));
            if ((long) W * H != stockPixels * fileK * fileK || fileK < k) throw new IllegalArgumentException(key + ": " + W + "x" + H + " is not " + k + "x or more");
            int w = W / fileK * k, h = H / fileK * k;
            int[] argb = img.getRGB(0, 0, W, H, null, 0, W);
            if (fileK != k) argb = SpriteUpscale.resample(argb, W, H, w, h);
            BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            o.setRGB(0, 0, w, h, argb, 0, w);
            ImageIO.write(o, "png", new File(out, f.getName()));
            n++;
        }
        System.out.println("Pack: " + n + " textures scaled to " + k + "x in " + out);
    }

    static void zip(File dir, File index, File out, int k, String pipeline) throws Exception {
        Map<String, List<String>> where = new TreeMap<>();
        Matcher m = Pattern.compile("^([0-9a-f]{8}-[0-9a-f]{8}) (\\S+) #(\\d+) ").matcher("");
        for (String line : Files.readAllLines(index.toPath())) {
            if (m.reset(line).find()) where.computeIfAbsent(m.group(1), x -> new ArrayList<>()).add(m.group(2).replace('\\', '/') + "#" + m.group(3));
        }
        StringBuilder manifest = new StringBuilder(SpritePack.FORMAT + "\n" + SpritePack.GAME + "\nscale " + k + "\npipeline " + pipeline + "\n");
        Map<String, byte[]> pngs = new TreeMap<>();
        for (File f : sortedPngs(dir)) {
            String key = f.getName().substring(0, f.getName().length() - 4);
            byte[] bytes = Files.readAllBytes(f.toPath());
            List<String> w = where.get(key);
            if (w == null) throw new IllegalArgumentException(key + " is not in " + index);
            pngs.put(key, bytes);
            manifest.append("texture ").append(key).append(' ').append(SpritePack.sha256(bytes)).append(' ').append(String.join(",", w)).append('\n');
        }
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            z.setMethod(ZipOutputStream.STORED);
            put(z, "manifest.txt", manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : pngs.entrySet()) put(z, "sprites/" + e.getKey() + ".png", e.getValue());
        }
        byte[] packBytes = Files.readAllBytes(out.toPath());
        System.out.printf("Pack: %d textures at %dx, %.1f MB, SHA-256 %s%n", pngs.size(), k, packBytes.length / 1048576.0, SpritePack.sha256(packBytes));
    }

    private static void put(ZipOutputStream z, String name, byte[] bytes) throws Exception {
        ZipEntry e = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        e.setSize(bytes.length); e.setCompressedSize(bytes.length); e.setCrc(crc.getValue()); e.setTime(FIXED_TIME);
        z.putNextEntry(e);
        z.write(bytes);
        z.closeEntry();
    }

    private static List<File> sortedPngs(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.matches("[0-9a-f]{8}-[0-9a-f]{8}\\.png"));
        if (fs == null) throw new IllegalArgumentException("No PNGs in " + dir);
        List<File> list = new ArrayList<>(List.of(fs));
        list.sort(null);
        return list;
    }
}
