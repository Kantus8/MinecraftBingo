package com.bingo.mod.client.integration.jei;

import com.bingo.mod.integration.jei.BingoJeiPlugin;
import com.bingo.mod.network.payload.ObjectiveProjection;
import com.bingo.mod.objective.JeiRole;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Ouverture de JEI sur la recette d'une case (`docs/03` §3.1, tâche 3.7).
 *
 * <p>Vit dans {@code src/client} et non à côté du plugin : c'est ici qu'on touche à la GUI
 * (`docs/06` §5). Le plugin, lui, ne fait que détenir le runtime et doit rester chargeable sans
 * client.
 *
 * <p>Le pont ne décide de rien — il renvoie {@code false} dès qu'il ne peut pas faire son travail,
 * et c'est l'appelant qui dégrade vers le tooltip (`docs/03` §3.1).
 */
public final class BingoJeiBridge {

	private BingoJeiBridge() {
	}

	/**
	 * Affiche les recettes liées à l'objectif.
	 *
	 * <p><strong>{@code false} ne veut pas dire « aucune recette »</strong>, mais « l'appel n'a même
	 * pas pu partir ». Quand une recette manque, JEI ne renvoie rien et ne fait rien de visible :
	 * cette méthode renvoie alors {@code true} et c'est l'appelant qui doit constater que l'écran
	 * n'a pas changé. Le contrat est désagréable, il vient de l'API.
	 *
	 * @return {@code false} si le runtime est absent ou si aucun item exploitable n'a pu être
	 *         résolu — dans les deux cas, à l'appelant de replier sur le tooltip
	 */
	public static boolean showRecipe(ObjectiveProjection objective) {
		IJeiRuntime runtime = BingoJeiPlugin.getRuntime();
		if (runtime == null) {
			// JEI pas encore démarré. Arrive au tout premier clic après l'entrée dans un monde.
			return false;
		}

		ItemStack stack = focusStack(objective);
		if (stack.isEmpty()) {
			return false;
		}

		// OUTPUT = « comment obtenir cet item », ce qu'on veut pour CRAFT comme pour FIND
		// (`docs/03` §3.1). INPUT n'est utilisé que si le datapack le demande explicitement.
		RecipeIngredientRole role = objective.jeiRole().orElse(JeiRole.OUTPUT) == JeiRole.INPUT
				? RecipeIngredientRole.INPUT
				: RecipeIngredientRole.OUTPUT;

		IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();
		IFocus<ItemStack> focus = focusFactory.createFocus(role, VanillaTypes.ITEM_STACK, stack);

		// Remplace l'écran courant. Surtout ne pas appeler setScreen(null) avant : JEI gère la
		// transition, et son bouton retour ramène à l'écran précédent — donc à la grille.
		runtime.getRecipesGui().show(focus);
		return true;
	}

	/**
	 * L'item sur lequel focaliser JEI.
	 *
	 * <p>Trois sources, dans l'ordre de précision décroissante :
	 *
	 * <ol>
	 *   <li>la cible de l'objectif quand c'est un item — c'est <em>exactement</em> ce que la case
	 *       demande, et la raison d'être du {@code TargetHint} de la projection (`docs/06` §3.4) ;
	 *   <li>l'icône d'affichage, quand la cible est un tag : le tag peut contenir vingt items, et
	 *       l'icône est le représentant que l'auteur du datapack a choisi ;
	 *   <li>le premier item du tag, si l'icône elle-même est introuvable.
	 * </ol>
	 *
	 * <p>Le compte est ramené à 1 : un focus porte sur un item, pas sur une pile, et une icône
	 * déclarée {@code icon_count: 64} afficherait « 64 » dans l'en-tête de JEI.
	 */
	private static ItemStack focusStack(ObjectiveProjection objective) {
		Optional<ObjectiveProjection.TargetHint> target = objective.target();

		if (target.isPresent() && !target.get().tag()) {
			ItemStack fromTarget = stackOf(target.get().id());
			if (!fromTarget.isEmpty()) {
				return fromTarget;
			}
		}

		ItemStack fromIcon = stackOf(objective.icon());
		if (!fromIcon.isEmpty()) {
			return fromIcon;
		}

		if (target.isPresent() && target.get().tag()) {
			return firstOfTag(target.get().id());
		}
		return ItemStack.EMPTY;
	}

	/** Une pile de 1, vide si l'identifiant n'est pas un item enregistré. */
	private static ItemStack stackOf(Identifier id) {
		if (!Registries.ITEM.containsId(id)) {
			return ItemStack.EMPTY;
		}
		var item = Registries.ITEM.get(id);
		return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}

	/**
	 * Le premier item d'un tag, vide si le tag est inconnu ou sans contenu.
	 *
	 * <p>Les tags d'items sont synchronisés au client à la connexion : la lecture est valide ici.
	 */
	private static ItemStack firstOfTag(Identifier id) {
		return Registries.ITEM.getEntryList(TagKey.of(RegistryKeys.ITEM, id))
				.flatMap(entries -> entries.stream().findFirst())
				.map(RegistryEntry::value)
				.map(ItemStack::new)
				.orElse(ItemStack.EMPTY);
	}
}
