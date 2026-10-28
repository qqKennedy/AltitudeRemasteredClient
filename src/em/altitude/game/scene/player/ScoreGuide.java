package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.altitude.game.PlaneBallGameMode;
import em.altitude.game.model.Team;
import em.common.util.TimeUtil;
import em.opengl.render.Color;
import em.opengl.render.RU;
import em.opengl.resource.font.Font;
import em.opengl.resource.font.builder.FontList;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL11;

/**
 * /opt scorehud (ball mode, display only): a faint backing behind the stock score so the team-coloured digits read on
 * any background, and inside it, right of the clock, the sudden-death target ("to 8") once the server announces it.
 * No countdown to sudden death: the round clock already counts down to it. It sits inline so nothing covers the
 * ball-carrier label under the score.
 * <p>
 * The target comes from the server's own chat line ("Sudden Death: First team with a point total of N wins."), as
 * the planeball.com ladder servers send it. The server sends it once (leader's score + 2 at that moment), so after
 * joining mid sudden death (clock at 0:00, play continuing) it is estimated as the top score first seen + 2 and shown
 * with a "?". The estimate is an upper bound and any score reached without the game ending is a lower bound (target >
 * score), so once a team reaches estimate - 1 the "?" is dropped.
 */
public final class ScoreGuide {
    private ScoreGuide() {}

    static final Pattern TARGET = Pattern.compile("point total of (\\d+) wins", Pattern.CASE_INSENSITIVE);
    /** Team colour blended toward white, so dark team colours (blue) stay readable on the dark backing. */
    static final float LIGHTEN = 0.35f;

    private static volatile int target = -1;
    private static int estimate = -1;                // late join: top score when sudden death was first seen, + 2
    private static WeakReference<Object> round = new WeakReference<Object>(null);
    private static final Field POINTS = pointsField();

    /** Hook: start of PlayerScene.onChatEvent. Only server messages (player id -1) are read. */
    public static void onChat(int playerId, boolean teamOnly, String message) {
        if (playerId != -1 || message == null) return;
        int t = targetOf(message);
        if (t > 0) target = t;
    }

    static int targetOf(String message) {
        Matcher m = TARGET.matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Late-join estimate: definite once the top score (play still going) is one below it. */
    static String estimateLine(int estimate, int top) { return top >= estimate - 1 ? "to " + estimate : "to " + estimate + "?"; }

    /** The extra text right of the clock, or null. */
    static String line(int target) { return target > 0 ? "to " + target : null; }

    private static Field pointsField() {
        try {
            Field f = PlaneBallGameMode.class.getDeclaredField("pointsByTeam");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A team's ball-mode score, or -1 when unknown. */
    static int points(PlaneBallGameMode mode, Team team) {
        try {
            Object v = POINTS == null ? null : ((Map<?, ?>) POINTS.get(mode)).get(team);
            return v instanceof Integer i ? i : -1;
        } catch (IllegalAccessException | RuntimeException e) {
            return -1;
        }
    }

    private static void lightened(Color c) { RU.color(c.r + (1 - c.r) * LIGHTEN, c.g + (1 - c.g) * LIGHTEN, c.b + (1 - c.b) * LIGHTEN, 1f); }

    /** Hook: end of PlayerScene.render. */
    public static void render(PlayerScene scene, float interp) {
        try {
            AltitudeGame game = scene.getGame();
            if (!ClientOptions.on("scorehud") || !(game.getGameMode() instanceof PlaneBallGameMode mode)) return;
            // A new round (or map) clears the previous sudden-death state.
            if (round.get() != mode) {
                round = new WeakReference<Object>(mode);
                target = -1; estimate = -1;
            }
            if (mode.isWarmingUp()) return;
            if (mode.isBetweenRounds()) { target = -1; estimate = -1; }
            List<Team> teams = mode.getAllowedTeams();
            if (teams.size() < 2) return;
            int left = points(mode, teams.get(0)), right = points(mode, teams.get(1));
            if (left < 0 || right < 0) return;
            Font font = game.getResourceManager().getFont(FontList.BLAMBOT_19_BOLD_1);
            // Same layout as PlaneBallGameMode.renderHud.
            int y = 715, x = mode.isRoundTimeLimitSet() ? 590 : 640;
            String l = Integer.toString(left), r = Integer.toString(right);
            int x0 = x - 10 - font.getWidth(l) - 8, x1 = x + 10 + font.getWidth(r) + 8;
            String clock = mode.isRoundTimeLimitSet()
                    ? TimeUtil.toClockString((long) (1000 * mode.getRoundTimeLeftSeconds(interp)), (long) (1000 * mode.getRoundTimeInitialSeconds())) : null;
            if (clock != null) x1 = Math.max(x1, 675 + font.getWidth(clock) + 8);
            boolean overtime = clock != null && mode.getRoundTimeLeftSeconds(interp) <= 0 && !mode.isBetweenRounds();
            if (overtime && target < 0 && estimate < 0) estimate = Math.max(left, right) + 2;
            String extra = target < 0 && overtime && estimate > 0 ? estimateLine(estimate, Math.max(left, right)) : line(target);
            Font small = game.getResourceManager().getFont(FontList.VERDANA_11_BOLD_OUTLINE);
            int extraX = x1 + 2;
            if (extra != null) x1 = extraX + small.getWidth(extra) + 8;
            // The digits occupy about y-21..y-4 (measured in game); pad 3 units around them.
            int y0 = y - 24, y1 = y - 1;
            int matrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_SCISSOR_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            HudDraw.hudProjection();
            try {
                HudDraw.resetTextureState();
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                HudDraw.clipToGameArea();
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(0, 0, 0, 0.35f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x0, y0); GL11.glVertex2f(x1, y0); GL11.glVertex2f(x1, y1); GL11.glVertex2f(x0, y1);
                GL11.glEnd();
                // Redraw the stock digits over the backing, exactly where the stock HUD placed them.
                lightened(teams.get(0).getFontColor());
                font.render(l, x - 10, y, 24);
                lightened(teams.get(1).getFontColor());
                font.render(r, x + 10, y, 20);
                if (clock != null) { RU.color(0.8f, 0.8f, 0.8f, 1f); font.render(clock, 675, y, 20); }
                if (extra != null) { RU.color(0.82f, 0.82f, 0.82f, 0.9f); small.render(extra, extraX, (y0 + y1) / 2, 2); }
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(matrix);
                GL11.glPopAttrib();
                HudDraw.resetTextureState();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Display only: never break the frame.
        }
    }
}
