package client.build;

import client.build.Hooks.Hook;
import client.build.Hooks.Jar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.tree.ClassNode;

/**
 * Compares the patched jar with the stock jars and lists every class that differs, under the fix it belongs to. Fails
 * when anything differs that {@link Hooks} does not account for: a stock entry changed or missing, a patched class
 * whose fields or attributes changed, a changed method that is not a hook site (frames and max stack/locals are
 * ignored, they are recomputed), or a new class that is not assigned to a fix.
 * <p>
 * Usage: JarDiff patched.jar stock-game.jar stock-wrappers.jar report.txt
 */
public final class JarDiff {
    private JarDiff() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 4 && args[4].equals("--test")) Hooks.enableTestBuild();
        Map<String, Jars.Entry> out = Jars.read(Path.of(args[0])), game = Jars.read(Path.of(args[1])), wrappers = Jars.read(Path.of(args[2]));
        Map<String, Set<String>> hookedMethods = new TreeMap<>();
        Map<String, Jar> hookedJar = new TreeMap<>();
        Map<String, Set<String>> fixesOfOwner = new TreeMap<>();
        for (Hook h : Hooks.HOOKS) {
            hookedMethods.computeIfAbsent(h.owner(), k -> new TreeSet<>()).add(h.method());
            fixesOfOwner.computeIfAbsent(h.owner(), k -> new TreeSet<>()).add(h.fix());
            hookedJar.put(h.owner(), h.jar());
        }

        List<String> errors = new ArrayList<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (String fix : Hooks.FIXES.keySet()) lines.put(fix, new ArrayList<>());
        int identical = 0;
        for (String name : game.keySet()) if (!out.containsKey(name)) errors.add("missing stock entry: " + name);
        for (Jars.Entry e : out.values()) {
            String name = e.name();
            Jars.Entry stock = game.get(name);
            if (stock != null && Arrays.equals(stock.data(), e.data())) { identical++; continue; }
            if (name.endsWith("/") && stock == null) continue; // directory entries for new classes
            if (!name.endsWith(".class")) {
                String fix = stock == null ? Hooks.fixOfClientResource(name) : null;
                if (fix == null) errors.add("non-class entry differs or is not assigned to a fix: " + name);
                else lines.get(fix).add("  new      " + name);
                continue;
            }
            String cls = name.substring(0, name.length() - 6);
            if (stock == null && !wrappers.containsKey(name)) {
                String fix = Hooks.fixOfClientClass(cls);
                if (fix == null) errors.add("new class not assigned to a fix: " + cls);
                else lines.get(fix).add("  new      " + cls);
                continue;
            }
            Jar jar = stock != null ? Jar.GAME : Jar.WRAPPERS;
            if (hookedJar.get(cls) != jar) { errors.add("stock class differs without a hook: " + cls); continue; }
            ClassNode before = Jars.node((stock != null ? stock : wrappers.get(name)).data()), after = Jars.node(e.data());
            if (!Jars.headerText(before).equals(Jars.headerText(after))) errors.add("fields or class attributes changed: " + cls);
            Map<String, String> a = Jars.methodTexts(before), b = Jars.methodTexts(after);
            if (!a.keySet().equals(b.keySet())) errors.add("methods added or removed: " + cls);
            Set<String> changed = new TreeSet<>();
            for (String m : a.keySet()) if (!a.get(m).equals(b.get(m))) changed.add(m);
            if (!changed.equals(hookedMethods.get(cls))) errors.add("changed methods of " + cls + " are " + changed + ", hooks are " + hookedMethods.get(cls));
            String kind = jar == Jar.GAME ? "patched " : "shadowed";
            for (String fix : fixesOfOwner.get(cls)) {
                for (Hook h : Hooks.HOOKS) {
                    if (h.owner().equals(cls) && h.fix().equals(fix)) lines.get(fix).add("  " + kind + " " + cls + "." + h.method() + "  [" + h.at() + ", " + h.helperMethod() + "]");
                }
            }
        }
        for (String owner : hookedJar.keySet()) {
            if (!out.containsKey(owner + ".class") || game.containsKey(owner + ".class") && Arrays.equals(game.get(owner + ".class").data(), out.get(owner + ".class").data())) {
                errors.add("hooked class not patched: " + owner);
            }
        }

        StringBuilder report = new StringBuilder("Patched jar vs stock Altitude 1.1.9: " + identical + " entries byte-identical to stock; differences by fix:\n");
        for (Map.Entry<String, List<String>> fix : lines.entrySet()) {
            report.append(fix.getKey()).append(": ").append(Hooks.FIXES.get(fix.getKey())).append('\n');
            for (String line : fix.getValue()) report.append(line).append('\n');
        }
        if (!errors.isEmpty()) report.append("UNACCOUNTED DIFFERENCES:\n").append(String.join("\n", errors)).append('\n');
        Files.writeString(Path.of(args[3]), report);
        System.out.print(report);
        if (!errors.isEmpty()) System.exit(1);
    }
}
