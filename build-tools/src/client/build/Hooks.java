package client.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every change the patched jar makes to stock classes, and the fix each client class belongs to. The patcher applies
 * exactly these hooks and the jar diff accepts exactly these differences, so this file is the whole patch surface.
 */
final class Hooks {
    private Hooks() {}

    /** SHA-256 of the stock 1.1.9 jars the hooks were written against. */
    static final String GAME_JAR = "Altitude-1.1.9.jar";
    static final String GAME_SHA256 = "8afc6b1cc62751d01fb4bfc96a0207b3500b85af55329edbc6d5c7a932a50ef8";
    static final String WRAPPERS_JAR = "EmWrappers-1.1.9.jar";
    static final String WRAPPERS_SHA256 = "abbdf98aa4608d9ca7094b7a8767d7230661bb136fe758830a2b9792eb9bebcb";

    /** Where the stock class lives. Patched EmWrappers classes are written into the game jar, which shadows them. */
    enum Jar { GAME, WRAPPERS }

    /** ENTRY: before the first instruction. BEFORE_RETURN: before every return instruction. */
    enum At { ENTRY, BEFORE_RETURN }

    /**
     * CALL: call the helper, then continue with the stock code. RETURN_IF_TRUE: return when the helper returns true
     * (nothing, false, 0 or null, by the method's return type). RETURN_IF_NONNULL: return the helper's result when it
     * is not null.
     */
    enum Mode { CALL, RETURN_IF_TRUE, RETURN_IF_NONNULL }

    /**
     * One static call inserted into a stock method. args name what is passed: "this", "argN" (1-based parameter) or
     * "this.field". sites is how many places it must be inserted (1 for ENTRY, the number of returns otherwise);
     * any other count fails the build.
     */
    record Hook(String fix, Jar jar, String owner, String method, At at, int sites, Mode mode, String helper, String helperMethod, List<String> args) {}

    static final String CLIENT = "em/altitude/game/scene/player/";

    /** Fix ids and one-line descriptions, in commit order. */
    static final Map<String, String> FIXES = new LinkedHashMap<>();
    static {
        FIXES.put("opt", "/opt console command, the client option store and the build ID (logged at start-up, first line of /opt)");
        FIXES.put("frame", "per-frame hook shared by the fixes (start of AltitudeGame.render)");
        FIXES.put("images", "faster image loading: APEG packs decoded off the render thread, game-wide packs precached in the menu");
        FIXES.put("pacing", "frame pacing: a fixed deadline per frame instead of the stock 1 ms sleep steps (/opt framepacing)");
        FIXES.put("fonts", "sharp HUD fonts: text drawn from fonts rasterized at the HUD scale, stock metrics (/opt sharpfonts)");
        FIXES.put("window", "resizable window: windowed mode can be dragged to any size or maximised (/opt window)");
        FIXES.put("score", "score HUD: backing behind the ball-mode score and the announced sudden-death target (/opt scorehud)");
        FIXES.put("perf", "perf HUD and log: per-second frame-time statistics, GC pauses, ticks, ping, heap (/opt perfhud, perflog, perf)");
        FIXES.put("fps", "frame-rate target follows the monitor's refresh rate, also windowed (stock: 60 fps in a window, at most 180) (/opt fps)");
        FIXES.put("shots", "screenshots without the freeze: the screen is read on the render thread, the PNG written on a worker (stock file, console line and dialog)");
        FIXES.put("hdsprites","HD sprites (/opt hdsprites, on by default): world textures replaced by upscaled art from Altitude-1.1.9-hdsprites.zip next to the client jar");
    }

