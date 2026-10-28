package em.altitude.game.scene.player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ClientOptionsTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }

    public static void main(String[] args) throws Exception {
        ClientOptions.Option flag = new ClientOptions.Option("flag", ClientOptions.OFF_ON, "help");
        check("on".equals(ClientOptions.normalize(flag, " TRUE ")) && "off".equals(ClientOptions.normalize(flag, "false")), "true/false map to on/off");
        check(ClientOptions.normalize(flag, "maybe") == null && ClientOptions.normalize(flag, null) == null, "unknown values are rejected");
        ClientOptions.Option mode = new ClientOptions.Option("mode", List.of("normal", "resizable"), "help");
        check("resizable".equals(ClientOptions.normalize(mode, "Resizable")) && ClientOptions.normalize(mode, "true") == null, "enumerated values");

        Path dir = Files.createTempDirectory("client-options");
        Path file = dir.resolve("config").resolve("client.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "other=kept\n");
        ClientOptions.save(file, Map.of("flag", "on"));
        Properties p = ClientOptions.load(file);
        check("on".equals(p.getProperty("flag")) && "kept".equals(p.getProperty("other")), "save keeps other keys");
        ClientOptions.save(file, Map.of("perfhud", "on", "scorehud", "on"));
        ClientOptions.save(file, Map.of("perfhud", "off"));
        p = ClientOptions.load(file);
        check(!p.containsKey("perfhud") && !p.containsKey("scorehud") && "on".equals(p.getProperty("flag")), "defaults are not saved, so new defaults apply");
        check(ClientOptions.load(dir.resolve("missing")).isEmpty(), "a missing file loads empty");
        for (Path f : List.of(file, file.getParent(), dir)) Files.deleteIfExists(f);
        for (ClientOptions.Option o : ClientOptions.ALL) check(o.values().contains(o.defaultValue()) && !o.help().isEmpty(), "option " + o.name());
        System.out.println("PASS: client options parse, save (defaults left out) and keep other keys.");
    }
}
