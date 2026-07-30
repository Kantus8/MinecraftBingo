package com.bingo.mod.mixin;

import com.bingo.mod.game.detect.ActionEvent;
import com.bingo.mod.game.detect.ObjectiveValidator;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Déclencheur {@code bingo:trade_with_villager} (`docs/01` §4.5).
 *
 * <p>{@code afterUsing} est appelé une fois l'échange conclu, et donne accès à la fois au
 * villageois — donc à sa profession, seul paramètre du déclencheur — et à son client. C'est ce qui
 * l'a fait préférer à un mixin sur {@code TradeOutputSlot}, où la profession n'est atteignable
 * qu'en shadowant un champ privé.
 *
 * <p><strong>Limite assumée</strong> : le marchand ambulant n'est pas un {@code VillagerEntity} et
 * ne déclenche donc rien. C'est cohérent avec l'intitulé de l'objectif livré (« échanger avec un
 * villageois ») ; un datapack qui voudrait l'inclure aurait besoin d'un déclencheur distinct.
 */
@Mixin(VillagerEntity.class)
public class VillagerTradeMixin {

	@Inject(method = "afterUsing", at = @At("TAIL"))
	private void bingo$afterUsing(TradeOffer offer, CallbackInfo info) {
		VillagerEntity villager = (VillagerEntity) (Object) this;
		if (!(villager.getCustomer() instanceof ServerPlayerEntity player)) {
			return;
		}
		Identifier profession = Registries.VILLAGER_PROFESSION.getId(
				villager.getVillagerData().getProfession());
		ObjectiveValidator.onAction(player,
				new ActionEvent.VillagerTraded(Optional.ofNullable(profession)));
	}
}
