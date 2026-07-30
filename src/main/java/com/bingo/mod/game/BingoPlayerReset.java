package com.bingo.mod.game;

import com.bingo.mod.config.BingoServerConfig;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Table rase des joueurs au lancement d'une manche : inventaire, niveaux, succès.
 *
 * <p>Trois remises à zéro plutôt qu'une, chacune derrière sa clé de config
 * ({@code clear_inventory_on_start}, {@code reset_levels_on_start},
 * {@code reset_advancements_on_start}) : elles servent la même intention — personne ne démarre avec
 * de l'avance — mais un opérateur peut vouloir vider les sacs sans effacer les succès d'un monde de
 * longue date.
 *
 * <p>Appliqué à <em>tous</em> les joueurs connectés, équipe ou pas : un opérateur qui observe la
 * manche est réinitialisé lui aussi. C'est assumé — épargner certains joueurs demanderait de décider
 * qui « joue vraiment », et les clés de config sont là pour couper franchement.
 */
public final class BingoPlayerReset {

	private BingoPlayerReset() {
	}

	/**
	 * Applique les remises à zéro activées, et annonce en une seule ligne ce qui a été fait.
	 *
	 * <p>Une ligne composée plutôt que trois messages : le lancement de manche envoie déjà
	 * l'animation de tirage et le décompte, et trois lignes de chat au même instant noieraient
	 * l'information. Rien n'est envoyé si les trois clés sont à faux.
	 */
	public static void applyAll(BingoGame game) {
		List<Text> done = new ArrayList<>();
		if (BingoServerConfig.clearInventoryOnStart) {
			done.add(fragment("inventory"));
		}
		if (BingoServerConfig.resetLevelsOnStart) {
			done.add(fragment("levels"));
		}
		if (BingoServerConfig.resetAdvancementsOnStart) {
			done.add(fragment("advancements"));
		}
		if (done.isEmpty()) {
			return;
		}

		Text summary = Text.translatable(BingoConstants.key("message.round_reset"),
				Texts.join(done, text -> text)).formatted(Formatting.GRAY);

		int count = 0;
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			if (BingoServerConfig.clearInventoryOnStart) {
				clearInventory(player);
			}
			if (BingoServerConfig.resetLevelsOnStart) {
				resetExperience(player);
			}
			if (BingoServerConfig.resetAdvancementsOnStart) {
				revokeAdvancements(game, player);
			}
			player.sendMessage(summary, false);
			count++;
		}
		BingoConstants.LOGGER.info("Table rase appliquée à {} joueur(s) au lancement de la manche", count);
	}

	private static Text fragment(String suffix) {
		return Text.translatable(BingoConstants.key("message.round_reset." + suffix));
	}

	// ── Inventaire ────────────────────────────────────────────────────────────

	/**
	 * Ordre imposé : curseur, grilles de craft, écran, puis inventaire.
	 *
	 * <p>Trois piles échappent à un simple {@code getInventory().clear()} — celle tenue par le
	 * curseur, celles posées dans une grille de craft, et celles d'un conteneur ouvert dont le client
	 * garde un cache. Les oublier laisse un joueur démarrer avec du stuff, et de façon aléatoire
	 * selon ce qu'il avait à l'écran.
	 *
	 * <p>Le curseur et les grilles <strong>avant</strong> la fermeture d'écran, parce que
	 * {@code closeHandledScreen} fait <em>tomber au sol</em> ce qu'il y trouve (vanilla
	 * {@code ScreenHandler#onClosed}). Dans l'autre ordre, les piles réapparaîtraient en items posés
	 * aux pieds du joueur — vidées de son inventaire, mais toujours à lui.
	 */
	private static void clearInventory(ServerPlayerEntity player) {
		player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);

		// La grille du joueur et celle de l'écran courant sont deux inventaires distincts : un joueur
		// devant une table de craft a des piles dans les deux.
		player.playerScreenHandler.clearCraftingSlots();
		if (player.currentScreenHandler instanceof AbstractRecipeScreenHandler<?> recipeHandler) {
			recipeHandler.clearCraftingSlots();
		}

		// Seulement si un conteneur est ouvert : envoyer la fermeture sans condition refermerait aussi
		// le menu de pause du joueur, ce qu'un lancement de manche n'a aucune raison de faire.
		if (player.currentScreenHandler != player.playerScreenHandler) {
			player.closeHandledScreen();
		}

		// Principal, armure et main secondaire d'un coup (PlayerInventory#clear).
		player.getInventory().clear();

		// Le tick serveur finirait par synchroniser, mais un inventaire ouvert au moment du vidage
		// afficherait ses anciennes piles jusque là — et un clic dessus produirait une désync.
		player.currentScreenHandler.sendContentUpdates();
	}

	// ── Niveaux ───────────────────────────────────────────────────────────────

	/**
	 * Remet niveaux, barre de progression et total d'expérience à zéro.
	 *
	 * <p>Les deux appels sont nécessaires, et dans cet ordre, comme dans {@code ExperienceCommand} :
	 * {@code setExperienceLevel} ne touche pas au total, et {@code setExperiencePoints} calcule la
	 * fraction de barre à partir du niveau <em>courant</em>. Poser les points d'abord laisserait une
	 * barre remplie sous un niveau 0.
	 *
	 * <p>Ce n'est pas qu'une question d'équité : enchanter coûte de l'expérience, et garder les 30
	 * niveaux d'un monde de longue date validerait la case « enchanter un objet » avant que quiconque
	 * ait miné un bloc.
	 */
	private static void resetExperience(ServerPlayerEntity player) {
		player.setExperienceLevel(0);
		player.setExperiencePoints(0);
	}

	// ── Succès ────────────────────────────────────────────────────────────────

	/**
	 * Révoque tous les succès, critère par critère — l'équivalent de
	 * {@code /advancement revoke <joueur> everything}.
	 *
	 * <p><strong>Ce n'est pas cosmétique : c'est ce qui rend les objectifs {@code bingo:advancement}
	 * jouables.</strong> Le déclencheur s'accroche à {@code grantCriterion}
	 * ({@code PlayerAdvancementTrackerMixin}), donc un joueur qui possède déjà
	 * {@code husbandry/fishy_business} ne le regagnera jamais : sa case « pêcher n'importe quoi »
	 * resterait vide quoi qu'il fasse. Sur un monde neuf le défaut est invisible ; sur un monde de
	 * longue date il rend plusieurs cases impossibles.
	 *
	 * <p>Critère par critère et non « advancement par advancement » parce que c'est la seule API
	 * publique de retrait — {@code PlayerAdvancementTracker} n'expose pas de révocation en bloc, et
	 * c'est exactement ce que fait la commande vanilla.
	 *
	 * <p>Deux effets de bord assumés, identiques à ceux de la commande : les succès de recette
	 * repartent à zéro (leurs notifications réapparaîtront au fil des crafts), et les succès racines
	 * se réattribuent au tick suivant puisqu'ils se déclenchent sur un simple tick. Aucun objectif
	 * livré ne cible une racine, donc ce réoctroi ne valide aucune case.
	 */
	private static void revokeAdvancements(BingoGame game, ServerPlayerEntity player) {
		PlayerAdvancementTracker tracker = player.getAdvancementTracker();
		for (Advancement advancement : game.server().getAdvancementLoader().getAdvancements()) {
			for (String criterion : advancement.getCriteria().keySet()) {
				tracker.revokeCriterion(advancement, criterion);
			}
		}
	}
}
