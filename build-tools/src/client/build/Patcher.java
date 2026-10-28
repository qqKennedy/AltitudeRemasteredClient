package client.build;

import client.build.Hooks.Hook;
import client.build.Hooks.Jar;
import client.build.Hooks.Mode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * Writes the patched game jar: the stock game jar with the hooks from {@link Hooks} applied, the hooked EmWrappers
 * classes added (they shadow the library copies), and the compiled client classes. Every other entry is copied
 * unchanged. Fails when a stock jar is not the expected one, a hook site is missing or found a different number of
 * times, a helper method does not exist, a client class is not assigned to a fix, or a written class does not verify.
 * <p>
 * Usage: Patcher stock-game.jar stock-wrappers.jar client-classes-dir library-classpath out.jar
 */
public final class Patcher {
    private Patcher() {}

    /** Time stamp of every entry the patcher writes, so the output only changes when its content does. */
    static final long NEW_ENTRY_TIME = 1767225600000L; // 2026-01-01T00:00:00Z

    public static void main(String[] args) throws Exception {
        Path gameJar = Path.of(args[0]), wrappersJar = Path.of(args[1]), classesDir = Path.of(args[2]), out = Path.of(args[4]);
        if (args.length > 5 && args[5].equals("--test")) Hooks.enableTestBuild();
        Jars.requireSha256(gameJar, Hooks.GAME_SHA256);
        Jars.requireSha256(wrappersJar, Hooks.WRAPPERS_SHA256);
        Map<String, Jars.Entry> game = Jars.read(gameJar), wrappers = Jars.read(wrappersJar);
        Map<String, byte[]> client = Jars.classes(classesDir);

        List<URL> path = new ArrayList<>(List.of(classesDir.toUri().toURL(), gameJar.toUri().toURL(), wrappersJar.toUri().toURL()));
        for (String lib : args[3].split(File.pathSeparator)) if (!lib.isEmpty()) path.add(Path.of(lib).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(path.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (String name : client.keySet()) {
                if (Hooks.fixOfClientClass(name) == null) throw new IllegalStateException("Client class not assigned to a fix in Hooks.CLASSES: " + name);
                verify(name, client.get(name), loader);
            }
            Map<String, byte[]> resources = Jars.resources(classesDir);
            for (String name : resources.keySet()) {
                if (Hooks.fixOfClientResource(name) == null) throw new IllegalStateException("Client resource not assigned to a fix in Hooks.RESOURCES: " + name);
            }
            Map<String, byte[]> patched = patch(game, wrappers, client, loader);
            write(out, game, patched, client, resources);
            System.out.println("Patched " + patched.size() + " stock classes, added " + client.size() + " client classes and " + resources.size() + " resources: " + out);
        }
    }

    /** Applies every hook; returns the patched classes by internal name. */
    static Map<String, byte[]> patch(Map<String, Jars.Entry> game, Map<String, Jars.Entry> wrappers, Map<String, byte[]> client, ClassLoader loader) {
        Map<String, List<Hook>> byOwner = new LinkedHashMap<>();
        for (Hook h : Hooks.HOOKS) byOwner.computeIfAbsent(h.owner(), k -> new ArrayList<>()).add(h);
        Map<String, byte[]> patched = new TreeMap<>();
        for (Map.Entry<String, List<Hook>> e : byOwner.entrySet()) {
            String owner = e.getKey();
            Set<Jar> jars = new HashSet<>();
            for (Hook h : e.getValue()) jars.add(h.jar());
            if (jars.size() != 1) throw new IllegalStateException("Hooks on " + owner + " disagree on its jar");
            Jars.Entry stock = (jars.contains(Jar.GAME) ? game : wrappers).get(owner + ".class");
            if (stock == null) throw new IllegalStateException("Hook target class not found: " + owner);
            if (jars.contains(Jar.WRAPPERS) && game.containsKey(owner + ".class")) throw new IllegalStateException("Wrapper class also in the game jar: " + owner);
            ClassNode node = new ClassNode();
            new ClassReader(stock.data()).accept(node, ClassReader.SKIP_FRAMES);
            // Several hooks at one site run in the order listed: each entry hook goes after the previous one's code.
            Map<MethodNode, AbstractInsnNode> entryEnd = new java.util.HashMap<>();
            for (Hook h : e.getValue()) apply(node, h, client, entryEnd);
            ClassWriter writer = new HierarchyClassWriter(loader);
            node.accept(writer);
            byte[] bytes = writer.toByteArray();
            verify(owner, bytes, loader);
            patched.put(owner, bytes);
        }
        return patched;
    }

    static void apply(ClassNode node, Hook h, Map<String, byte[]> client, Map<MethodNode, AbstractInsnNode> entryEnd) {
        MethodNode method = null;
        for (MethodNode m : node.methods) if ((m.name + m.desc).equals(h.method())) method = m;
        if (method == null) throw new IllegalStateException("Hook site not found: " + h.owner() + "." + h.method());
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] params = Type.getArgumentTypes(method.desc);
        Type returns = Type.getReturnType(method.desc);

        InsnList call = new InsnList();
        List<Type> types = new ArrayList<>();
        for (String arg : h.args()) {
            if (arg.equals("this") || arg.startsWith("this.")) {
                if (isStatic) throw new IllegalStateException("No 'this' in static " + h.owner() + "." + h.method());
                call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if (arg.equals("this")) {
                    types.add(Type.getObjectType(node.name));
                } else {
                    FieldNode field = null;
                    for (FieldNode f : node.fields) if (f.name.equals(arg.substring(5)) && (f.access & Opcodes.ACC_STATIC) == 0) field = f;
                    if (field == null) throw new IllegalStateException("Field not found: " + h.owner() + "." + arg);
                    call.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field.name, field.desc));
                    types.add(Type.getType(field.desc));
                }
            } else if (arg.startsWith("arg")) {
                int n = Integer.parseInt(arg.substring(3));
                int slot = isStatic ? 0 : 1;
                for (int i = 0; i < n - 1; i++) slot += params[i].getSize();
                call.add(new VarInsnNode(params[n - 1].getOpcode(Opcodes.ILOAD), slot));
                types.add(params[n - 1]);
            } else {
                throw new IllegalStateException("Bad hook argument: " + arg);
            }
        }
        Type helperReturns = switch (h.mode()) {
            case CALL -> Type.VOID_TYPE;
            case RETURN_IF_TRUE -> Type.BOOLEAN_TYPE;
            case RETURN_IF_NONNULL -> returns;
        };
        String desc = Type.getMethodDescriptor(helperReturns, types.toArray(Type[]::new));
        requireHelper(client, h, desc);
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, h.helper(), h.helperMethod(), desc, false));
        LabelNode resume = new LabelNode();
        switch (h.mode()) {
            case CALL -> {}
            case RETURN_IF_TRUE -> {
                call.add(new JumpInsnNode(Opcodes.IFEQ, resume));
                switch (returns.getSort()) { // return nothing, false/0 or null
                    case Type.VOID -> {}
                    case Type.OBJECT, Type.ARRAY -> call.add(new InsnNode(Opcodes.ACONST_NULL));
                    case Type.LONG -> call.add(new InsnNode(Opcodes.LCONST_0));
                    case Type.FLOAT -> call.add(new InsnNode(Opcodes.FCONST_0));
                    case Type.DOUBLE -> call.add(new InsnNode(Opcodes.DCONST_0));
                    default -> call.add(new InsnNode(Opcodes.ICONST_0));
                }
                call.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
                call.add(resume);
            }
            case RETURN_IF_NONNULL -> {
                if (returns.getSort() != Type.OBJECT && returns.getSort() != Type.ARRAY) throw new IllegalStateException("RETURN_IF_NONNULL needs a reference return: " + h.method());
                call.add(new InsnNode(Opcodes.DUP));
                call.add(new JumpInsnNode(Opcodes.IFNULL, resume));
                call.add(new InsnNode(Opcodes.ARETURN));
                call.add(resume);
                call.add(new InsnNode(Opcodes.POP));
            }
        }

        int sites = 0;
        if (h.at() == Hooks.At.ENTRY) {
            AbstractInsnNode end = call.getLast(), previous = entryEnd.get(method);
            if (previous == null) method.instructions.insert(call);
            else method.instructions.insert(previous, call);
            entryEnd.put(method, end);
            sites = 1;
        } else {
            if (h.mode() != Mode.CALL) throw new IllegalStateException("BEFORE_RETURN hooks must be CALL: " + h.method());
            int returnOpcode = returns.getOpcode(Opcodes.IRETURN);
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != returnOpcode) continue;
                method.instructions.insertBefore(insn, copy(call));
                sites++;
            }
        }
        if (sites != h.sites()) throw new IllegalStateException("Hook " + h.owner() + "." + h.method() + ": expected " + h.sites() + " site(s), found " + sites);
    }

    private static InsnList copy(InsnList list) {
        Map<LabelNode, LabelNode> labels = new java.util.HashMap<>();
        for (AbstractInsnNode n : list) if (n instanceof LabelNode l) labels.put(l, new LabelNode());
        InsnList copy = new InsnList();
        for (AbstractInsnNode n : list) copy.add(n.clone(labels));
        return copy;
    }

    /** The helper must be a public static method of a client class with exactly this descriptor. */
    static void requireHelper(Map<String, byte[]> client, Hook h, String desc) {
        byte[] bytes = client.get(h.helper());
        if (bytes != null) {
            for (MethodNode m : Jars.node(bytes).methods) {
                if (m.name.equals(h.helperMethod()) && m.desc.equals(desc) && (m.access & Opcodes.ACC_STATIC) != 0 && (m.access & Opcodes.ACC_PUBLIC) != 0) return;
            }
        }
        throw new IllegalStateException("Helper not found: public static " + h.helper() + "." + h.helperMethod() + desc);
    }

    /** ASM's bytecode verifier against the full class path; any finding fails the build. */
    static void verify(String name, byte[] bytes, ClassLoader loader) {
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), loader, false, new PrintWriter(problems));
        if (!problems.toString().isEmpty()) throw new IllegalStateException("Verification failed for " + name + ":\n" + problems);
    }

    static void write(Path out, Map<String, Jars.Entry> game, Map<String, byte[]> patched, Map<String, byte[]> client, Map<String, byte[]> resources) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            Set<String> written = new HashSet<>();
            for (Jars.Entry e : game.values()) {
                String cls = e.name().endsWith(".class") ? e.name().substring(0, e.name().length() - 6) : null;
                byte[] data = cls != null && patched.containsKey(cls) ? patched.get(cls) : e.data();
                put(zip, e.name(), e.time(), data);
                written.add(e.name());
            }
            for (Map<String, byte[]> classes : List.of(patched, client)) {
                for (Map.Entry<String, byte[]> c : classes.entrySet()) {
                    String name = c.getKey() + ".class";
                    if (written.contains(name)) continue;
                    if (client == classes && patched.containsKey(c.getKey())) throw new IllegalStateException("Client class replaces a stock class: " + name);
                    addDirectories(zip, name, written);
                    put(zip, name, NEW_ENTRY_TIME, c.getValue());
                    written.add(name);
                }
            }
            for (Map.Entry<String, byte[]> r : resources.entrySet()) {
                if (written.contains(r.getKey())) throw new IllegalStateException("Client resource replaces a stock entry: " + r.getKey());
                addDirectories(zip, r.getKey(), written);
                put(zip, r.getKey(), NEW_ENTRY_TIME, r.getValue());
                written.add(r.getKey());
            }
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void addDirectories(ZipOutputStream zip, String name, Set<String> written) throws IOException {
        for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
            String dir = name.substring(0, i + 1);
            if (written.add(dir)) put(zip, dir, NEW_ENTRY_TIME, new byte[0]);
        }
    }

    private static void put(ZipOutputStream zip, String name, long time, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(time);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    /** Frame computation needs common super classes; they are read from class files, never loaded or initialized. */
    static final class HierarchyClassWriter extends ClassWriter {
        private final ClassLoader loader;

        HierarchyClassWriter(ClassLoader loader) {
            super(COMPUTE_FRAMES);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            if (isInterface(a) || isInterface(b)) return "java/lang/Object";
            List<String> ancestorsOfA = ancestors(a);
            for (String t : ancestors(b)) if (ancestorsOfA.contains(t)) return t;
            return "java/lang/Object";
        }

        private List<String> ancestors(String name) {
            List<String> chain = new ArrayList<>();
            for (String t = name; t != null; t = reader(t).getSuperName()) chain.add(t);
            return chain;
        }

        private boolean isInterface(String name) {
            return (reader(name).getAccess() & Opcodes.ACC_INTERFACE) != 0;
        }

        private ClassReader reader(String name) {
            try (InputStream in = loader.getResourceAsStream(name + ".class")) {
                if (in == null) throw new IllegalStateException("Class not on the build class path: " + name);
                return new ClassReader(in);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
