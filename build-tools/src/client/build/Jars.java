package client.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Jar reading, hashing and the textual class comparison shared by the build tools. */
final class Jars {
    private Jars() {}

    record Entry(String name, long time, byte[] data) {}

    /** All entries of a jar, in file order. */
    static Map<String, Entry> read(Path jar) throws IOException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) entries.put(e.getName(), new Entry(e.getName(), e.getTime(), in.readAllBytes()));
        }
        return entries;
    }

    /** All .class files under a directory, by internal name, sorted. */
    static Map<String, byte[]> classes(Path dir) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = dir.relativize(f).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(f));
            }
        }
        return classes;
    }

    /** All non-class files under a directory, by jar path, sorted. */
    static Map<String, byte[]> resources(Path dir) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) all.filter(p -> Files.isRegularFile(p) && !p.toString().endsWith(".class"))::iterator) {
                files.put(dir.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
            }
        }
        return files;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static void requireSha256(Path file, String expected) throws Exception {
        String actual = sha256(file);
        if (!actual.equals(expected)) throw new IllegalStateException(file + " is not the stock jar: SHA-256 " + actual + ", expected " + expected);
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    /** A class without its methods, as text: name, flags, super types, fields, attributes. */
    static String headerText(ClassNode node) {
        ClassNode copy = new ClassNode();
        node.accept(copy);
        copy.methods.clear();
        StringWriter out = new StringWriter();
        copy.accept(new TraceClassVisitor(new PrintWriter(out)));
        return out.toString();
    }

    /** A method's code as text, without stack-map frames and max stack/locals (recomputed when a class is patched). */
    static String methodText(MethodNode method) {
        Textifier text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        StringWriter out = new StringWriter();
        text.print(new PrintWriter(out));
        StringBuilder kept = new StringBuilder();
        for (String line : out.toString().split("\n")) {
            String t = line.trim();
            if (t.startsWith("FRAME ") || t.startsWith("MAXSTACK") || t.startsWith("MAXLOCALS")) continue;
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    /** Methods by name + descriptor, as text. */
    static Map<String, String> methodTexts(ClassNode node) {
        Map<String, String> methods = new TreeMap<>();
        for (MethodNode m : node.methods) methods.put(m.name + m.desc, methodText(m));
        return methods;
    }
}
