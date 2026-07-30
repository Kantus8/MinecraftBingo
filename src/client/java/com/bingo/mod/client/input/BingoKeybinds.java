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
 * <p>{@code B} est libre en vanilla 1.20.1. Le bind « Afficher/masquer le HUD » reste
 * <strong>non assigné</strong> par défaut : c'est un réglage qu'on change une fois, pas une action
 * de jeu, et occuper une touche pour ça priverait le joueur d'un raccourci utile.
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

		toggleHud = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.bingo.toggle_hud",
				InputUtil.Type.KEYSYM,
				InputUtil.UNKNOWN_KEY.getCode(),
				"key.categories.bingo"));

		// Non assigné par défaut, comme la bascule du HUD et pour la même raison : le tableau des
		// équipes est fait pour rester affiché, le masquer est un réglage qu'on change une fois.
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
