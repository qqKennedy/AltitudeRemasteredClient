package em.altitude.game.scene.player;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where /opt hdsprites finds its replacements. The shipped form is a pack file, Altitude-1.1.9-hdsprites.zip, next to
 * the client jar (or -Dclient.spritepack=FILE): a zip of PNGs named by stock texture key under sprites/, and
 * manifest.txt:
 * <pre>
 * altitude-hdsprites 1
 * game 1.1.9
 * scale 3
 * pipeline ...                                       (free text: how the art was made)
 * texture KEY SHA256 PACK#INDEX[,PACK#INDEX...]      (one per PNG; where the stock texture is in the game's packs)
 * </pre>
 * A pack with another format or game version is ignored. The release jar accepts only the official pack: its manifest
 * must have the SHA-256 below (written by sprite-tools/pack.ps1), and each PNG is checked against its SHA-256 in the
 * manifest when read. So no other art can replace the game's textures, which keeps hdsprites from being a route to
 * see-through or high-contrast sprites. Only the test build (which has TestHarness) accepts other packs, and loose
 * PNGs in config/client-sprites (or -Dclient.spritedir=DIR) that override the pack's, for trying new art.
 */
final class SpritePack {
    private SpritePack() {}

    static final String FILE_NAME = "Altitude-1.1.9-hdsprites.zip", FORMAT = "altitude-hdsprites 1", GAME = "game 1.1.9";
    /** SHA-256 of the official pack's manifest.txt; sprite-tools/pack.ps1 updates this line. */
    static final String MANIFEST_SHA256 = "aac08d771bbb1cd0d6b9459db003d771c3c8a8f04717994a35314605ca0a2bfa";
    /** The test build (TestHarness is only in that jar) also takes other packs and loose PNGs. */
    static final boolean TEST_BUILD = exists("em.altitude.game.scene.player.TestHarness");

    private static boolean exists(String name) {
        try {
            Class.forName(name, false, SpritePack.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** One replacement PNG. The stamp identifies its content, for the cache file name. */
    interface Source {
        String stamp();
        byte[] read() throws IOException;
    }

    /** A loose PNG: its stamp is its date and size, so replacing the file (even with an older one) changes it. */
    record FileSource(File file) implements Source {
        public String stamp() { return Long.toHexString(file.lastModified()) + "-" + Long.toHexString(file.length()); }
        public byte[] read() throws IOException { return Files.readAllBytes(file.toPath()); }
    }

    /** A PNG in the pack, read only if it still matches the manifest's SHA-256. */
    record PackEntry(ZipFile zip, String name, String sha256) implements Source {
        public String stamp() { return sha256.substring(0, 16); }
        public byte[] read() throws IOException {
            ZipEntry e = zip.getEntry(name);
            if (e == null) throw new IOException(name + " is missing from the pack");
            byte[] bytes;
            try (InputStream in = zip.getInputStream(e)) { bytes = in.readAllBytes(); }
            if (!SpritePack.sha256(bytes).equals(sha256)) throw new IOException(name + " does not match its checksum in the manifest");
            return bytes;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The pack file: -Dclient.spritepack, else next to the jar (or class folder) this class was loaded from. */
    static File packFile() {
        String p = System.getProperty("client.spritepack");
        if (p != null) return new File(p);
        try {
            File code = new File(SpritePack.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return new File(code.isFile() ? code.getParentFile() : code, FILE_NAME);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** A pack's replacements by texture key, and the scale of its art. */
    record Contents(Map<String, Source> textures, int scale) {}

    /**
     * Opens a pack and reads its manifest. Throws when it is not a pack for this game, or when manifestSha256 is given
     * and the manifest does not have it.
     */
    static Contents readPack(ZipFile zip, String manifestSha256) throws IOException {
        ZipEntry m = zip.getEntry("manifest.txt");
        if (m == null) throw new IOException("no manifest.txt");
        byte[] manifest;
        try (InputStream in = zip.getInputStream(m)) { manifest = in.readAllBytes(); }
        if (manifestSha256 != null && !sha256(manifest).equals(manifestSha256)) throw new IOException("not the official HD sprite pack for this client");
        Map<String, Source> out = new HashMap<>();
        boolean game = false;
        int scale = 4;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(manifest), StandardCharsets.UTF_8))) {
            String first = r.readLine();
            if (!FORMAT.equals(first)) throw new IOException("unknown pack format: " + first);
            for (String line; (line = r.readLine()) != null; ) {
                if (line.equals(GAME)) game = true;
                String[] w = line.split(" ");
                if (w[0].equals("scale") && w.length == 2 && w[1].matches("[2-4]")) scale = Integer.parseInt(w[1]);
                if (w[0].equals("texture") && w.length >= 3 && w[1].matches("[0-9a-f]{8}-[0-9a-f]{8}") && w[2].matches("[0-9a-f]{64}"))
                    out.put(w[1], new PackEntry(zip, "sprites/" + w[1] + ".png", w[2]));
            }
        }
        if (!game) throw new IOException("not a pack for Altitude 1.1.9");
        return new Contents(out, scale);
    }

    /** Loose PNGs named by key in a folder. */
    static Map<String, Source> readDir(File dir) {
        Map<String, Source> out = new HashMap<>();
        File[] list = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (list != null) for (File f : list) out.put(f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT), new FileSource(f));
        return out;
    }
}
