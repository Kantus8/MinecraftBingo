package com.bingo.mod.registry;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;

import java.util.List;

/**
 * Les tags d'items du mod et leur contenu de référence (`docs/04` §3).
 *
 * <p>Source unique de vérité pour {@code #bingo:roll_decoys} : la clé de tag est lue au runtime par
 * l'animation client ({@code RollAnimationState}) et par le datagen ({@code BingoItemTagProvider}),
 * qui écrit le JSON à partir de {@link #ROLL_DECOYS_ITEMS}. Un seul endroit à toucher pour ajouter
 * un leurre, et l'ajout est <strong>vérifié à la compilation</strong> — une constante {@code Items.*}
 * renommée ou disparue casse le build, là où le JSON écrit à la main laissait passer un item mort
 * qui n'apparaissait simplement jamais dans le défilement.
 */
public final class BingoItemTags {

	/**
	 * Le tag de leurres de l'animation Slot Machine (`docs/04` §3).
	 *
	 * <p>Défini ici et non plus en dur dans le client : les deux chemins — résolution au runtime et
	 * génération du JSON — doivent viser la même clé, faute de quoi le datapack généré ne serait
	 * jamais celui que l'animation lit.
	 */
	public static final TagKey<Item> ROLL_DECOYS =
			TagKey.of(RegistryKeys.ITEM, BingoConstants.id("roll_decoys"));

	/**
	 * Les 69 items « iconiques » qui composent le défilement (`docs/04` §3).
	 *
	 * <p>Le choix n'est pas anodin : `docs/04` §3 exige des items reconnaissables au passage — pas
	 * de blocs de commande ni d'œufs de dragon, qui « sonneraient faux » au reveal. La liste couvre
	 * minerais, nourriture, blocs de base, outils, armes et items de progression, de quoi rester
	 * crédible sur les 20 swaps de la phase A.
	 */
	public static final List<Item> ROLL_DECOYS_ITEMS = List.of(
			// Minerais et matériaux précieux
			Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT, Items.COPPER_INGOT,
			Items.NETHERITE_INGOT, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ,
			Items.AMETHYST_SHARD, Items.ECHO_SHARD,
			// Nourriture
			Items.APPLE, Items.GOLDEN_APPLE, Items.BREAD, Items.COOKED_BEEF, Items.CARROT,
			Items.POTATO, Items.CAKE, Items.COOKIE,
			// Blocs de base et ressources brutes
			Items.OAK_LOG, Items.STONE, Items.COBBLESTONE, Items.OBSIDIAN, Items.GLASS,
			Items.SAND, Items.GRAVEL, Items.CLAY_BALL, Items.WHEAT, Items.SUGAR_CANE,
			Items.BAMBOO, Items.CACTUS, Items.PUMPKIN, Items.MELON_SLICE, Items.SWEET_BERRIES,
			// Outils et armes
			Items.DIAMOND_SWORD, Items.BOW, Items.ARROW, Items.SHIELD, Items.TRIDENT,
			Items.CROSSBOW, Items.FISHING_ROD, Items.IRON_PICKAXE, Items.GOLDEN_AXE, Items.SHEARS,
			Items.FLINT_AND_STEEL, Items.BUCKET, Items.WATER_BUCKET,
			// Objets utilitaires et décoratifs
			Items.TORCH, Items.LANTERN, Items.CHEST, Items.FURNACE, Items.ANVIL,
			Items.CAULDRON, Items.BELL, Items.BEACON,
			// Items de progression et butin de mob
			Items.ENDER_PEARL, Items.BLAZE_ROD, Items.GHAST_TEAR, Items.SLIME_BALL, Items.GUNPOWDER,
			Items.FEATHER, Items.BONE, Items.STRING, Items.LEATHER, Items.PAPER,
			Items.BOOK, Items.MUSIC_DISC_CAT, Items.TOTEM_OF_UNDYING);

	private BingoItemTags() {
	}
}
