package com.bingo.mod.mixin;

import com.bingo.mod.game.detect.ActionEvent;
import com.bingo.mod.game.detect.ObjectiveValidator;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Déclencheur {@code bingo:enchant_item} (`docs/01` §4.5).
 *
 * <p>Le résultat est relu dans l'emplacement 0 <em>après</em> l'enchantement : la table
 * d'enchantement enchante l'item sur place, elle ne le déplace pas. Passer par
 * {@code ScreenHandler#getSlot(0)}, méthode publique, plutôt que par un {@code @Shadow} du champ
 * privé {@code inventory} — un accesseur public ne change pas de nom entre deux versions de yarn.
 */
@Mixin(EnchantmentScreenHandler.class)
public class EnchantmentScreenHandlerMixin {

	@Inject(method = "onButtonClick", at = @At("RETURN"))
	private void bingo$onEnchant(PlayerEntity player, int id, CallbackInfoReturnable<Boolean> info) {
		if (!info.getReturnValueZ() || !(player instanceof ServerPlayerEntity serverPlayer)) {
			return;
		}

		ItemStack result = ((ScreenHandler) (Object) this).getSlot(0).getStack();
		Map<Enchantment, Integer> enchantments = EnchantmentHelper.get(result);
		if (enchantments.isEmpty()) {
			return;
		}

		Map<Identifier, Integer> byId = new LinkedHashMap<>();
		enchantments.forEach((enchantment, level) -> {
			Identifier enchantmentId = Registries.ENCHANTMENT.getId(enchantment);
			if (enchantmentId != null) {
				byId.put(enchantmentId, level);
			}
		});

		ObjectiveValidator.onAction(serverPlayer, new ActionEvent.ItemEnchanted(byId));
	}
}
