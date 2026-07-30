package com.bingo.mod.client.input;

import com.bingo.mod.client.BingoClientState;
import com.bingo.mod.client.screen.BingoBoardScreen;
import com.bingo.mod.util.BingoConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Keybinds du mod (`docs/03` §5, tâche 2.14).
 *
 * <p>{@code B} ouvre la carte et {@code H} masque le bingo — deux touches libres en vanilla 1.20.1.
 *
 * <p><strong>Revirement assumé sur {@code H}</strong> : la bascule du HUD était non assignée, au
 * motif qu'un réglage qu'on change une fois ne mérite pas de confisquer une touche. C'est faux à
 * l'usage — la grille occupe le coin haut-gauche, là où on regarde pour construire, et le joueur la
 * masque puis la remontre plusieurs fois par manche. C'est une action de jeu.
 *
 * <p>Le tableau des équipes garde, lui, un bind non assigné : il est petit, ancré à droite et n'a
 * jamais gêné personne. Deux touches pour masquer deux morceaux du même HUD demanderaient au joueur
 * de retenir laquelle fait quoi.
 *
 * <p>« Parler à l'équipe » n'est pas ici : Simple Voice Chat gère déjà ses propres binds de groupe,
 * les dupliquer créerait deux touches pour un seul effet.
 */
public final class BingoKeybinds {

	private static KeyBinding openBoard;
	private static KeyBinding toggleHud;
	private static KeyBinding toggleTeamPanel;

	private BingoKeybinds() {
	}

	public static void register() {
		openBoard = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bingo.open_board",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_B,
				"key.categories.bingo"));

		// H comme « hide » : libre en vanilla, et voisine immédiate du J et du K qu'aucun mod courant
		// n'occupe non plus. Réassignable dans les options comme n'importe quel bind.
		toggleHud = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bingo.toggle_hud",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				"key.categories.bingo"));

		// Non assigné, contrairement à la bascule du HUD : le tableau des équipes est fait pour rester
		// affiché, et le masquer reste un réglage qu'on change une fois.
		toggleTeamPanel = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bingo.toggle_team_panel",
				InputUtil.Type.KEYSYM,
				InputUtil.UNKNOWN_KEY.getCode(),
				"key.categories.bingo"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// wasPressed() et non isPressed() : la boucle consomme une pression à la fois, sinon
			// maintenir la touche rouvrirait l'écran à chaque tick.
			while (openBoard.wasPressed()) {
				if (client.world != null) {
					BingoBoardScreen.open();
				}
			}
			while (toggleHud.wasPressed()) {
				boolean visible = BingoClientState.toggleHud();
				if (client.player != null) {
					client.player.sendMessage(Text.translatable(BingoConstants.key(
							visible ? "hud.shown" : "hud.hidden")), true);
				}
			}
			while (toggleTeamPanel.wasPressed()) {
				boolean visible = BingoClientState.toggleTeamPanel();
				if (client.player != null) {
					client.player.sendMessage(Text.translatable(BingoConstants.key(
							visible ? "hud.teams.shown" : "hud.teams.hidden")), true);
				}
			}
		});
	}
}
