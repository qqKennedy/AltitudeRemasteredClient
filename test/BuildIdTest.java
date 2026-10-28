package em.altitude.game.scene.player;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class BuildIdTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) {
        check("abc1234-20261027".equals(ClientOptions.readBuildId(new ByteArrayInputStream(" abc1234-20261027\r\n".getBytes(StandardCharsets.UTF_8)))), "the ID is read trimmed");
        check("unknown".equals(ClientOptions.readBuildId(null)) && "unknown".equals(ClientOptions.readBuildId(new ByteArrayInputStream(new byte[0]))), "no or an empty resource is unknown");
        // build.ps1 writes build/id.txt next to the client classes before the tests run.
        check(ClientOptions.BUILD_ID.matches("[0-9a-f]{7,}(-dirty)?-\\d{8}"), "build.ps1 wrote the ID: " + ClientOptions.BUILD_ID);
        check(ClientOptions.summary().get(0).equals("Altitude REMASTERED build " + ClientOptions.BUILD_ID), "/opt prints the build first: " + ClientOptions.summary().get(0));
        System.out.println("PASS: build ID " + ClientOptions.BUILD_ID + " read from the jar and shown first by /opt.");
    }
}
