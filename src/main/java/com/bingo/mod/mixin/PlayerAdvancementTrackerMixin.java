package com.bingo.mod.mixin;

import com.bingo.mod.game.detect.ActionEvent;
import com.bingo.mod.game.detect.ObjectiveValidator;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Déclencheur {@code bingo:advancement} (`docs/01` §4.5).
 *
 * <p>Le déclencheur le plus rentable du registre : il couvre à lui seul une centaine d'objectifs
 * potentiels sans une ligne de Java côté datapack. Deux des objectifs livrés s'en servent
 * ({@code minecraft:end/kill_dragon} et {@code minecraft:husbandry/plant_seed}).
 *
 * <p>L'injection est sur {@code grantCriterion} et non sur l'octroi de l'advancement : Minecraft
 * n'expose pas d'événement « advancement complété ». D'où le double test — le critère a bien été
 * ajouté (valeur de retour), <em>et</em> l'advancement est désormais complet. Sans le second,
 * chaque critère intermédiaire validerait la case.
 */
@Mixin(PlayerAdvancementTracker.class)
public abstract class PlayerAdvancementTrackerMixin {

	@Shadow
	private ServerPlayerEntity owner;

	@Shadow
	public abstract AdvancementProgress getProgress(Advancement advancement);

	@Inject(method = "grantCriterion", at = @At("RETURN"))
	private void bingo$onGrantCriterion(Advancement advancement,
	                                    String criterionName,
	                                    CallbackInfoReturnable<Boolean> info) {
		if (!info.getReturnValueZ() || owner == null || !getProgress(advancement).isDone()) {
			return;
		}
		ObjectiveValidator.onAction(owner, new ActionEvent.AdvancementDone(advancement.getId()));
	}
}
