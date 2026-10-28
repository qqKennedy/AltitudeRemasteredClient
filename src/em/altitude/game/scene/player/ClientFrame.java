package em.altitude.game.scene.player;

import em.altitude.game.AltitudeGame;

/** The per-frame hook shared by the fixes: called at the start of AltitudeGame.render, menus included. */
public final class ClientFrame {
    private ClientFrame() {}

    /** Hook: start of AltitudeGame.render. */
    public static void frame(AltitudeGame game) {
        ClientOptions.pollFile();
        FastImagePack.menuFrame(game);
        FrameTarget.frame(game);
        FramePacer.setEnabled(ClientOptions.on("framepacing"));
        HudFontSharpen.setStock(!ClientOptions.on("sharpfonts"));
        WindowMode.poll(game);
        SpriteUpscale.frame(game);
        Screenshots.frame(game);
        CursorLock.frame(game);
        try {
            PerfMonitor.frame(game);
        } catch (RuntimeException | LinkageError e) {
            // Measurement only: never break the frame.
        }
    }
}
