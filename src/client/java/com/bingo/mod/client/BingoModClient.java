package com.bingo.mod.client;

import com.bingo.mod.client.config.BingoClientConfig;
import com.bingo.mod.client.hud.BingoHudOverlay;
import com.bingo.mod.client.input.BingoKeybinds;
import com.bingo.mod.client.network.BingoClientNetworking;
import com.bingo.mod.client.roll.RollAnimationState;
import com.bingo.mod.util.BingoConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Entrypoint {@code client} — chargé uniquement côté client.
 *
 * <p>La config d'abord : {@code BingoBoardLayout} lit ses marges et son échelle dedans, et un HUD
 * dessiné avant le chargement du fichier prendrait les défauts pendant une frame.
 *
 * <p>Puis les récepteurs de paquets — un paquet arrivé avant son récepteur est perdu sans erreur —
 * puis l'overlay, les keybinds, et le tick qui fait avancer les sons du tirage.
 */
public class BingoModClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		BingoConstants.LOGGER.info("{} : environnement client", BingoConstants.MOD_NAME);

		BingoClientConfig.load();

		BingoClientNetworking.register();
		BingoHudOverlay.register();
		BingoKeybinds.register();

		// Les sons de l'animation sont les seuls éléments non dérivables de `elapsed` : ils ont
		// besoin d'un pouls. Tout le reste — icônes, verrous, flash, punch — se calcule au rendu
		// (`docs/04` §6).
		ClientTickEvents.END_CLIENT_TICK.register(client -> RollAnimationState.tick());
	}
}
