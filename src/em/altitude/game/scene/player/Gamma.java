package em.altitude.game.scene.player;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /opt gamma image (default): the game's brightness slider applied to the game's picture. Stock
 * GLFrame.setDisplayConfiguration turns the slider value s into an exponent g (a quartic, 1 at the default 0.5) and has
 * LWJGL set the monitor's gamma ramp to pow(x, g), but only in fullscreen: in a window the slider does nothing, and in
 * fullscreen it changes the whole monitor's ramp. Here the same pow(x, g) is applied to the finished frame by a
 * one-line shader (copy of the back buffer, drawn back through it) before the buffers are swapped, so it works in every
 * window mode and leaves the monitor alone. At g = 1 nothing is drawn. /opt gamma stock keeps the stock ramp. Any GL
 * problem turns it off for the session (the picture is then as at g = 1).
 */
public final class Gamma {
    private Gamma() {}

    private static final Logger LOG = LoggerFactory.getLogger(Gamma.class);
    private static volatile float exponent = 1f;
    private static Object context;
    private static int program, texture, texWidth, texHeight, uExponent;
    private static boolean failed, logged;

    /** The stock curve from the slider value to the gamma exponent (GLFrame.setDisplayConfiguration). */
    static float exponent(float s) {
        double v = s;
        return (float) (3.0 - 12.18968379 * v + 27.66296444 * v * v - 26.9911067 * v * v * v + 8.84782609 * v * v * v * v);
    }

    /** Hook: start of GLFrame.setDisplayConfiguration(F); true when applied here and the stock ramp is skipped. */
    public static boolean set(float slider) {
        if (!"image".equals(ClientOptions.get("gamma"))) {
            exponent = 1f;
            return false;
        }
        exponent = exponent(slider);
        LOG.info("Gamma: brightness {} -> exponent {} on the game image", slider, exponent);
        return true;
    }

    /** Hook: end of AltitudeGame.render, before the buffers are swapped. */
    public static void endFrame() {
        float g = exponent;
        if (failed || Math.abs(g - 1f) < 1e-3f || !"image".equals(ClientOptions.get("gamma"))) return;
        int w = Display.getWidth(), h = Display.getHeight();
        if (w <= 0 || h <= 0) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            setUp(w, h);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            for (int cap : new int[] {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_ALPHA_TEST, GL11.GL_LIGHTING, GL11.GL_STENCIL_TEST, GL11.GL_CULL_FACE}) GL11.glDisable(cap);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glViewport(0, 0, w, h);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, 1, 0, 1, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL20.glUseProgram(program);
            GL20.glUniform1f(uExponent, g);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, 0); GL11.glVertex2f(0, 0);
            GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, 0);
            GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
            GL11.glTexCoord2f(0, 1); GL11.glVertex2f(0, 1);
            GL11.glEnd();
            if (!logged) {
                logged = true;
                LOG.info("Gamma: exponent {} applied to the {}x{} image (GL error {})", g, w, h, GL11.glGetError());
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            LOG.warn("Gamma: turned off (stock picture): {}", e.toString());
        } finally {
            try { GL20.glUseProgram(0); } catch (RuntimeException | LinkageError e) { /* no GL 2.0 */ }
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            HudDraw.resetTextureState();
        }
    }

    /** The shader and a texture of the window's size, made again for a new GL context or a new size. */
    private static void setUp(int w, int h) {
        Object ctx = GLContext.getCapabilities();
        if (ctx != context) {
            context = ctx;
            program = 0;
            texture = 0;
        }
        if (program == 0) {
            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, "uniform sampler2D image; uniform float exponent;"
                    + " void main() { vec3 c = texture2D(image, gl_TexCoord[0].st).rgb; gl_FragColor = vec4(pow(c, vec3(exponent)), 1.0); }");
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException("shader: " + GL20.glGetShaderInfoLog(fs, 1000));
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException("program: " + GL20.glGetProgramInfoLog(p, 1000));
            GL20.glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "image"), 0);
            uExponent = GL20.glGetUniformLocation(p, "exponent");
            program = p;
        }
        if (texture == 0 || texWidth != w || texHeight != h) {
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, w, h, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            texWidth = w;
            texHeight = h;
        }
    }
}
