package actionrecorder;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Compile every instrument replacement against the installed original jars. */
public final class PatchInstrumentationSmoke {
    public static void main(String[] args) throws Exception {
        ClassPool pool = ClassPool.getDefault();
        int count = 0;
        try (Stream<Path> paths = Files.walk(Paths.get("target/classes/actionrecorder/patches"))) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = Paths.get("target/classes").relativize(path).toString()
                        .replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                Class<?> patchClass = Class.forName(name, false, PatchInstrumentationSmoke.class.getClassLoader());
                SpirePatch patch = patchClass.getAnnotation(SpirePatch.class);
                if (patch == null) continue;
                for (Method method : patchClass.getDeclaredMethods()) {
                    if (method.getAnnotation(SpireInstrumentPatch.class) == null) continue;
                    String targetName = patch.cls().isEmpty() ? patch.clz().getName() : patch.cls();
                    CtClass target = pool.get(targetName);
                    CtMethod original = target.getDeclaredMethod(patch.method());
                    original.instrument((ExprEditor) method.invoke(null));
                    System.out.println("PASS " + name + " -> " + targetName + "." + patch.method());
                    count++;
                }
            }
        }
        if (count == 0) throw new IllegalStateException("No instrument patches found");
        System.out.println("Validated " + count + " instrument patches (not a live-game test)");
    }
}
