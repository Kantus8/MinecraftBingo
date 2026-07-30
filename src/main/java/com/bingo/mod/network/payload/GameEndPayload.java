package com.bingo.mod.network.payload;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.game.GameEndReason;
import com.bingo.mod.game.team.TeamId;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code bingo:game_end} — victoire, temps écoulé ou arrêt (`docs/06` §3.1).
 *
 * @param winners     les équipes gagnantes. Plusieurs en cas de victoire partagée (`docs/05`
 *                    §1.4 cas 3), <strong>aucune</strong> en cas de match nul ou de
 *                    {@code /bingo stop} — c'est ce qui rend une raison {@code DRAW} inutile.
 * @param winningLine les 5 index de la combinaison gagnante, vide s'il n'y en a pas. Alimente la
 *                    surbrillance des 10 secondes de {@code FINISHED} (`docs/03` §4).
 * @param ranking     classement final, meilleure équipe en tête.
 */
public record GameEndPayload(
		GameEndReason reason,
		List<TeamId> winners,
		List<Integer> winningLine,
		List<ScoreUpdatePayload.Entry> ranking
) {

	public void write(PacketByteBuf buf) {
		buf.writeByte(reason.ordinal());
		buf.writeCollection(winners, (target, id) -> target.writeString(id.value()));
		buf.writeCollection(winningLine, (target, index) -> target.writeByte(index));
		new ScoreUpdatePayload(ranking).write(buf);
	}

	public static GameEndPayload read(PacketByteBuf buf) {
		GameEndReason reason = GameEndReason.byOrdinal(buf.readByte());
		// Deux plafonds d'allocation, même règle que les 25 cases de board_sync : au plus une
		// équipe gagnante par équipe, et au plus une combinaison de la taille de la grille.
		List<TeamId> winners = buf.readCollection(
				size -> new ArrayList<>(Math.min(size, BoardSyncPayload.MAX_TEAMS)),
				source -> {
					TeamId id = TeamId.parse(source.readString());
					return id == null ? new TeamId("?") : id;
				});
		List<Integer> winningLine = buf.readCollection(
				size -> new ArrayList<>(Math.min(size, BingoBoard.TILE_COUNT)),
				source -> (int) source.readByte());
		List<ScoreUpdatePayload.Entry> ranking = ScoreUpdatePayload.read(buf).entries();
		return new GameEndPayload(reason, winners, winningLine, ranking);
	}
}
