package em.altitude.game.scene.player;

public class FrameTargetTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        check(FrameTarget.stock(0) == 60 && FrameTarget.stock(144) == 144 && FrameTarget.stock(240) == 180, "stock: 60 without a rate, capped at 180");
        check(FrameTarget.target(144) == 144 && FrameTarget.target(240) == 240 && FrameTarget.target(360) == 360, "auto: the monitor's rate, above 180 too");
        check(FrameTarget.target(59) == 59 && FrameTarget.target(0) == 0 && FrameTarget.target(1) == 0 && FrameTarget.target(5000) == 0, "auto keeps stock for missing or odd rates");
        System.out.println("PASS: frame-rate target follows the monitor (stock 60 in a window, 180 cap) and keeps stock for odd rates.");
    }
}
