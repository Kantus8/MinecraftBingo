package com.bingo.mod;

import com.bingo.mod.command.BingoCommand;
import com.bingo.mod.config.BingoServerConfig;
import com.bingo.mod.data.BingoData;
import com.bingo.mod.game.BingoFreeze;
import com.bingo.mod.game.BingoGame;
import com.bingo.mod.game.detect.ActionTriggers;
import com.bingo.mod.game.detect.BingoDetectors;
import com.bingo.mod.integration.voicechat.BingoVoiceManager;
import com.bingo.mod.network.handler.BingoServerNetworking;
import com.bingo.mod.registry.BingoSounds;
import com.bingo.mod.util.BingoConstants;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Entrypoint {@code main} — chargé sur le client comme sur le serveur dédié.
 *
 * <p>Contient tout l'enregistrement commun : cycle de vie de l'état de partie, réseau,
 * détecteurs, sons et commandes. Ne doit jamais référencer une classe de {@code src/client}
 * ({@code splitEnvironmentSourceSets()} le refuse à la compilation, docs/06 §5).
 */
public class BingoMod implements ModInitializer {

	@Override
	public void onInitialize() {
		BingoConstants.LOGGER.info("Initialisation de {} ({})", BingoConstants.MOD_NAME, BingoConstants.MOD_ID);

		// Avant tout le reste : ces valeurs sont le repli de dernier recours de la précédence de
		// `docs/01` §8, donc elles doivent être en mémoire avant qu'une première manche ne les lise.
		// Le dossier config existe dès le chargement du mod, aucun monde n'est requis (contrairement
		// à la persistance de partie, accrochée seulement à SERVER_STARTED).
		BingoServerConfig.load();

		BingoSounds.register();

		ServerLifecycleEvents.SERVER_STARTING.register(BingoGame::of);

		// La persistance ne peut s'accrocher qu'ici : SERVER_STARTING précède le chargement des
		// mondes et des datapacks, donc aucun identifiant de case ne serait résoluble (docs/06 §2).
		ServerLifecycleEvents.SERVER_STARTED.register(server -> BingoGame.of(server).attachPersistence());

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			// Avant le détachement : unbind lâche des références à des groupes vocaux qui n'ont plus
			// de sens hors de ce serveur. Sans lui, un second monde solo ouvert dans la même session
			// hériterait des groupes du premier (`docs/02` §3.4).
			BingoVoiceManager.get().unbind();
			BingoGame.detach(server);
		});

		// SERVER_DATA et non CLIENT_RESOURCES : ce sont des données de datapack, rechargées par
		// /reload et donc par /bingo reload (docs/06 §3.4).
		BingoData.registerLoaders();

		// Après chaque rechargement — /reload vanilla compris, pas seulement /bingo reload : le
		// client doit recevoir le catalogue à jour, sinon sa révision diverge de celle du serveur
		// et il resynchronise en boucle (docs/06 §3.4 garde-fou 2).
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			// Diffusion même sur échec, et c'est délibéré : les reload listeners du mod ont déjà
			// remplacé leur contenu et incrémenté la révision avant que le rechargement ne casse.
			// Se taire laisserait chaque client sur une révision périmée jusqu'au prochain
			// board_sync, alors que le serveur, lui, a bel et bien changé de catalogue.
			if (!success) {
				BingoConstants.LOGGER.warn(
						"Rechargement de datapack en échec — le catalogue du mod est malgré tout diffusé");
			}

			ActionTriggers.warnUnknownTriggers(BingoData.OBJECTIVES.all());

			// Ordre : catalogue, puis réalignement de la carte, puis carte (docs/06 §3.4 garde-fou
			// 1). Le réalignement peut émettre des tile_update, qui doivent tomber sur un client
			// dont le catalogue est déjà à jour.
			BingoGame game = BingoGame.of(server);
			BingoServerNetworking.broadcastObjectiveSync(server);
			game.onDataReload();
			BingoServerNetworking.broadcastBoardSync(game);
		});

		BingoServerNetworking.register();
		BingoDetectors.register();

		// Second garde-fou de `docs/04` §5 : un joueur qui se déconnecte pendant les 3 s de ROLLING
		// doit revenir mobile, quelle que soit la phase à son retour.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				BingoFreeze.reapply(null, handler.player));

		ServerTickEvents.END_SERVER_TICK.register(server -> BingoGame.of(server).tick());

		CommandRegistrationCallback.EVENT.register(BingoCommand::register);
	}
}
