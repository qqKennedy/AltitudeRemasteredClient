package client.build;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Loads and links every class of the patched jar that is not byte-identical to the stock game jar, without running
 * any of its code. Run it with -Xverify:all and the launcher's class path (patched jar first): linking runs the JVM's
 * own bytecode verifier, and reflection on the declared members forces the link.
 * <p>
 * Usage: java -Xverify:all -cp patched.jar;libs... client.build.LoadCheck patched.jar stock-game.jar
 */
public final class LoadCheck {
    private LoadCheck() {}

    public static void main(String[] args) throws Exception {
        Map<String, Jars.Entry> out = Jars.read(Path.of(args[0])), game = Jars.read(Path.of(args[1]));
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        int n = 0;
        for (Jars.Entry e : out.values()) {
            if (!e.name().endsWith(".class")) continue;
            Jars.Entry stock = game.get(e.name());
            if (stock != null && Arrays.equals(stock.data(), e.data())) continue;
            String name = e.name().substring(0, e.name().length() - 6).replace('/', '.');
            Class<?> c = Class.forName(name, false, loader);
            Path from = Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!from.toAbsolutePath().equals(Path.of(args[0]).toAbsolutePath())) throw new IllegalStateException(name + " loaded from " + from + ", not the patched jar");
            c.getDeclaredMethods();
            c.getDeclaredFields();
            n++;
        }
        System.out.println("Loaded and verified " + n + " patched and client classes with Java " + System.getProperty("java.version"));
    }
}
