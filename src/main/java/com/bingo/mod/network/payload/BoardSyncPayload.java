package com.bingo.mod.network.payload;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.data.Ruleset;
import com.bingo.mod.game.phase.GamePhase;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code bingo:board_sync} — l'état complet de la partie (`docs/06` §3.1).
 *
 * <p>Envoyé à l'entrée en jeu, sur {@code /bingo card}, sur {@code request_sync} et après un
 * rechargement de datapack. Ne transporte que les <strong>25 identifiants</strong> des cases,
 * jamais les objectifs : le client les résout dans le catalogue reçu une fois via
 * {@link ObjectiveSyncPayload} (`docs/06` §3.3).
 *
 * <p>{@code tiles} est vide hors manche — c'est ce qui dit au HUD de se masquer (`docs/03` §4).
 *
 * @param elapsedMs               temps de jeu déjà écoulé, chrono de pause déduit ; le client
 *                                extrapole depuis <em>sa</em> réception plutôt que de comparer
 *                                deux horloges système (`docs/06` §4)
 * @param revealOpponentProgress  {@code ruleset.reveal_opponent_progress} — le client en a besoin
 *                                pour décider du pied de score, dont la hauteur change le layout
 *                                (`docs/03` §1)
 * @param pointsBase              {@code ruleset.points_base}, pour la ligne « Niveau 3 · 400 pts »
 *                                du tooltip (`docs/03` §3.3). `docs/06` §3.4 range {@code points_base}
 *                                parmi les champs qui restent serveur, mais cette liste porte sur la
 *                                projection d'un <em>objectif</em> : sans la base du ruleset, la ligne
 *                                de points exigée par `docs/03` §3.3 est inaffichable. Une surcharge
 *                                {@code points_base} par objectif, elle, reste invisible du client —
 *                                aucun objectif livré n'en pose.
 * @param winConditions           {@code ruleset.win_conditions}. Ajout du lot 4 : sans elles, la
 *                                bordure dorée du 4/5 (`docs/03` §2, tâche 4.9) mettrait en avant
 *                                une diagonale sur un ruleset qui les a désactivées — c'est-à-dire
 *                                qu'elle annoncerait au joueur une victoire impossible.
 */
public record BoardSyncPayload(
		int revision,
		GamePhase phase,
		List<Identifier> tiles,
		long rollSeed,
		Optional<Identifier> difficultyId,
		Optional<Identifier> rulesetId,
		int timeLimitSeconds,
		int remainingSeconds,
		long elapsedMs,
		boolean revealOpponentProgress,
		int pointsBase,
		List<Ruleset.WinCondition> winConditions,
		List<TeamSnapshot> teams
) {

	/**
	 * Plafond d'allocation pour la liste d'équipes lue du réseau.
	 *
	 * <p>{@code ruleset.max_teams} vaut 4 par défaut et la palette de couleurs en compte autant ;
	 * 64 laisse toute la marge qu'un datapack peut réclamer tout en fermant la porte à un
	 * {@code varint} de deux milliards qui dimensionnerait l'{@link ArrayList} avant même que la
	 * lecture n'échoue. La règle est celle déjà appliquée aux 25 cases, étendue à toutes les
	 * collections du protocole.
	 */
	static final int MAX_TEAMS = 64;

	public void write(PacketByteBuf buf) {
		buf.writeInt(revision);
		buf.writeByte(phase.ordinal());
		buf.writeCollection(tiles, PacketByteBuf::writeIdentifier);
		buf.writeLong(rollSeed);
		buf.writeOptional(difficultyId, PacketByteBuf::writeIdentifier);
		buf.writeOptional(rulesetId, PacketByteBuf::writeIdentifier);
		buf.writeVarInt(timeLimitSeconds);
		buf.writeVarInt(remainingSeconds);
		buf.writeLong(elapsedMs);
		buf.writeBoolean(revealOpponentProgress);
		buf.writeVarInt(pointsBase);
		buf.writeCollection(winConditions, (target, condition) -> target.writeByte(condition.ordinal()));
		buf.writeCollection(teams, (target, team) -> team.write(target));
	}

	public static BoardSyncPayload read(PacketByteBuf buf) {
		int revision = buf.readInt();
		GamePhase phase = GamePhase.byOrdinal(buf.readByte());
		// Borné à 25 : une taille lue depuis le réseau ne doit jamais dimensionner une allocation
		// sans plafond, même sur un canal qu'on émet soi-même.
		List<Identifier> tiles = buf.readCollection(
				size -> new ArrayList<>(Math.min(size, BingoBoard.TILE_COUNT)),
				PacketByteBuf::readIdentifier);
		long rollSeed = buf.readLong();
		Optional<Identifier> difficultyId = buf.readOptional(PacketByteBuf::readIdentifier);
		Optional<Identifier> rulesetId = buf.readOptional(PacketByteBuf::readIdentifier);
		int timeLimitSeconds = buf.readVarInt();
		int remainingSeconds = buf.readVarInt();
		long elapsedMs = buf.readLong();
		boolean revealOpponentProgress = buf.readBoolean();
		int pointsBase = buf.readVarInt();
		// Un ordinal inconnu vient d'un serveur plus récent : la forme est ignorée plutôt que
		// repliée sur LINE, qui ferait miroiter une combinaison que le serveur ne validera pas.
		List<Ruleset.WinCondition> winConditions = buf.readCollection(
						size -> new ArrayList<Optional<Ruleset.WinCondition>>(
								Math.min(size, Ruleset.WinCondition.values().length)),
						source -> winCondition(source.readByte()))
				.stream().flatMap(Optional::stream).toList();
		List<TeamSnapshot> teams = buf.readCollection(
				size -> new ArrayList<>(Math.min(size, MAX_TEAMS)), TeamSnapshot::read);

		return new BoardSyncPayload(revision, phase, tiles, rollSeed, difficultyId, rulesetId,
				timeLimitSeconds, remainingSeconds, elapsedMs, revealOpponentProgress, pointsBase,
				winConditions, teams);
	}

	private static Optional<Ruleset.WinCondition> winCondition(int ordinal) {
		Ruleset.WinCondition[] values = Ruleset.WinCondition.values();
		return ordinal >= 0 && ordinal < values.length ? Optional.of(values[ordinal]) : Optional.empty();
	}
}