    static final List<Hook> HOOKS = new java.util.ArrayList<>(List.of(
            new Hook("opt", Jar.GAME, "em/altitude/game/console/AltitudeConsoleCommandManager", "createCommands(Z)V",
                    At.ENTRY, 1, Mode.CALL, CLIENT + "OptCommand", "register", List.of("this.clientCommands")),
            new Hook("frame", Jar.GAME, "em/altitude/game/AltitudeGame", "render()V",
                    At.ENTRY, 1, Mode.CALL, CLIENT + "ClientFrame", "frame", List.of("this")),
            new Hook("images", Jar.WRAPPERS, "em/opengl/resource/image/ImagePack",
                    "readImageBytes(Ljava/lang/String;Ljava/nio/ByteBuffer;Lem/opengl/resource/image/ImageManager$TextureQuality;)[Lem/opengl/resource/image/Texture;",
                    At.ENTRY, 1, Mode.RETURN_IF_NONNULL, CLIENT + "FastImagePack", "read", List.of("arg1", "arg2", "arg3")),
            new Hook("images", Jar.WRAPPERS, "em/opengl/resource/image/ImageManager", "getPrecacheLoadImagesTask(Ljava/util/List;)Lem/common/util/MultipartTask;",
                    At.ENTRY, 1, Mode.CALL, CLIENT + "FastImagePack", "prefetch", List.of("this", "arg1")),
            new Hook("pacing", Jar.WRAPPERS, "em/opengl/ui/GLFrame", "sleep()V",
                    At.ENTRY, 1, Mode.RETURN_IF_TRUE, CLIENT + "FramePacer", "sleep", List.of("this")),
            new Hook("fonts", Jar.WRAPPERS, "em/opengl/resource/font/Font", "render(Ljava/lang/CharSequence;IIII)V",
                    At.ENTRY, 1, Mode.RETURN_IF_TRUE, CLIENT + "HudFontSharpen", "render", List.of("this", "arg1", "arg2", "arg3", "arg4", "arg5")),
            new Hook("fonts", Jar.WRAPPERS, "em/opengl/resource/font/Font", "reloadImage()V",
                    At.ENTRY, 1, Mode.CALL, CLIENT + "HudFontSharpen", "reload", List.of("this")),
            new Hook("window", Jar.WRAPPERS, "em/opengl/ui/GLFrame", "setDisplayMode(Lorg/lwjgl/opengl/DisplayMode;Lorg/lwjgl/opengl/PixelFormat;ZZ)V",
                    At.BEFORE_RETURN, 1, Mode.CALL, CLIENT + "WindowMode", "afterCreate", List.of("arg3")),
            new Hook("fps", Jar.WRAPPERS, "em/opengl/ui/GLFrame", "setDisplayMode(Lorg/lwjgl/opengl/DisplayMode;Lorg/lwjgl/opengl/PixelFormat;ZZ)V",
                    At.BEFORE_RETURN, 1, Mode.CALL, CLIENT + "FrameTarget", "afterCreate", List.of("this", "arg3")),
            new Hook("score", Jar.GAME, "em/altitude/game/scene/player/PlayerScene", "onChatEvent(IZLjava/lang/String;)V",
                    At.ENTRY, 1, Mode.CALL, CLIENT + "ScoreGuide", "onChat", List.of("arg1", "arg2", "arg3")),
            new Hook("score", Jar.GAME, "em/altitude/game/scene/player/PlayerScene", "render(F)V",
                    At.BEFORE_RETURN, 1, Mode.CALL, CLIENT + "ScoreGuide", "render", List.of("this", "arg1")),
            new Hook("perf", Jar.GAME, "em/altitude/game/scene/player/PlayerScene", "render(F)V",
                    At.BEFORE_RETURN, 1, Mode.CALL, CLIENT + "PerfMonitor", "render", List.of("this")),
            new Hook("shots", Jar.GAME, "em/altitude/game/scene/SceneManager", "takeScreenshot()V",
                    At.ENTRY, 1, Mode.RETURN_IF_TRUE, CLIENT + "Screenshots", "take", List.of("this")),
            new Hook("hdsprites", Jar.WRAPPERS, "em/opengl/resource/image/ImagePack",
                    "loadTextureFromRgba(Lem/opengl/resource/image/ImageDescriptor$InternalFormat;IILjava/nio/ByteBuffer;)V",
                    At.ENTRY, 1, Mode.RETURN_IF_TRUE, CLIENT + "SpriteUpscale", "upload", List.of("arg1", "arg2", "arg3", "arg4"))));

