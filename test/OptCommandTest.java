package em.altitude.game.scene.player;

import em.altitude.game.console.AltitudeConsoleCommandAutoCompletion;
import java.util.ArrayList;
import java.util.List;

public class OptCommandTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    /** What the stock console completes for these words, through the command's own completion path. */
    static List<Object> completions(OptCommand cmd, String... words) {
        List<Object> out = new ArrayList<>();
        for (boolean flag : new boolean[] {false, true}) {
            AltitudeConsoleCommandAutoCompletion[] c = cmd.getAutoCompletions(words, flag);
            if (c != null) for (AltitudeConsoleCommandAutoCompletion a : c) out.add(a.getAutoCompletion());
        }
        return out;
    }

    public static void main(String[] args) {
        OptCommand cmd = new OptCommand();
        check(cmd.getParameters(new String[] {"framepacing"}).length == 2, "an option takes a value");
        check(cmd.getParameters(new String[] {"perf"}).length == 1 && cmd.getParameters(new String[0]).length == 1, "perf and no option take no value");
        List<Object> pacing = completions(cmd, "framepacing", "");
        check(pacing.contains("on") && pacing.contains("off") && !pacing.contains("resizable"), "framepacing completes on|off only: " + pacing);
        List<Object> window = completions(cmd, "window", "");
        check(window.contains("resizable") && window.contains("normal") && !window.contains("on"), "window completes resizable|normal only: " + window);
        check(completions(cmd, "fr").contains("framepacing"), "option names complete");
        System.out.println("PASS: /opt completes each option's own values.");
    }
}
