package com.bingo.mod.game.detect;

import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * Un événement de jeu susceptible de valider un objectif {@code bingo:action} (`docs/01` §4.5).
 *
 * <p>Ces records sont de la <strong>donnée pure</strong> : ils décrivent ce qui vient de se
 * produire, sans savoir quel objectif pourrait s'y intéresser. L'interprétation des
 * {@code params} du datapack vit dans {@link ActionTriggers}, un endroit par déclencheur.
 *
 * <p>Scellé volontairement : ajouter un déclencheur doit obliger à ajouter son événement ici,
 * ce qui rend visible d'un coup d'œil ce que le système sait observer.
 */
public sealed interface ActionEvent {

	/** Le déclencheur que cet événement peut satisfaire. */
	Identifier trigger();

	/** Un advancement vanilla vient d'être complété — couvre à lui seul une centaine d'objectifs. */
	record AdvancementDone(Identifier advancement) implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.ADVANCEMENT;
		}
	}

	/** Le joueur a fini de dormir. */
	record SleptInBed() implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.SLEEP_IN_BED;
		}
	}

	/** Le joueur vient de changer de dimension. */
	record DimensionEntered(Identifier dimension) implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.ENTER_DIMENSION;
		}
	}

	/**
	 * Un item vient d'être enchanté.
	 *
	 * @param enchantments les enchantements du résultat et leur niveau. La table complète et non
	 *                     le seul enchantement ajouté : la table d'enchantement en pose plusieurs
	 *                     d'un coup, et lequel est « celui qu'on voulait » n'est pas décidable ici.
	 */
	record ItemEnchanted(Map<Identifier, Integer> enchantments) implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.ENCHANT_ITEM;
		}
	}

	/** Un échange avec un villageois vient d'aboutir. */
	record VillagerTraded(Optional<Identifier> profession) implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.TRADE_WITH_VILLAGER;
		}
	}

	/** Un animal vient d'être apprivoisé. */
	record AnimalTamed(EntityType<?> type) implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.TAME_ANIMAL;
		}
	}

	/** Position verticale du joueur, échantillonnée périodiquement. */
	record YLevelReached(double y) implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.REACH_Y_LEVEL;
		}
	}

	/** Le joueur a utilisé un item sur un bloc. */
	record ItemUsedOnBlock(Identifier item, Identifier block) implements ActionEvent {
		@Override
		public Identifier trigger() {
			return ActionTriggers.USE_ITEM_ON_BLOCK;
		}
	}
}
