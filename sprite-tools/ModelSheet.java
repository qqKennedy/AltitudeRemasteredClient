import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Compares upscaler models on chosen textures: one row per texture, one column per model, plus stock (nearest
 * neighbour) first. Every cell is drawn at the same size (ZOOM x the stock size) over a dark backing, so different
 * scale factors line up. Usage: java ModelSheet STOCK_DIR OUT.png ZOOM KEY1,KEY2,... NAME=DIR [NAME=DIR ...]
 */
public class ModelSheet {
    public static void main(String[] a) throws Exception {
        File stockDir = new File(a[0]);
        int zoom = Integer.parseInt(a[2]);
        String[] keys = a[3].split(",");
        int models = a.length - 4;
        BufferedImage[] stock = new BufferedImage[keys.length];
        int cellW = 0, rowsH = 0;
        for (int i = 0; i < keys.length; i++) {
            stock[i] = ImageIO.read(new File(stockDir, keys[i] + ".png"));
            cellW = Math.max(cellW, stock[i].getWidth() * zoom);
            rowsH += stock[i].getHeight() * zoom + 10;
        }
        cellW = Math.max(cellW, 160);
        BufferedImage out = new BufferedImage((cellW + 10) * (models + 1) + 10, rowsH + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0x1c3a52));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 16));
        g.drawString("stock", 10, 24);
        for (int m = 0; m < models; m++) g.drawString(a[4 + m].split("=", 2)[0], 10 + (cellW + 10) * (m + 1), 24);
        int y = 34;
        for (int i = 0; i < keys.length; i++) {
            int w = stock[i].getWidth() * zoom, h = stock[i].getHeight() * zoom;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(stock[i], 10, y, w, h, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (int m = 0; m < models; m++) {
                File f = new File(a[4 + m].split("=", 2)[1], keys[i] + ".png");
                if (f.isFile()) g.drawImage(ImageIO.read(f), 10 + (cellW + 10) * (m + 1), y, w, h, null);
            }
            y += h + 10;
        }
        g.dispose();
        ImageIO.write(out, "png", new File(a[1]));
    }
}
