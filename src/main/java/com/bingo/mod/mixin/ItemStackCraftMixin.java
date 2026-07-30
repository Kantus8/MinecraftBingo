package com.bingo.mod.mixin;

import com.bingo.mod.game.detect.ObjectiveValidator;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Détection des objectifs {@code bingo:craft} (`docs/01` §4.1).
 *
 * <p>{@code ItemStack#onCraft} est le point de passage <strong>unique</strong> de tout ce que
 * vanilla considère comme fabriqué : c'est lui qui incrémente la statistique
 * {@code minecraft:crafted}. En un seul mixin, on couvre donc la table de craft, la grille 2×2 de
 * l'inventaire, le four, le fumoir, le haut-fourneau, la table de forge et la table de découpe.
 *
 * <p>C'est ce qui a fait préférer ce point d'ancrage aux trois ou quatre mixins de slots que
 * `docs/01` §4.1 évoque ({@code CraftingResultSlot#onTakeItem} et compagnie) : moins de surface de
 * mixin, et aucun risque d'oublier un poste de fabrication — le {@code netherite_pickaxe} livré
 * passe par la table de forge, pas par une table de craft.
 *
 * <p>Le paramètre {@code amount} porte le nombre d'exemplaires produits, ce qui règle d'emblée le
 * piège du shift-clic (`docs/01` §4.1).
 */
@Mixin(ItemStack.class)
public class ItemStackCraftMixin {

	@Inject(method = "onCraft", at = @At("TAIL"))
	private void bingo$onCraft(World world, PlayerEntity player, int amount, CallbackInfo info) {
		if (player instanceof ServerPlayerEntity serverPlayer) {
			ObjectiveValidator.onCraft(serverPlayer, (ItemStack) (Object) this, amount);
		}
	}
}
