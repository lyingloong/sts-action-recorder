package actionrecorder.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/** Observe the official executor, never replace game decisions or simulate clicks. */
public final class CommunicationCommandPatches {
    @SpirePatch(cls="communicationmod.CommunicationMod", method="receivePreUpdate",
            requiredModId="CommunicationMod", optional=true)
    public static class CorrelateCommands {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("executeCommand".equals(call.getMethodName())) {
                        call.replace("{ actionrecorder.runtime.CommandLedger ledger = actionrecorder.runtime.CommandLedger.INSTANCE;"
                                + " try { $_ = $proceed(ledger.begin($1)); ledger.accepted(); }"
                                + " catch (communicationmod.InvalidCommandException e) { ledger.failed(e.getMessage()); throw e; }"
                                + " catch (java.lang.IllegalArgumentException e) { ledger.failed(e.getMessage());"
                                + " throw new communicationmod.InvalidCommandException(e.getMessage()); }"
                                + " catch (java.lang.RuntimeException e) { ledger.failed(e.toString()); throw e; }"
                                + " finally { ledger.finish(); } }");
                    } else if ("sendMessage".equals(call.getMethodName())) {
                        call.replace("{ $proceed(actionrecorder.runtime.CommandLedger.INSTANCE.decorate($1)); }");
                    }
                }
            };
        }
    }

    @SpirePatch(cls="communicationmod.CommandExecutor", method="executePlayCommand",
            requiredModId="CommunicationMod", optional=true)
    public static class RecordQueuedCard {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("java.util.ArrayList".equals(call.getClassName()) && "add".equals(call.getMethodName())) {
                        call.replace("{ actionrecorder.runtime.ActionRecorderRuntime runtime = actionrecorder.runtime.ActionRecorderRuntime.getInstance();"
                                + " int depth = runtime.decisionDepth(); runtime.beginDecision();"
                                + " try { $_ = $proceed($$); if ($_ ) runtime.recordCardQueued((com.megacrit.cardcrawl.cards.CardQueueItem)$1); }"
                                + " finally { runtime.discardDecisionsAfter(depth); } }");
                    }
                }
            };
        }
    }

    @SpirePatch(cls="communicationmod.CommandExecutor", method="executePotionCommand",
            requiredModId="CommunicationMod", optional=true)
    public static class RecordPotion {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("com.megacrit.cardcrawl.potions.AbstractPotion".equals(call.getClassName())
                            && "use".equals(call.getMethodName())) {
                        call.replace("{ actionrecorder.runtime.ActionRecorderRuntime runtime = actionrecorder.runtime.ActionRecorderRuntime.getInstance();"
                                + " int depth = runtime.decisionDepth(); int slot = com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.potions.indexOf($0);"
                                + " runtime.beginDecision(); try { $proceed($$);"
                                + " runtime.recordPotionUseTarget(slot, $0,"
                                + " ($1 instanceof com.megacrit.cardcrawl.monsters.AbstractMonster) ? (com.megacrit.cardcrawl.monsters.AbstractMonster)$1 : null);"
                                + " } finally { runtime.discardDecisionsAfter(depth); } }");
                    } else if ("destroyPotion".equals(call.getMethodName())) {
                        // This call also consumes used potions. Only DISCARD is a second semantic action.
                        call.replace("{ actionrecorder.runtime.ActionRecorderRuntime runtime = actionrecorder.runtime.ActionRecorderRuntime.getInstance();"
                                + " int depth = runtime.decisionDepth();"
                                // Javassist does not implement Java generic casts: get() emits Object.
                                // The cast must be explicit or the transformed class fails JVM verification.
                                + " com.megacrit.cardcrawl.potions.AbstractPotion potion = (com.megacrit.cardcrawl.potions.AbstractPotion)"
                                + " com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.potions.get($1);"
                                + " boolean discard = tokens[1].equalsIgnoreCase(\"discard\");"
                                + " if (discard) runtime.beginDecision(); try { $proceed($$);"
                                + " if (discard) runtime.recordPotionAction(\"POTION_DISCARD\", \"potion_discarded\", $1, potion);"
                                + " } finally { runtime.discardDecisionsAfter(depth); } }");
                    }
                }
            };
        }
    }
}
