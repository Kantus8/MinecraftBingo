package com.bingo.mod.game.detect;

import com.bingo.mod.util.BingoConstants;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

/**
 * Branchement des détecteurs sur les événements de jeu (tâches 2.7 et 2.8).
 *
 * <p>Tout ce qui peut passer par un événement Fabric passe par un événement Fabric. Les quatre
 * hooks qui exigent un mixin — craft, enchantement, échange, apprivoisement — vivent dans
 * {@code com.bingo.mod.mixin} et appellent directement {@link ObjectiveValidator} : un mixin est
 * un coût de maintenance à chaque montée de version, on n'en écrit un que faute d'alternative.
 *
 * <p>Les deux détecteurs périodiques ({@code FIND} et l'altitude) ne sont pas ici : ils sont
 * pilotés par le tick de partie, qui sait déjà si la manche tourne.
 */
public final class BingoDetectors {

	private BingoDetectors() {
	}

	public static void register() {
		// Un seul événement pour deux détecteurs : la mort d'un joueur est à la fois un objectif
		// DEATH pour lui et un KILL_MOB potentiel pour son agresseur.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayerEntity player) {
				ObjectiveValidator.onPlayerDeath(player, source);
			}
			ObjectiveValidator.onEntityKilled(entity, source);
		});

		EntitySleepEvents.STOP_SLEEPING.register((entity, sleepingPos) -> {
			if (entity instanceof ServerPlayerEntity player) {
				ObjectiveValidator.onAction(player, new ActionEvent.SleptInBed());
			}
		});

		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
				ObjectiveValidator.onAction(player,
						new ActionEvent.DimensionEntered(destination.getRegistryKey().getValue())));

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			// L'événement est appelé sur les deux côtés : côté client il n'y a ni partie ni équipe
			// autoritaires, et valider un objectif y serait au mieux inutile.
			if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
				ItemStack stack = player.getStackInHand(hand);
				if (!stack.isEmpty()) {
					Identifier item = Registries.ITEM.getId(stack.getItem());
					Identifier block = Registries.BLOCK.getId(
							world.getBlockState(hitResult.getBlockPos()).getBlock());
					ObjectiveValidator.onAction(serverPlayer, new ActionEvent.ItemUsedOnBlock(item, block));
				}
			}
			// PASS et jamais SUCCESS : on observe, on n'intercepte pas. Renvoyer autre chose
			// empêcherait l'interaction vanilla d'avoir lieu.
			return ActionResult.PASS;
		});

		BingoConstants.LOGGER.debug("Détecteurs d'objectifs enregistrés");
	}
}
