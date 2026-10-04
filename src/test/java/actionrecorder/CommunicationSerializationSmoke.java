package actionrecorder;

import actionrecorder.patches.CommunicationStatePatches;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Execute the actual serializer replacement with CM's shaded Gson, no game boot. */
public final class CommunicationSerializationSmoke {
    public static void main(String[] args) throws Exception {
        CtClass probe = ClassPool.getDefault().makeClass("actionrecorder.testgenerated.NullSerializerProbe");
        probe.addMethod(CtNewMethod.make("public static String legacy(Object value) { return new com.autoplay.gson.Gson().toJson(value); }", probe));
        probe.addMethod(CtNewMethod.make("public static String fixed(Object value) { return new com.autoplay.gson.Gson().toJson(value); }", probe));
        probe.getDeclaredMethod("fixed").instrument(CommunicationStatePatches.PreserveNullValues.instrument());
        Class<?> type = probe.toClass();
        Map<String, Object> stance = new HashMap<String, Object>();
        stance.put("id", "Neutral"); stance.put("name", null); stance.put("description", "");
        Map<String, Object> unseen = new HashMap<String, Object>();
        unseen.put("revealed", false); unseen.put("card", null);
        Map<String, Object> source = new HashMap<String, Object>();
        source.put("stance", stance); source.put("board", Arrays.asList(unseen));
        source.put("unavailable_counter", null); source.put("false_flag", false);
        JsonObject old = parse(type.getMethod("legacy", Object.class), source);
        check(!old.getAsJsonObject("stance").has("name"), "reproduced CM null omission");
        JsonObject fixed = parse(type.getMethod("fixed", Object.class), source);
        check(fixed.getAsJsonObject("stance").get("name").isJsonNull(), "neutral name explicitly null");
        check(fixed.getAsJsonArray("board").get(0).getAsJsonObject().get("card").isJsonNull(), "unseen card explicitly null");
        check(fixed.get("unavailable_counter").isJsonNull(), "missing runtime source explicitly null");
        check(!fixed.get("false_flag").getAsBoolean(), "known false unchanged");
        // Recorder stamps/saves JSON trees; nulls must survive that second boundary.
        check(new JsonParser().parse(fixed.toString()).getAsJsonObject().getAsJsonObject("stance").get("name").isJsonNull(), "journal tree roundtrip");
        System.out.println("PASS actual CM serializer patch / nested nulls / hidden cards / known false / journal roundtrip");
    }
    private static JsonObject parse(Method method, Object value) throws Exception {
        return new JsonParser().parse((String) method.invoke(null, value)).getAsJsonObject();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
