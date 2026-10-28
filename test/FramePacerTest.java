package em.altitude.game.scene.player;

public class FramePacerTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        long p = 16_666_667L;
        check(FramePacer.next(0, 500, p) == 500, "first frame starts the schedule now");
        check(FramePacer.next(1000, 1000 + p / 2, p) == 1000 + p, "on time: one period after the last deadline");
        check(FramePacer.next(1000, 1000 + 2 * p, p) == 1000 + p, "up to one period late keeps the schedule");
        check(FramePacer.next(1000, 1000 + 2 * p + 1, p) == 1000 + 2 * p + 1, "more than a period late restarts now");
        System.out.println("PASS: frame pacer deadlines keep a fixed schedule and restart when late.");
    }
}
