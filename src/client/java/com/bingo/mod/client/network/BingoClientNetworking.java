package com.bingo.mod.client.network;

import com.bingo.mod.client.BingoClientState;
import com.bingo.mod.client.roll.RollAnimationState;
import com.bingo.mod.client.screen.BingoBoardScreen;
import com.bingo.mod.network.BingoNetworking;
import com.bingo.mod.network.payload.BoardSyncPayload;
import com.bingo.mod.network.payload.GameEndPayload;
import com.bingo.mod.network.payload.ObjectiveSyncPayload;
import com.bingo.mod.network.payload.PhasePayload;
import com.bingo.mod.network.payload.RollStartPayload;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.network.payload.TeamSyncPayload;
import com.bingo.mod.network.payload.TileUpdatePayload;
import com.bingo.mod.util.BingoConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Réception des paquets S2C et émission de l'unique paquet C2S (`docs/06` §3).
 *
 * <p><strong>Règle absolue</strong> (`docs/06` en tête) : le {@code PacketByteBuf} est décodé
 * <em>dans le handler réseau</em>, puis seuls les objets déjà décodés passent à
 * {@code client.execute(...)}. Le buffer est libéré dès le retour du handler ; y toucher depuis le
 * thread principal donne des données corrompues de façon intermittente — le pire type de bug à
 * diagnostiquer.
 *
 * <p>{@link #receive} existe précisément pour que cette règle ne dépende pas de la discipline de
 * l'auteur du prochain paquet : la seule façon d'enregistrer un récepteur ici décode au bon endroit.
 */
public final class BingoClientNetworking {

	private BingoClientNetworking() {
	}

	public static void register() {
		receive(BingoNetworking.OBJECTIVE_SYNC, ObjectiveSyncPayload::read, BingoClientState::onObjectiveSync);
		receive(BingoNetworking.BOARD_SYNC, BoardSyncPayload::read, BingoClientState::onBoardSync);
		receive(BingoNetworking.PHASE, PhasePayload::read, BingoClientState::onPhase);
		receive(BingoNetworking.TILE_UPDATE, TileUpdatePayload::read, BingoClientState::onTileUpdate);
		receive(BingoNetworking.SCORE_UPDATE, ScoreUpdatePayload::read, BingoClientState::onScoreUpdate);
		receive(BingoNetworking.GAME_END, GameEndPayload::read, BingoClientState::onGameEnd);
		receive(BingoNetworking.TEAM_SYNC, TeamSyncPayload::read,
				payload -> BingoClientState.onTeamSync(payload.teams()));
		receive(BingoNetworking.ROLL_START, RollStartPayload::read, RollAnimationState::start);

		// Charge utile vide : rien à décoder, mais l'ouverture d'écran doit quand même repasser sur
		// le thread principal.
		ClientPlayNetworking.registerGlobalReceiver(BingoNetworking.OPEN_BOARD,
				(client, handler, buf, responseSender) -> client.execute(BingoBoardScreen::open));

		// Sans ce nettoyage, le HUD de la partie précédente reste dessiné après un retour au menu,
		// puis sur le monde suivant.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BingoClientState.clear());

		BingoConstants.LOGGER.debug("Récepteurs de paquets client enregistrés");
	}

	/** Demande une resynchronisation complète (`docs/06` §3.2). */
	public static void sendRequestSync() {
		if (MinecraftClient.getInstance().getNetworkHandler() == null) {
			return;
		}
		ClientPlayNetworking.send(BingoNetworking.REQUEST_SYNC, PacketByteBufs.empty());
	}

	private static <T> void receive(Identifier channel,
	                               Function<PacketByteBuf, T> reader,
	                               Consumer<T> action) {
		ClientPlayNetworking.registerGlobalReceiver(channel, (client, handler, buf, responseSender) -> {
			T payload = reader.apply(buf);
			client.execute(() -> action.accept(payload));
		});
	}
}
