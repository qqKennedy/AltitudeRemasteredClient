package em.altitude.game.scene.player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's own settings, changed with /opt and kept in config/client.properties in the game's data folder
 * (%LOCALAPPDATA%/NimblyGames/Altitude). Every option has a fixed list of values; the first one is the default.
 * A missing file, key or unknown value means the default.
 */
public final class ClientOptions {
    private ClientOptions() {}

    /** One /opt setting. */
    record Option(String name, List<String> values, String help) {
        String defaultValue() { return values.get(0); }
    }

    static final List<String> OFF_ON = List.of("off", "on"), ON_OFF = List.of("on", "off");

    /** Every option, in the order /opt lists them. */
    static final List<Option> ALL = List.of(
            new Option("fps", List.of("auto", "stock"), "Frame-rate target. auto (default): your monitor's refresh rate, also in a window, where stock uses 60 fps (and stock caps fullscreen at 180). stock: the stock target. A frame-rate override in options.xml still wins."),
            new Option("framepacing", OFF_ON, "Steadier frame times: a fixed deadline per frame (sleep, then a short spin) instead of the stock 1 ms sleep steps. Same frame-rate target; menus and play. Costs CPU: it spins for up to 2 ms of every frame, so at a high frame-rate cap (400 fps) about 30% more of one core."),
            new Option("sharpfonts", ON_OFF, "HUD text drawn from fonts rasterized at the HUD scale (2-4x): same layout and size, sharp on high-resolution displays. OFF: the stock low-resolution bitmaps."),
            new Option("window", List.of("resizable", "normal", "borderless"), "Windowed mode (the game's fullscreen option off): resizable lets you drag the window to any size or maximise it; borderless fills the monitor chosen with /opt monitor, without borders (instant alt-tab). Applies the next time the window is created (restart or a display change)."),
            new Option("monitor", List.of("1", "2", "3", "4"), "Monitor for /opt window borderless: 1 is the main one. Applies the next time the window is created."),
            new Option("scorehud", ON_OFF, "Ball mode score: a faint backing behind the score so the team digits read on any background, and the sudden-death target once the server announces it (\"to 8\"). Display only."),
            new Option("perfhud", OFF_ON, "Two lines top right, updated each second: FPS, frame time (avg, p95, p99, max), frames longer than one refresh, GC pauses, game ticks per second, ping and heap. /opt perf prints the same line anywhere."),
            new Option("perflog", OFF_ON, "Append the same per-second measurements to config/client-perf.csv (menus and play). -Dclient.perflog=true logs from startup."),
            new Option("hdsprites", ON_OFF, "Draw the game world with upscaled sprites from the HD sprite pack (Altitude-1.1.9-hdsprites.zip next to the client jar), at the scale the window needs. Prepared in the background; textures reload once ready. Without the pack: stock sprites."));

    private static final Logger LOG = LoggerFactory.getLogger(ClientOptions.class);

    /** The build ID build.ps1 writes into the jar (commit, -dirty for uncommitted changes, date), or "unknown". */
    static final String BUILD_ID = readBuildId(ClientOptions.class.getResourceAsStream("build/id.txt"));
    static {
        LOG.info("Altitude REMASTERED client, build {}", BUILD_ID);
    }

    static String readBuildId(InputStream resource) {
        try (InputStream in = resource) {
            String id = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return id.isEmpty() ? "unknown" : id;
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static final Map<String, String> VALUES = new ConcurrentHashMap<>(parse(load(file())));
    private static long fileTime = modified(), nextCheck;

    private static long modified() {
        try {
            return Files.getLastModifiedTime(file()).toMillis();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Once per frame, from {@link ClientFrame}: about once a second, re-read the file when it was edited outside the game. */
    static void pollFile() {
        long now = System.nanoTime();
        if (now < nextCheck) return;
        nextCheck = now + 1_000_000_000L;
        long t = modified();
        if (t == fileTime) return;
        fileTime = t;
        VALUES.putAll(parse(load(file())));
    }

    static Path file() {
        String base = System.getenv("LOCALAPPDATA");
        return Path.of(base == null ? "." : base, "NimblyGames", "Altitude", "config", "client.properties");
    }

    static Option option(String name) {
        for (Option o : ALL) if (o.name().equalsIgnoreCase(name)) return o;
        return null;
    }

    /** The current value of an option (its default when unknown). */
    static String get(String name) {
        Option o = option(name);
        return o == null ? null : VALUES.getOrDefault(o.name(), o.defaultValue());
    }

    /** True when an on/off option is on. */
    public static boolean on(String name) { return "on".equals(get(name)); }

    /** A value in the option's own spelling, accepting true/false for on/off; null when not one of its values. */
    static String normalize(Option o, String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (o.values().equals(OFF_ON) || o.values().equals(ON_OFF)) v = v.equals("true") ? "on" : v.equals("false") ? "off" : v;
        return o.values().contains(v) ? v : null;
    }

    /** Sets an option and saves the file. False when the option or value is unknown. */
    static boolean set(String name, String value) throws IOException {
        Option o = option(name);
        String v = o == null ? null : normalize(o, value);
        if (v == null) return false;
        VALUES.put(o.name(), v);
        save(file(), VALUES);
        fileTime = modified();
        return true;
    }

    /** Valid values by option name; anything else keeps the default. */
    static Map<String, String> parse(Properties p) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Option o : ALL) {
            String v = normalize(o, p.getProperty(o.name()));
            m.put(o.name(), v == null ? o.defaultValue() : v);
        }
        return m;
    }

    static Properties load(Path file) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            // No file yet, or unreadable: defaults.
        }
        return p;
    }

    /**
     * Writes the options into the file, keeping any other keys in it; replaced in one move. An option at its default
     * is left out, so a later build's new default applies to it.
     */
    static void save(Path file, Map<String, String> values) throws IOException {
        Properties p = load(file);
        for (Map.Entry<String, String> e : values.entrySet()) {
            Option o = option(e.getKey());
            if (o != null && o.defaultValue().equals(e.getValue())) p.remove(o.name());
            else p.setProperty(e.getKey(), e.getValue());
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "Altitude client options (/opt)");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The build line, then "name = value", one per option. */
    static List<String> summary() {
        List<String> lines = new ArrayList<>();
        lines.add("Altitude REMASTERED build " + BUILD_ID);
        for (Option o : ALL) lines.add(o.name() + " = " + get(o.name()) + "  (" + String.join("|", o.values()) + ")");
        return lines;
    }
}
