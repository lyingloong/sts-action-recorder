package actionrecorder.runtime;

import java.lang.reflect.Method;

/** Optional access to the same publisher used by CommunicationMod's script interface. */
final class CommunicationStateBridge {
    private Method publisher;
    private boolean warned;

    boolean publish() {
        try {
            if (publisher == null) {
                publisher = Class.forName("communicationmod.CommunicationMod")
                        .getDeclaredMethod("sendGameState");
                publisher.setAccessible(true);
            }
            publisher.invoke(null);
            return true;
        } catch (Throwable exc) {
            if (!warned) {
                warned = true;
                System.err.println("[ActionRecorder] state publisher unavailable: " + exc);
            }
            return false;
        }
    }
}
