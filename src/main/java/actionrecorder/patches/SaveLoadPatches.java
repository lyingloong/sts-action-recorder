package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.characters.AbstractPlayer;

/** Start the load barrier before vanilla/BaseMod restore any saved fields. */
public final class SaveLoadPatches {
    @SpirePatch(clz = CardCrawlGame.class, method = "loadPlayerSave",
            paramtypez = {AbstractPlayer.class})
    public static class Loading {
        @SpirePrefixPatch public static void prefix() {
            ActionRecorderRuntime.getInstance().beginLoadingSave();
        }
    }
}
