package com.bingo.mod.game;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Vidage des inventaires au lancement d'une manche (clé de config {@code clear_inventory_on_start}).
 *
 * <p><strong>Pourquoi ce n'est pas un simple {@code getInventory().clear()}</strong> : trois piles
 * échappent à l'inventaire du joueur au moment où on l'efface — celle tenue par le curseur, celles
 * posées dans une grille de craft, et celles d'un conteneur ouvert dont le client garde un cache.
 * Les oublier laisse un joueur commencer la manche avec du stuff, ce qui est exactement ce que le
 * vidage cherchait à empêcher, et le fait de façon aléatoire selon ce qu'il avait à l'écran.
 *
 * <p>Appelé sur <em>tous</em> les joueurs connectés, équipe ou pas : un opérateur qui observe la
 * manche perd donc son inventaire. C'est assumé — un vidage qui épargne certains joueurs demanderait
 * de décider qui « joue vraiment », et la clé de config est là pour le désactiver franchement.
 */
public final class BingoInventoryReset {

	private BingoInventoryReset() {
	}

	public static void clearAll(BingoGame game) {
		int cleared = 0;
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			clear(player);
			player.sendMessage(Text.translatable(BingoConstants.key("message.inventory_cleared"))
					.formatted(Formatting.GRAY), false);
			cleared++;
		}
		BingoConstants.LOGGER.info("Inventaire vidé pour {} joueur(s) au lancement de la manche", cleared);
	}

	/**
	 * Ordre imposé : curseur, grilles de craft, écran, puis inventaire.
	 *
	 * <p>Le curseur et les grilles <strong>avant</strong> la fermeture d'écran, parce que
	 * {@code closeHandledScreen} fait <em>tomber au sol</em> ce qu'il y trouve (vanilla
	 * {@code ScreenHandler#onClosed}). Dans l'autre ordre, les piles réapparaîtraient en items posés
	 * aux pieds du joueur — vidées de son inventaire, mais toujours à lui.
	 */
	private static void clear(ServerPlayerEntity player) {
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
}
