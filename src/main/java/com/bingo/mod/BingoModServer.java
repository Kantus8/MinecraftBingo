package com.bingo.mod;

import com.bingo.mod.util.BingoConstants;
import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * Entrypoint {@code server} — chargé uniquement sur un serveur dédié.
 *
 * <p>Volontairement presque vide : la logique de partie est déjà autoritaire dans
 * {@link BingoMod}, qui tourne aussi bien en solo qu'en dédié. Cette classe est le
 * point d'accroche des réglages propres au dédié (annonces, permissions, config
 * serveur), pas un second initialiseur de jeu.
 */
public class BingoModServer implements DedicatedServerModInitializer {

	@Override
	public void onInitializeServer() {
		BingoConstants.LOGGER.info("{} : environnement serveur dédié", BingoConstants.MOD_NAME);
	}
}
