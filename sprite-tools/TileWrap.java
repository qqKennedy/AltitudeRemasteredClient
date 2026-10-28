import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * For textures the game repeats (wrap mode repeat): an upscaler pads the edges of a single tile, which leaves seams
 * where tiles meet. "tile" lays the texture out 3 x 3 so the upscaler sees its real neighbours; "crop" cuts the centre
 * tile back out of the upscaled result.
 * Usage: java TileWrap tile IN.png OUT.png  |  java TileWrap crop IN.png OUT.png
 */
public class TileWrap {
    public static void main(String[] a) throws Exception {
        BufferedImage in = ImageIO.read(new File(a[1]));
        int w = in.getWidth(), h = in.getHeight();
        BufferedImage out;
        if (a[0].equals("tile")) {
            out = new BufferedImage(w * 3, h * 3, BufferedImage.TYPE_INT_ARGB);
            for (int ty = 0; ty < 3; ty++) for (int tx = 0; tx < 3; tx++) out.setRGB(tx * w, ty * h, w, h, in.getRGB(0, 0, w, h, null, 0, w), 0, w);
        } else if (a[0].equals("crop")) {
            if (w % 3 != 0 || h % 3 != 0) throw new IllegalArgumentException("not a 3 x 3 tiling: " + w + "x" + h);
            out = new BufferedImage(w / 3, h / 3, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w / 3, h / 3, in.getRGB(w / 3, h / 3, w / 3, h / 3, null, 0, w / 3), 0, w / 3);
        } else {
            throw new IllegalArgumentException("tile or crop");
        }
        ImageIO.write(out, "png", new File(a[2]));
    }
}
