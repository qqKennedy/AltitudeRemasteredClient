package em.altitude.game.scene.player;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

public class SpriteUpscaleTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) throws Exception {
        check(SpriteUpscale.scaleFor(1280, 720) == 1 && SpriteUpscale.scaleFor(1600, 900) == 2, "720 stock, 900 2x");
        check(SpriteUpscale.scaleFor(1920, 1080) == 2 && SpriteUpscale.scaleFor(2560, 1440) == 2, "1080 and 1440 2x");
        check(SpriteUpscale.scaleFor(3840, 2160) == 3 && SpriteUpscale.scaleFor(5120, 2880) == 4 && SpriteUpscale.scaleFor(7680, 4320) == 4, "4K 3x, 5K and up 4x");
        check(SpriteUpscale.scaleFor(3840, 1080) == 2, "the 1280x720 screen is letterboxed: the smaller ratio counts");

        // 4x4 -> 2x2 is an exact box average in premultiplied colour: a transparent pixel adds no colour.
        int[] src = new int[16];
        for (int i = 0; i < 16; i++) src[i] = 0xFF204060;
        src[0] = 0x00FFFFFF;
        int[] half = SpriteUpscale.resample(src, 4, 4, 2, 2);
        check(half[0] >>> 24 == 191 && (half[0] & 0xFFFFFF) == 0x204060, "box average, premultiplied: " + Integer.toHexString(half[0]));
        check(half[3] == 0xFF204060, "opaque block unchanged");
        int[] three = SpriteUpscale.resample(src, 4, 4, 3, 3);
        check(three[8] == 0xFF204060 && three[0] >>> 24 < 255, "4 to 3 area average");

