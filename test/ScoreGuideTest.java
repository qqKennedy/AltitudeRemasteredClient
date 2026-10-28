package em.altitude.game.scene.player;

public class ScoreGuideTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        check(ScoreGuide.targetOf("Sudden Death: First team with a point total of 8 wins.") == 8, "target from the server line");
        check(ScoreGuide.targetOf("gg") == -1, "no target in other chat");
        check("to 8".equals(ScoreGuide.line(8)), "target line");
        check(ScoreGuide.line(-1) == null, "nothing before the target is announced");
        check("to 7?".equals(ScoreGuide.estimateLine(7, 5)) && "to 7".equals(ScoreGuide.estimateLine(7, 6)), "estimate becomes definite at target - 1");
        System.out.println("PASS: score HUD parses the server's sudden-death target and formats target and estimate.");
    }
}
