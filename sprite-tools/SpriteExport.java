import em.common.io.stream.BitInputStream;
import em.common.io.stream.codec.ListStreamCodec;
import em.opengl.resource.image.ApegHelper;
import em.opengl.resource.image.Image;
import em.opengl.resource.image.ImagePackStorageType;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * PROTOTYPE tool: exports the textures of the stock image packs under a folder as upright PNGs named by content key
 * (RGBA byte count and CRC-32, as SpriteUpscale looks them up), for upscaling outside the game.
 * Usage: java -cp <stock libs>;. SpriteExport <game>/app/resources/dist/.image/render/planes <out dir>
 */
public class SpriteExport {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        List<Path> packs;
        try (Stream<Path> s = Files.walk(root)) { packs = s.filter(p -> p.getFileName().toString().matches("p[0-9]+")).sorted().toList(); }
        int n = 0;
        for (Path pack : packs) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(pack)))) {
                List<Image> images = new ListStreamCodec<Image>(Image.STREAM_CODEC).read(new BitInputStream(in));
                System.out.println("pack " + root.relativize(pack) + ": " + images.stream().map(Image::getPath).sorted().toList());
                int version = in.readInt();
                boolean legacy = ImagePackStorageType.isLegacyVersion(version);
                ImagePackStorageType type = legacy ? ImagePackStorageType.UNCOMPRESSED_RGBA : ImagePackStorageType.STORAGE_TYPE_BY_VERSION.get(version);
                if (!legacy) in.readInt(); // internal format follows the version
                in.readInt(); in.readInt(); // wrap, filter
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    int w = in.readInt(), h = in.readInt();
                    byte[] rgba;
                    if (type == ImagePackStorageType.UNCOMPRESSED_RGBA) { rgba = new byte[w * h * 4]; in.readFully(rgba); }
                    else if (type == ImagePackStorageType.APEG_1) { byte[] b = new byte[in.readInt()]; in.readFully(b); rgba = ApegHelper.loadApeg(new ByteArrayInputStream(b)).getRgba(); }
                    else { System.out.println("skip " + pack + " (" + type + ")"); break; }
                    CRC32 crc = new CRC32();
                    crc.update(rgba);
                    String key = String.format(Locale.ROOT, "%08x-%08x", rgba.length, crc.getValue());
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    for (int p = 0; p < w * h; p++) {
                        img.setRGB(p % w, h - 1 - p / w, (rgba[p * 4 + 3] & 255) << 24 | (rgba[p * 4] & 255) << 16 | (rgba[p * 4 + 1] & 255) << 8 | (rgba[p * 4 + 2] & 255));
                    }
                    ImageIO.write(img, "png", out.resolve(key + ".png").toFile());
                    System.out.println(key + " " + root.relativize(pack) + " #" + i + " " + w + "x" + h);
                    n++;
                }
            }
        }
        System.out.println(n + " textures exported to " + out);
    }
}
