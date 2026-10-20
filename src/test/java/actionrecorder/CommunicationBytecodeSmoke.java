package actionrecorder;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.expr.ExprEditor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Load transformed CM classes so the JVM verifies bytecode, without game initialization. */
public final class CommunicationBytecodeSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--if-installed".equals(args[0])
                && CommunicationBytecodeSmoke.class.getClassLoader()
                .getResource("communicationmod/CommandExecutor.class") == null) {
            System.out.println("SKIP CM bytecode verification: optional CommunicationMod jar is not installed");
            return;
        }
        ClassPool pool = new ClassPool(true);
        Map<String, CtClass> targets = new LinkedHashMap<>();
        int patches = 0;
        try (Stream<Path> paths = Files.walk(Paths.get("target/classes/actionrecorder/patches"))) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = Paths.get("target/classes").relativize(path).toString()
                        .replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                Class<?> patchClass = Class.forName(name, false, CommunicationBytecodeSmoke.class.getClassLoader());
                SpirePatch patch = patchClass.getAnnotation(SpirePatch.class);
                if (patch == null || !patch.cls().startsWith("communicationmod.")) continue;
                for (Method method : patchClass.getDeclaredMethods()) {
                    if (method.getAnnotation(SpireInstrumentPatch.class) == null) continue;
                    CtClass target = pool.get(patch.cls());
                    target.getDeclaredMethod(patch.method()).instrument((ExprEditor) method.invoke(null));
                    targets.put(patch.cls(), target);
                    patches++;
                }
            }
        }
        final Map<String, byte[]> transformed = new LinkedHashMap<>();
        for (Map.Entry<String, CtClass> target : targets.entrySet())
            transformed.put(target.getKey(), target.getValue().toBytecode());
        ClassLoader verifier = new ClassLoader(CommunicationBytecodeSmoke.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                byte[] bytes = transformed.get(name);
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) result = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(result);
                return result;
            }
        };
        if (!transformed.containsKey("communicationmod.CommandExecutor"))
            throw new AssertionError("Missing CommandExecutor verification");
        for (String name : transformed.keySet()) {
            // Reflection forces method verification; do not initialize static game state.
            Class<?> verified = Class.forName(name, false, verifier);
            verified.getDeclaredMethods();
            verified.getDeclaredConstructors();
            System.out.println("PASS JVM verification " + name);
        }
        System.out.println("Verified " + patches + " CM instrument patches in " + transformed.size()
                + " transformed classes (not a live-game test)");
    }
}
