package em.altitude.game.scene.player;

import java.awt.Rectangle;

public class WindowModeTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        check(WindowMode.physical(new Rectangle(0, 0, 2560, 1440), 1.5, 1.5).equals(new Rectangle(0, 0, 3840, 2160)), "a 150% 4K main monitor");
        check(WindowMode.physical(new Rectangle(2560, -200, 1920, 1080), 1.0, 1.0).equals(new Rectangle(2560, -200, 1920, 1080)), "a 100% second monitor, left of nothing, above");
        check(WindowMode.physical(new Rectangle(-1280, 0, 1280, 720), 1.5, 1.5).equals(new Rectangle(-1920, 0, 1920, 1080)), "a scaled monitor to the left");
        System.out.println("PASS: borderless window bounds: AWT monitor bounds scaled to physical pixels.");
    }
}
