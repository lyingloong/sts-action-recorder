package actionrecorder;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.patcher.PrefixPatchInfo;
import com.evacipated.cardcrawl.modthespire.patcher.PostfixPatchInfo;
import com.evacipated.cardcrawl.modthespire.patcher.PatchingException;
import com.megacrit.cardcrawl.rewards.RewardItem;
import actionrecorder.patches.DecisionPatches;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtMethod;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Validate parameter binding through the installed ModTheSpire patcher. */
public final class PatchBindingSmoke {
    public static void main(String[] args) throws Exception {
        ClassPool pool = ClassPool.getDefault();
        int count = 0;
        try (Stream<Path> paths = Files.walk(Paths.get("target/classes/actionrecorder/patches"))) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = Paths.get("target/classes").relativize(path).toString()
                        .replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                Class<?> patchClass = Class.forName(name, false, PatchBindingSmoke.class.getClassLoader());
                SpirePatch patch = patchClass.getAnnotation(SpirePatch.class);
                if (patch == null) continue;
                for (Method method : patchClass.getDeclaredMethods()) {
                    boolean prefix = method.getAnnotation(SpirePrefixPatch.class) != null;
                    boolean postfix = method.getAnnotation(SpirePostfixPatch.class) != null;
                    if (!prefix && !postfix) continue;
                    String targetName = patch.cls().isEmpty() ? patch.clz().getName() : patch.cls();
                    CtClass target = pool.get(targetName);
                    CtBehavior original = resolve(pool, target, patch);
                    CtClass[] signature = new CtClass[method.getParameterTypes().length];
                    for (int i = 0; i < signature.length; i++) {
                        signature[i] = pool.get(method.getParameterTypes()[i].getName());
                    }
                    CtMethod patchMethod = pool.get(name).getDeclaredMethod(method.getName(), signature);
                    if (prefix) new PrefixPatchInfo(original, patchMethod).doPatch();
                    else new PostfixPatchInfo(original, patchMethod).doPatch();
                    System.out.println("PASS " + name + "." + method.getName());
                    count++;
                }
            }
        }
        if (count == 0) throw new IllegalStateException("No parameter patches found");
        // Keep the actual startup failure as a negative regression test.
        try {
            new PostfixPatchInfo(pool.get(RewardItem.class.getName()).getDeclaredMethod("claimReward"),
                    pool.get(BrokenLegacyReward.class.getName()).getDeclaredMethod("postfix")).doPatch();
            throw new AssertionError("Legacy void postfix with second result parameter must be rejected");
        } catch (PatchingException expected) {
            if (!expected.getMessage().contains("Cannot determine name")) throw expected;
            System.out.println("PASS reproduced and rejected the old RewardClaim binding");
        }
        if (!DecisionPatches.RewardClaim.postfix(true, null)
                || DecisionPatches.RewardClaim.postfix(false, null)) {
            throw new AssertionError("Reward postfix changed the original result");
        }
        System.out.println("Validated " + count + " prefix/postfix bindings (not a live-game test)");
    }

    public static final class BrokenLegacyReward {
        public static void postfix(RewardItem __instance, boolean __result) { }
    }

    private static CtBehavior resolve(ClassPool pool, CtClass target, SpirePatch patch) throws Exception {
        Class<?>[] types = patch.paramtypez();
        String[] names = patch.paramtypes();
        boolean specified = !(types.length == 1 && types[0] == void.class);
        CtClass[] signature = new CtClass[specified ? types.length : 0];
        if (specified) {
            for (int i = 0; i < types.length; i++) signature[i] = pool.get(types[i].getName());
        } else if (!(names.length == 1 && "DEFAULT".equals(names[0])) && names.length > 0) {
            specified = true;
            signature = new CtClass[names.length];
            for (int i = 0; i < names.length; i++) signature[i] = pool.get(names[i]);
        }
        if (SpirePatch.CONSTRUCTOR.equals(patch.method())) return target.getDeclaredConstructor(signature);
        if (specified) return target.getDeclaredMethod(patch.method(), signature);
        CtMethod[] matches = target.getDeclaredMethods(patch.method());
        if (matches.length != 1) throw new IllegalStateException("Ambiguous target " + target.getName() + "." + patch.method());
        return matches[0];
    }
}
