package com.bingo.mod.mixin;

import com.bingo.mod.game.detect.ActionEvent;
import com.bingo.mod.game.detect.ObjectiveValidator;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Déclencheur {@code bingo:eat_item}.
 *
 * <p>Écrit parce qu'<strong>aucun advancement vanilla ne couvre « manger tel aliment »</strong> :
 * {@code husbandry/balanced_diet} exige le régime complet, et il n'existe rien par aliment. Le
 * réflexe du registre — privilégier {@link com.bingo.mod.game.detect.ActionTriggers#ADVANCEMENT} —
 * n'avait donc pas de solution ici.
 *
 * <p><strong>Cible {@code LivingEntity} et non {@code PlayerEntity}</strong> : en 1.20.1,
 * {@code eatFood} n'est déclaré que par {@code LivingEntity} — le joueur ne le redéfinit pas. Viser
 * {@code PlayerEntity} compile sans broncher (le nom de méthode d'un mixin est une chaîne) et fait
 * échouer l'injection au chargement du monde. D'où le filtre {@code instanceof} plutôt qu'un type
 * cible plus étroit.
 *
 * <p>En {@code HEAD} et non {@code TAIL} : la pile est décrémentée pendant l'appel, et une pile d'un
 * seul exemplaire serait déjà vide à la sortie. Le test {@code isFood} reproduit celui de vanilla —
 * sans lui, un objet non comestible passé par {@code finishUsing} émettrait un événement de repas.
 *
 * <p>Limite assumée : les <em>boissons</em> (lait, potions) ne passent pas par {@code eatFood}, et le
 * gâteau mangé depuis le bloc non plus. Aucun objectif livré n'en a besoin ; le jour où ce sera le
 * cas, ce sera un déclencheur distinct plutôt qu'un élargissement de celui-ci — « manger » et
 * « boire » ne se formulent pas pareil dans la description d'une case.
 */
@Mixin(LivingEntity.class)
public class LivingEntityEatFoodMixin {

	@Inject(method = "eatFood", at = @At("HEAD"))
	private void bingo$onEatFood(World world, ItemStack stack, CallbackInfoReturnable<ItemStack> info) {
		if ((Object) this instanceof ServerPlayerEntity serverPlayer && stack.isFood()) {
			ObjectiveValidator.onAction(serverPlayer,
					new ActionEvent.ItemEaten(Registries.ITEM.getId(stack.getItem())));
		}
	}
}
