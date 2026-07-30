package com.bingo.mod.client.hud;

import com.bingo.mod.client.BingoClientState;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

/**
 * Overlay HUD permanent, coin haut-gauche (`docs/03` §1-2, §4, tâche 2.12).
 *
 * <p><strong>Non cliquable par construction</strong> : un overlay dessiné par
 * {@link HudRenderCallback} ne reçoit jamais d'événement de souris, celles-ci n'étant routées que
 * vers un {@code Screen} actif. C'est {@code BingoBoardScreen} qui rend la grille cliquable, en la
 * redessinant au même endroit (`docs/03` en tête).
 *
 * <p>La classe ne fait donc que deux choses : décider s'il faut dessiner, et déléguer. Toute la
 * logique de visibilité vit dans {@code BingoClientState#shouldRenderHud}, pour que l'écran et le
 * HUD ne puissent pas diverger sur la question.
 */
public final class BingoHudOverlay {

	private BingoHudOverlay() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (!BingoClientState.shouldRenderHud(client)) {
				return;
			}
			if (!BingoBoardLayout.fitsOnScreen(client, BingoClientState.revealOpponentProgress())) {
				return;
			}
			BingoBoardRenderer.render(context, client.textRenderer, BingoBoardRenderer.NO_HOVER);
		});
	}
}
