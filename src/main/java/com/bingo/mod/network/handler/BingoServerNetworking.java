package com.bingo.mod.network.handler;

import com.bingo.mod.data.BingoData;
import com.bingo.mod.game.BingoFreeze;
import com.bingo.mod.game.BingoGame;
import com.bingo.mod.network.BingoNetworking;
import com.bingo.mod.network.payload.BoardSyncPayload;
import com.bingo.mod.network.payload.GameEndPayload;
import com.bingo.mod.network.payload.ObjectiveProjection;
import com.bingo.mod.network.payload.ObjectiveSyncPayload;
import com.bingo.mod.network.payload.PhasePayload;
import com.bingo.mod.network.payload.RollStartPayload;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.network.payload.TeamSyncPayload;
import com.bingo.mod.network.payload.TileUpdatePayload;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.util.BingoConstants;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Émission des paquets S2C et réception de l'unique paquet C2S (`docs/06` §3, §4).
 *
 * <p><strong>Un buffer neuf par destinataire.</strong> En 1.20.1,
 * {@code CustomPayloadS2CPacket#write} recopie le {@code PacketByteBuf} avec
 * {@code writeBytes(ByteBuf)}, ce qui <em>avance l'index de lecture de la source</em> : réutiliser
 * un même buffer pour huit joueurs enverrait le paquet correct au premier et des paquets vides
 * aux sept autres. Le surcoût d'encodage est de quelques kilo-octets sur des événements qui
 * arrivent quelques dizaines de fois par manche.
 */
public final class BingoServerNetworking {

	/**
	 * Délai minimal entre deux {@code request_sync} d'un même joueur.
	 *
	 * <p>Le client s'étrangle déjà à 2 s ({@code BingoClientState}), donc un client légitime ne
	 * rencontre jamais ce plafond : il est là pour celui qui ne l'est pas. La réponse à un
	 * {@code request_sync} pèse le catalogue entier — plusieurs kilo-octets — sur l'unique canal
	 * C2S du mod (`docs/06` §3.2) ; sans cooldown, une boucle côté client fait émettre au serveur
	 * autant de kilo-octets qu'elle envoie d'octets.
	 */
	private static final long RESYNC_COOLDOWN_MS = 1_000L;

	/**
	 * Dernier {@code request_sync} servi, par joueur.
	 *
	 * <p>Une {@link HashMap} nue suffit : toutes les écritures passent par
	 * {@code server.execute(...)} ou par un événement de connexion, donc par le thread serveur.
	 */
	private static final Map<UUID, Long> lastResyncMs = new HashMap<>();

	private BingoServerNetworking() {
	}

	public static void register() {
		// Ordre exigé par le garde-fou 1 de `docs/06` §3.4 : le catalogue AVANT la carte. Un
		// board_sync reçu avant son catalogue ne peut rien afficher, et l'ordre est garanti sur
		// une même connexion — il suffit de séquencer les envois.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			BingoGame game = BingoGame.of(server);
			sendObjectiveSync(player);
			sendBoardSync(player, game);
			sendTeamSync(player, game);

			// Garde-fou de `docs/04` §5 : aligner le gel sur la phase à l'entrée en jeu. C'est le
			// point de nettoyage qui compte réellement — au démarrage du serveur, il n'y a encore
			// personne à balayer.
			BingoFreeze.reapply(game, player);
		});

		ServerPlayNetworking.registerGlobalReceiver(BingoNetworking.REQUEST_SYNC,
				(server, player, handler, buf, responseSender) -> {
					// Charge utile vide : rien à décoder, mais on repasse quand même sur le thread
					// serveur avant de toucher à l'état de partie (`docs/06` en tête).
					server.execute(() -> {
						if (!acceptResync(player)) {
							return;
						}
						BingoGame game = BingoGame.of(server);
						sendObjectiveSync(player);
						sendBoardSync(player, game);
						sendTeamSync(player, game);
					});
				});

		// Sans ce nettoyage, la table de cooldown garderait une entrée par joueur ayant demandé
		// une resynchronisation depuis le démarrage du serveur.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				lastResyncMs.remove(handler.player.getUuid()));
	}

	/** @return {@code false} si ce joueur a déjà été servi il y a moins de {@link #RESYNC_COOLDOWN_MS}. */
	private static boolean acceptResync(ServerPlayerEntity player) {
		long now = System.currentTimeMillis();
		Long previous = lastResyncMs.get(player.getUuid());
		if (previous != null && now - previous < RESYNC_COOLDOWN_MS) {
			BingoConstants.LOGGER.debug("request_sync ignoré pour {} : cooldown", player.getGameProfile().getName());
			return false;
		}
		lastResyncMs.put(player.getUuid(), now);
		return true;
	}

	// ── Envois ciblés ─────────────────────────────────────────────────────────

	public static void sendObjectiveSync(ServerPlayerEntity player) {
		send(player, BingoNetworking.OBJECTIVE_SYNC, buf -> objectiveSync().write(buf));
	}

	public static void sendBoardSync(ServerPlayerEntity player, BingoGame game) {
		send(player, BingoNetworking.BOARD_SYNC, buf -> game.boardSync().write(buf));
	}

	public static void sendTeamSync(ServerPlayerEntity player, BingoGame game) {
		send(player, BingoNetworking.TEAM_SYNC, buf -> game.teamSync().write(buf));
	}

	/** {@code /bingo card} : ouvre l'écran chez l'émetteur seul (`docs/05` §4.2). */
	public static void sendOpenBoard(ServerPlayerEntity player) {
		send(player, BingoNetworking.OPEN_BOARD, buf -> {
		});
	}

	// ── Diffusions ────────────────────────────────────────────────────────────

	/** Après un rechargement de datapack : catalogue puis carte (`docs/06` §4). */
	public static void broadcastObjectiveSync(MinecraftServer server) {
		ObjectiveSyncPayload payload = objectiveSync();
		broadcast(server, BingoNetworking.OBJECTIVE_SYNC, payload::write);
		BingoConstants.LOGGER.debug("Catalogue d'objectifs diffusé : {} entrées, révision {}",
				payload.objectives().size(), payload.revision());
	}

	/**
	 * Les projections sont construites <strong>une fois</strong>, hors de la boucle d'envoi.
	 *
	 * <p>Le buffer, lui, reste par destinataire (voir l'en-tête de classe) : c'est l'encodage qui
	 * doit être répété, pas le calcul. Un {@code board_sync} recopie 25 identifiants et clone un
	 * tableau d'avancement par équipe, un {@code score_update} redérive tout le classement depuis
	 * les masques — le faire huit fois pour huit joueurs était du travail rendu à personne.
	 */
	public static void broadcastBoardSync(BingoGame game) {
		BoardSyncPayload payload = game.boardSync();
		broadcast(game.server(), BingoNetworking.BOARD_SYNC, payload::write);
	}

	public static void broadcastPhase(BingoGame game) {
		PhasePayload payload = game.phasePayload();
		broadcast(game.server(), BingoNetworking.PHASE, payload::write);
	}

	public static void broadcastTeamSync(BingoGame game) {
		TeamSyncPayload payload = game.teamSync();
		broadcast(game.server(), BingoNetworking.TEAM_SYNC, payload::write);
	}

	public static void broadcastTileUpdate(BingoGame game, TileUpdatePayload payload) {
		broadcast(game.server(), BingoNetworking.TILE_UPDATE, payload::write);
	}

	public static void broadcastScoreUpdate(BingoGame game) {
		ScoreUpdatePayload payload = game.scoreUpdate();
		broadcast(game.server(), BingoNetworking.SCORE_UPDATE, payload::write);
	}

	public static void broadcastGameEnd(BingoGame game, GameEndPayload payload) {
		broadcast(game.server(), BingoNetworking.GAME_END, payload::write);
	}

	/**
	 * Départ de l'animation de tirage (`docs/04` §1).
	 *
	 * <p>Un seul paquet, puis plus rien pendant les 3 secondes : chaque client rejoue la séquence
	 * depuis la graine. C'est tout l'intérêt du déterminisme par seed.
	 */
	public static void broadcastRollStart(BingoGame game, long durationMs) {
		RollStartPayload payload = new RollStartPayload(
				game.tiles().stream().map(Objective::id).toList(),
				game.rollSeed(),
				System.currentTimeMillis(),
				(int) durationMs);
		broadcast(game.server(), BingoNetworking.ROLL_START, payload::write);
	}

	// ── Plomberie ─────────────────────────────────────────────────────────────

	private static ObjectiveSyncPayload objectiveSync() {
		return new ObjectiveSyncPayload(
				BingoData.revision(),
				BingoData.OBJECTIVES.all().stream().map(ObjectiveProjection::of).toList());
	}

	/**
	 * Encode et envoie, en refusant un paquet trop gros pour la liaison.
	 *
	 * <p>Le plafond de {@link BingoNetworking#MAX_PAYLOAD_SIZE} n'est pas contrôlé à l'écriture par
	 * Minecraft : un paquet trop gros part, et c'est le client qui lève à la lecture — donc qui se
	 * fait déconnecter, avec un message qui ne dit ni le canal ni la taille. Ne pas l'envoyer laisse
	 * au contraire le joueur en jeu, avec des cases placeholder que le garde-fou 3 de `docs/06`
	 * §3.4 prévoit déjà, et une ligne de log qui nomme le coupable.
	 *
	 * @return {@code false} si le paquet a été refusé
	 */
	private static boolean send(ServerPlayerEntity player, Identifier channel, Consumer<PacketByteBuf> writer) {
		PacketByteBuf buf = PacketByteBufs.create();
		writer.accept(buf);

		int size = buf.readableBytes();
		if (size > BingoNetworking.MAX_PAYLOAD_SIZE) {
			BingoConstants.LOGGER.error(
					"Paquet '{}' refusé : {} octets pour un plafond de {} — paquet non envoyé",
					channel, size, BingoNetworking.MAX_PAYLOAD_SIZE);
			buf.release();
			return false;
		}

		ServerPlayNetworking.send(player, channel, buf);
		return true;
	}

	private static void broadcast(MinecraftServer server, Identifier channel, Consumer<PacketByteBuf> writer) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			// Un refus ne dépend que de la charge utile, jamais du destinataire : ce qui a été
			// refusé pour le premier le sera pour les huit suivants, et une seule ligne de log
			// suffit à le dire.
			if (!send(player, channel, writer)) {
				return;
			}
		}
	}
}