    /** Client classes by simple name (nested classes belong to their outer class), and their fix. */
    static final Map<String, String> CLASSES = new LinkedHashMap<>();
    static {
        CLASSES.put("ClientOptions", "opt");
        CLASSES.put("OptCommand", "opt");
        CLASSES.put("ClientFrame", "frame");
        CLASSES.put("FastImagePack", "images");
        CLASSES.put("FramePacer", "pacing");
        CLASSES.put("HudFontSharpen", "fonts");
        CLASSES.put("WindowMode", "window");
        CLASSES.put("ScoreGuide", "score");
        CLASSES.put("HudDraw", "score");
        CLASSES.put("PerfStats", "perf");
        CLASSES.put("PerfMonitor", "perf");
        CLASSES.put("FrameTarget", "fps");
        CLASSES.put("Screenshots", "shots");
        CLASSES.put("SpriteUpscale", "hdsprites");
        CLASSES.put("SpritePack", "hdsprites");
    }

    /**
     * Test builds only (build.ps1 -TestJar, out\Altitude-1.1.9-REMASTERED-test.jar; never the release jar): with
     * -Dclient.offline=true the client skips Steam and goes straight to the stock offline login (bots and local
     * games), so test launches never sign in to an account. Off without the property.
     */
    static void enableTestBuild() {
        FIXES.put("test-offline", "TEST BUILD ONLY: -Dclient.offline=true starts in the stock offline mode without Steam");
        HOOKS.add(new Hook("test-offline", Jar.GAME, "em/altitude/game/AltitudeGame", "initializeDistributor()V",
                At.BEFORE_RETURN, 1, Mode.CALL, CLIENT + "OfflineTest", "afterDistributor", List.of("this")));
        HOOKS.add(new Hook("test-offline", Jar.GAME, "em/altitude/game/AltitudeGame", "updateCreateVaporClient(Z)Z",
                At.ENTRY, 1, Mode.RETURN_IF_TRUE, CLIENT + "OfflineTest", "createClient", List.of("this", "arg1")));
        CLASSES.put("OfflineTest", "test-offline");
        FIXES.put("test-harness", "TEST BUILD ONLY: unattended visual tests (auto skirmish, screenshots, sprite gallery; -Dclient.test.dir)");
        HOOKS.add(new Hook("test-harness", Jar.GAME, "em/altitude/game/AltitudeGame", "render()V",
                At.ENTRY, 1, Mode.CALL, CLIENT + "TestHarness", "frame", List.of("this")));
        HOOKS.add(new Hook("test-harness", Jar.GAME, "em/altitude/game/AltitudeGame", "render()V",
                At.BEFORE_RETURN, 1, Mode.CALL, CLIENT + "TestHarness", "endFrame", List.of("this")));
        CLASSES.put("TestHarness", "test-harness");
    }

    /** Non-class files the client jar adds (path prefix in the jar), and their fix. */
    static final Map<String, String> RESOURCES = Map.of(CLIENT + "fonts/", "fonts", CLIENT + "build/", "opt");

    /** The fix a client resource (jar path) belongs to, or null when it is not listed. */
    static String fixOfClientResource(String path) {
        for (Map.Entry<String, String> e : RESOURCES.entrySet()) if (path.startsWith(e.getKey()) && path.length() > e.getKey().length()) return e.getValue();
        return null;
    }

    /** The fix a client class file (internal name) belongs to, or null when it is not listed. */
    static String fixOfClientClass(String internalName) {
        if (!internalName.startsWith(CLIENT)) return null;
        String simple = internalName.substring(CLIENT.length());
        int nested = simple.indexOf('$');
        return CLASSES.get(nested < 0 ? simple : simple.substring(0, nested));
    }
}
