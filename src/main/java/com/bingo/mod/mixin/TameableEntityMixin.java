package com.bingo.mod.mixin;

import com.bingo.mod.game.detect.ActionEvent;
import com.bingo.mod.game.detect.ObjectiveValidator;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Déclencheur {@code bingo:tame_animal} (`docs/01` §4.5).
 *
 * <p>Couvre les loups, chats, perroquets et axolotls — tout ce qui dérive de
 * {@link TameableEntity}. <strong>Pas</strong> les chevaux et assimilés, qui passent par
 * {@code AbstractHorseEntity#bondWithPlayer} sans hériter de cette classe. L'objectif livré
 * apprivoise un loup ; ajouter les montures demanderait un second point d'ancrage, à faire le jour
 * où un datapack en a besoin.
 */
@Mixin(TameableEntity.class)
public class TameableEntityMixin {

	@Inject(method = "setOwner", at = @At("TAIL"))
	private void bingo$onTamed(PlayerEntity player, CallbackInfo info) {
		if (player instanceof ServerPlayerEntity serverPlayer) {
			TameableEntity tamed = (TameableEntity) (Object) this;
			ObjectiveValidator.onAction(serverPlayer, new ActionEvent.AnimalTamed(tamed.getType()));
		}
	}
}
