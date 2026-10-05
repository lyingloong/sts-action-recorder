package actionrecorder;

import actionrecorder.runtime.RuntimeDescriptions;
import java.lang.reflect.Method;

/** Test numeric fallback and game text formatting without asset initialization. */
public final class RuntimeDescriptionsSmoke {
    public static void main(String[] args) throws Exception {
        Method value = RuntimeDescriptions.class.getDeclaredMethod("builtinValue", int.class, int.class);
        value.setAccessible(true);
        check(Integer.valueOf(6).equals(value.invoke(null, -1, 6)), "deck preview falls back to base");
        check(Integer.valueOf(9).equals(value.invoke(null, -1, 9)), "upgraded base is preserved");
        check(Integer.valueOf(0).equals(value.invoke(null, 0, 6)), "live zero is preserved");
        check(Integer.valueOf(7).equals(value.invoke(null, 7, 5)), "modified live preview is preserved");
        check(value.invoke(null, -1, -1) == null, "unknown remains unavailable");
        check(RuntimeDescriptions.plain("#rDeal 6 damage. NL Gain [R].")
                .equals("Deal 6 damage. \n Gain Energy."), "formatting is removed and numbers retained");
        check(RuntimeDescriptions.plain("!unknown!").equals("!unknown!"), "unknown token is not erased");
        System.out.println("PASS runtime numeric fallback / live zero / unavailable values / game text formatting");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
