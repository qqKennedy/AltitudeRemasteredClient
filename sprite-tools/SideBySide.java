import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Puts the same region of two screenshots side by side with labels, for before/after comparisons.
 * Usage: java SideBySide A.png LABEL_A B.png LABEL_B OUT.png [x y w h]   (region defaults to the top-left quarter)
 */
public class SideBySide {
    public static void main(String[] a) throws Exception {
        BufferedImage left = ImageIO.read(new File(a[0])), right = ImageIO.read(new File(a[2]));
        int x = 0, y = 0, w = left.getWidth() / 2, h = left.getHeight() / 2;
        if (a.length >= 9) { x = Integer.parseInt(a[5]); y = Integer.parseInt(a[6]); w = Integer.parseInt(a[7]); h = Integer.parseInt(a[8]); }
        w = Math.min(w, Math.min(left.getWidth(), right.getWidth()) - x);
        h = Math.min(h, Math.min(left.getHeight(), right.getHeight()) - y);
        BufferedImage out = new BufferedImage(w * 2 + 30, h + 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0x10202c));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 28));
        g.drawString(a[1], 10, 36);
        g.drawString(a[3], w + 20, 36);
        g.drawImage(left.getSubimage(x, y, w, h), 10, 46, null);
        g.drawImage(right.getSubimage(x, y, w, h), w + 20, 46, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[4]));
    }
}
