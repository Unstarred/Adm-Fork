package com.engineerclient.mixin;

import com.engineerclient.misc.ScoreboardLines;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

@Mixin(Hud.class)
public class ScoreboardSidebarMixin {

    @Redirect(
        method = "displayScoreboardSidebar",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/scores/Scoreboard;listPlayerScores(Lnet/minecraft/world/scores/Objective;)Ljava/util/Collection;"
        )
    )
    private Collection<PlayerScoreEntry> ec$hideSidebarLines(Scoreboard scoreboard, Objective objective) {
        try {
            return ScoreboardLines.INSTANCE.visibleEntries(scoreboard, objective);
        } catch (Throwable t) {
            return scoreboard.listPlayerScores(objective);
        }
    }

    @Redirect(
        method = "displayScoreboardSidebar",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"
        )
    )
    private Component ec$hideSidebarTitle(Objective objective) {
        try {
            return ScoreboardLines.INSTANCE.hidesTitle() ? Component.empty() : objective.getDisplayName();
        } catch (Throwable t) {
            return objective.getDisplayName();
        }
    }
}
