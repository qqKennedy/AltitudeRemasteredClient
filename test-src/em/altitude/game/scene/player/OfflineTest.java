package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;
import em.network.IP;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import vapor.client.Distributor;

/**
 * TEST BUILD ONLY (out\Altitude-1.1.9-REMASTERED-test.jar, never the release jar). With -Dclient.offline=true the client
 * never starts the Steam API and goes straight to the stock offline login ("Offline Player": bots and local games),
 * the same path as the stock "Play Offline" button on the no-connection dialog. Test launches therefore never sign in
 * to an account. Without the property nothing changes.
 * Hooks: end of AltitudeGame.initializeDistributor; start of AltitudeGame.updateCreateVaporClient, which returns false
 * without running the stock body while this returns true.
 */
public final class OfflineTest {
    private OfflineTest() {}

    static final boolean ENABLED = Boolean.getBoolean("client.offline");
    private static boolean reentrant, loggedIn;

    private static Field field(String name) throws ReflectiveOperationException {
        Field f = AltitudeGame.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Hook: end of AltitudeGame.initializeDistributor. Not Steam, so the Steam API is never started. */
    public static void afterDistributor(AltitudeGame game) {
        if (!ENABLED) return;
        try {
            field("distributor").set(game, Distributor.NIMBLY_GAMES);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("client.offline: cannot leave Steam out", e);
        }
    }

    /** Hook: start of AltitudeGame.updateCreateVaporClient; true skips the stock (online) body. */
    public static boolean createClient(AltitudeGame game, boolean arg) {
        if (!ENABLED || reentrant) return false;
        if (loggedIn) return true;
        try {
            Field vaporIp = field("resolveVaporIp"), googleIp = field("resolveGoogleIp");
            // Wait until the stock lookups have filled these, so they cannot overwrite the unreachable addresses below.
            if (vaporIp.get(null) == null || googleIp.get(null) == null) return true;
            field("usingInvalidVaporIp").setBoolean(null, true);
            vaporIp.set(null, new IP(new byte[] { 8, 8, 8, 8 }, 1001));
            googleIp.set(null, new IP(new byte[] { 8, 8, 8, 8 }, 1002));
            Method update = AltitudeGame.class.getDeclaredMethod("updateCreateVaporClient", boolean.class);
            update.setAccessible(true);
            boolean created;
            reentrant = true;
            try {
                created = (Boolean) update.invoke(game, false);
            } finally {
                reentrant = false;
            }
            if (created) {
                game.getSceneManager().createLoginWindow(false, true);
                loggedIn = true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Stay offline; retry next frame. Never fall back to an online login.
        }
        return true;
    }
}