        // A 4x replacement of a 2x1 stock texture, converted to the 3x cache: header, size, bottom-up rows.
        Path dir = Files.createTempDirectory("hdsprites");
        BufferedImage img = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) img.setRGB(x, y, y < 2 ? 0xFFFF0000 : 0x800000FF);
        ByteBuffer stock = ByteBuffer.allocate(8);
        String key = SpriteUpscale.key(stock, 0, 8);
        File png = dir.resolve(key + ".png").toFile(), cache = dir.resolve(key + ".rgba").toFile();
        ImageIO.write(img, "png", png);
        SpriteUpscale.convert(key, Files.readAllBytes(png.toPath()), cache, 3);
        ByteBuffer c = ByteBuffer.wrap(Files.readAllBytes(cache.toPath()));
        // 6x3, then mipmaps 3x1 and 1x1.
        check(c.getInt(0) == 0x48445332 && c.getInt(4) == 6 && c.getInt(8) == 3 && c.getInt(12) == 3, "header: 3x size, 3 levels");
        check(c.capacity() == 16 + (6 * 3 + 3 * 1 + 1) * 4 && SpriteUpscale.levelBytes(6, 3) == (6 * 3 + 3 + 1) * 4, "all levels stored");
        check(c.get(16) == 0 && c.get(18) == (byte) 255 && c.get(19) == (byte) 128, "first row is the bottom one (blue, half alpha)");
        check(c.get(16 + 2 * 6 * 4) == (byte) 255 && c.get(16 + 2 * 6 * 4 + 3) == (byte) 255, "last row is the top one (opaque red)");
        check(SpriteUpscale.levels(1536, 1536) == 11 && SpriteUpscale.levels(1, 1) == 1 && SpriteUpscale.levels(8, 2) == 4, "level counts");
        SpriteUpscale.convert(key, Files.readAllBytes(png.toPath()), cache, 4);
        check(Files.size(cache.toPath()) == 16 + SpriteUpscale.levelBytes(8, 4), "no resampling when the scale matches the file");
        boolean refused = false;
        try { ImageIO.write(new BufferedImage(5, 4, BufferedImage.TYPE_INT_ARGB), "png", png); SpriteUpscale.convert(key, Files.readAllBytes(png.toPath()), cache, 2); }
        catch (java.io.IOException e) { refused = true; }
        check(refused, "a PNG that is not 2-4x of the stock size is refused");
        for (File f : dir.toFile().listFiles()) f.delete();
        dir.toFile().delete();
        packs();
        System.out.println("PASS: hdsprites upload scale per window size, premultiplied area resampling, cache file format, "
                + "pack manifest and checksums, folder override, cache clean-up.");
    }

    static void zip(File f, String manifest, String entry, byte[] bytes) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("manifest.txt")); z.write(manifest.getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            if (entry != null) { z.putNextEntry(new ZipEntry(entry)); z.write(bytes); z.closeEntry(); }
        }
    }

    static void packs() throws Exception {
        Path dir = Files.createTempDirectory("hdpack");
        byte[] png = { 1, 2, 3 };
        String key = "00000010-0000abcd", sha = SpritePack.sha256(png), head = SpritePack.FORMAT + "\n" + SpritePack.GAME + "\nscale 3\npipeline test\n";
        check(sha.equals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"), "SHA-256 of 1,2,3: " + sha);
        File ok = dir.resolve("ok.zip").toFile();
        zip(ok, head + "texture " + key + " " + sha + " render/planes/x/p0#0\n", "sprites/" + key + ".png", png);
        try (ZipFile z = new ZipFile(ok)) {
            SpritePack.Contents c = SpritePack.readPack(z, null);
            Map<String, SpritePack.Source> m = c.textures();
            check(c.scale() == 3, "pack scale from the manifest");
            check(m.size() == 1 && java.util.Arrays.equals(m.get(key).read(), png), "pack entry read and verified");
            check(m.get(key).stamp().equals(sha.substring(0, 16)), "pack stamp is the start of its checksum");
        }
        File bad = dir.resolve("bad.zip").toFile();
        zip(bad, head + "texture " + key + " " + sha + " x#0\n", "sprites/" + key + ".png", new byte[] { 1, 2, 4 });
        boolean caught = false;
        try (ZipFile z = new ZipFile(bad)) { SpritePack.readPack(z, null).textures().get(key).read(); } catch (java.io.IOException e) { caught = e.getMessage().contains("checksum"); }
        check(caught, "a PNG that does not match the manifest is refused");
        File other = dir.resolve("other.zip").toFile();
        zip(other, SpritePack.FORMAT + "\ngame 1.1.8\ntexture " + key + " " + sha + " x#0\n", null, null);
        caught = false;
        try (ZipFile z = new ZipFile(other)) { SpritePack.readPack(z, null); } catch (java.io.IOException e) { caught = true; }
        check(caught, "a pack for another game version is refused");
        String manifest = head + "texture " + key + " " + sha + " render/planes/x/p0#0\n";
        caught = false;
        try (ZipFile z = new ZipFile(ok)) { SpritePack.readPack(z, "0".repeat(64)); } catch (java.io.IOException e) { caught = e.getMessage().contains("official"); }
        check(caught, "with a pinned manifest checksum, any other pack is refused");
        try (ZipFile z = new ZipFile(ok)) { check(SpritePack.readPack(z, SpritePack.sha256(manifest.getBytes(StandardCharsets.UTF_8))).textures().size() == 1, "the pinned pack is read"); }
        check(!SpritePack.TEST_BUILD && SpritePack.MANIFEST_SHA256.matches("[0-9a-f]{64}"), "the release classes take only the pinned pack, no loose PNGs");

        File loose = dir.resolve("loose").toFile();
        loose.mkdirs();
        Files.write(loose.toPath().resolve(key + ".png"), png);
        Map<String, SpritePack.Source> d = SpritePack.readDir(loose);
        check(d.get(key) instanceof SpritePack.FileSource && !d.get(key).stamp().equals(sha.substring(0, 16)), "loose PNGs are found and stamped by date and size");

        File cache = dir.resolve("cache").toFile(), cur = new File(cache, "hds2-3x"), old = new File(cache, "hds2-2x");
        cur.mkdirs(); old.mkdirs();
        for (String n : new String[] { "a.rgba", "b.rgba" }) Files.write(cur.toPath().resolve(n), png);
        Files.write(old.toPath().resolve("c.rgba"), png);
        check(SpriteUpscale.cleanCache(cur, java.util.Set.of("a.rgba")) == 2, "two stale files removed");
        check(new File(cur, "a.rgba").isFile() && !new File(cur, "b.rgba").exists() && !old.exists(), "kept the current file, removed the rest and the other scale");
        for (File f : new File[] { new File(cur, "a.rgba"), cur, cache, new File(loose, key + ".png"), loose, ok, bad, other, dir.toFile() }) f.delete();
    }
}
