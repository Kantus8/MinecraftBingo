package com.bingo.mod.network.payload;

import com.bingo.mod.game.team.TeamId;
import net.minecraft.network.PacketByteBuf;

/**
 * {@code bingo:tile_update} — une case a progressé ou a été validée (`docs/06` §3.1).
 *
 * <p>Envoyé à <strong>tous</strong> les joueurs, y compris ceux des équipes adverses : le HUD
 * révèle la progression adverse, c'est un pilier de design (`docs/00` §2). Le filtrage éventuel
 * par {@code reveal_opponent_progress} est un choix d'affichage, fait côté client depuis
 * {@link BoardSyncPayload#revealOpponentProgress()}.
 *
 * @param completedAtMs instant serveur de la validation, {@code 0} si la case n'est pas validée.
 *                      Sert au journal et aux égalités (`docs/05` §1.4) — jamais pris côté
 *                      client, sinon la latence décide du vainqueur.
 */
public record TileUpdatePayload(
		TeamId teamId,
		int index,
		int progress,
		boolean completed,
		long completedAtMs
) {

	public void write(PacketByteBuf buf) {
		buf.writeString(teamId.value());
		buf.writeByte(index);
		buf.writeVarInt(progress);
		buf.writeBoolean(completed);
		buf.writeLong(completedAtMs);
	}

	public static TileUpdatePayload read(PacketByteBuf buf) {
		TeamId teamId = TeamId.parse(buf.readString());
		int index = buf.readByte();
		int progress = buf.readVarInt();
		boolean completed = buf.readBoolean();
		long completedAtMs = buf.readLong();
		return new TileUpdatePayload(teamId == null ? new TeamId("?") : teamId,
				index, progress, completed, completedAtMs);
	}
}
