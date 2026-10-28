package em.altitude.game.scene.player;

public class GammaTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        check(Math.abs(Gamma.exponent(0.5f) - 1f) < 1e-4f, "the default slider (0.5) is exponent 1: no pass");
        check(Gamma.exponent(0f) > 2.9f && Gamma.exponent(1f) < 0.4f, "the slider's ends: 3 (darker) and about 1/3 (brighter)");
        float last = Float.MAX_VALUE;
        for (int i = 0; i <= 100; i++) { float g = Gamma.exponent(i / 100f); check(g > 0 && g < last, "exponent falls as the slider rises"); last = g; }
        System.out.println("PASS: brightness slider to gamma exponent, as stock GLFrame.setDisplayConfiguration (1 at the default).");
    }
}
