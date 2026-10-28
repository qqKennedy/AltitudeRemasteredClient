package em.altitude.game.scene.player;

public class CursorLockTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    /** option, active, playing, alive, chatting, menu, console */
    static boolean lock(boolean... s) { return CursorLock.shouldLock(s[0], s[1], s[2], s[3], s[4], s[5], s[6]); }

    public static void main(String[] args) {
        check(lock(true, true, true, true, false, false, false), "flying, focused, option on: locked");
        check(!lock(false, true, true, true, false, false, false), "option off: released");
        check(!lock(true, false, true, true, false, false, false), "window not focused: released");
        check(!lock(true, true, false, true, false, false, false), "not in a match (title, loading): released");
        check(!lock(true, true, true, false, false, false, false), "plane dead (respawn, plane selection): released");
        check(!lock(true, true, true, true, true, false, false), "chatting: released");
        check(!lock(true, true, true, true, false, true, false), "game menu open: released");
        check(!lock(true, true, true, true, false, false, true), "console open: released");
        check("off".equals(ClientOptions.option("cursorlock").defaultValue()), "off by default");
        System.out.println("PASS: cursor lock only while flying, focused, with no menu, console or chat open.");
    }
}
