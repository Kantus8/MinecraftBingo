package com.bingo.mod.network.payload;

import com.bingo.mod.game.BingoScoring;
import com.bingo.mod.game.team.TeamId;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code bingo:score_update} — les scores de toutes les équipes (`docs/06` §3.1).
 *
 * <p>Envoyé après chaque {@link TileUpdatePayload}, et jamais autrement : le score est
 * <strong>dérivé</strong> du masque de complétion (`docs/05` §2.2), donc il ne peut changer
 * qu'à la suite d'une validation. Le client ne le recalcule pas — il ne connaît pas
 * {@code points_base} ni les surcharges {@code points_base} par objectif, qui restent serveur
 * (`docs/06` §3.4).
 */
public record ScoreUpdatePayload(List<Entry> entries) {

	/** @param tileCount nombre de cases validées, pour le pied de score du HUD (`docs/03` §1) */
	public record Entry(TeamId teamId, int score, int tileCount) {

		public static Entry of(BingoScoring.Standing standing) {
			return new Entry(standing.team().id(), standing.score(), standing.tileCount());
		}

		void write(PacketByteBuf buf) {
			buf.writeString(teamId.value());
			buf.writeVarInt(score);
			buf.writeByte(tileCount);
		}

		static Entry read(PacketByteBuf buf) {
			TeamId teamId = TeamId.parse(buf.readString());
			int score = buf.readVarInt();
			int tileCount = buf.readByte();
			return new Entry(teamId == null ? new TeamId("?") : teamId, score, tileCount);
		}
	}

	/** Le classement complet, meilleure équipe en tête (`docs/05` §1.3). */
	public static ScoreUpdatePayload of(List<BingoScoring.Standing> ranking) {
		return new ScoreUpdatePayload(ranking.stream().map(Entry::of).toList());
	}

	public void write(PacketByteBuf buf) {
		buf.writeCollection(entries, (target, entry) -> entry.write(target));
	}

	public static ScoreUpdatePayload read(PacketByteBuf buf) {
		// Une entrée par équipe : même plafond d'allocation que board_sync, pour la même raison —
		// une taille lue du réseau ne dimensionne jamais une allocation sans borne.
		return new ScoreUpdatePayload(buf.readCollection(
				size -> new ArrayList<>(Math.min(size, BoardSyncPayload.MAX_TEAMS)), Entry::read));
	}
}
