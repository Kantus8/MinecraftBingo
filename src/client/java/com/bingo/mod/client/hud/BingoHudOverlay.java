package com.bingo.mod.client.hud;

import com.bingo.mod.client.BingoClientState;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Overlays HUD permanents : la grille en haut-gauche (`docs/03` §1-2, §4, tâche 2.12) et le tableau
 * des équipes en haut-droite.
 *
 * <p><strong>Non cliquables par construction</strong> : un overlay dessiné par
 * {@link HudRenderCallback} ne reçoit jamais d'événement de souris, celles-ci n'étant routées que
 * vers un {@code Screen} actif. C'est {@code BingoBoardScreen} qui rend la grille cliquable, en la
 * redessinant au même endroit (`docs/03` en tête).
 *
 * <p>La classe ne fait donc que deux choses : décider s'il faut dessiner, et déléguer. Toute la
 * logique de visibilité vit dans {@code BingoClientState}, pour que les écrans et le HUD ne puissent
 * pas diverger sur la question.
 *
 * <p>Les deux panneaux partagent un unique callback mais <strong>pas</strong> leur condition
 * d'affichage : la grille exige une carte tirée, le tableau des équipes s'affiche dès l'entrée en
 * jeu. D'où deux méthodes et non deux {@code return} dans la même.
 */
public final class BingoHudOverlay {

	private BingoHudOverlay() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			renderBoard(context, client);
			renderTeamPanel(context, client);
		});
	}

	private static void renderBoard(DrawContext context, MinecraftClient client) {
		if (!BingoClientState.shouldRenderHud(client)) {
			return;
		}
		if (!BingoBoardLayout.fitsOnScreen(client, BingoClientState.revealOpponentProgress())) {
			return;
		}
		BingoBoardRenderer.render(context, client.textRenderer, BingoBoardRenderer.NO_HOVER);
	}

	private static void renderTeamPanel(DrawContext context, MinecraftClient client) {
		if (!BingoClientState.shouldRenderTeamPanel(client)) {
			return;
		}
		BingoTeamPanelRenderer.render(context, client.textRenderer);
	}
}
