package com.bingo.mod.network.payload;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code bingo:team_sync} — la composition des équipes a changé (`docs/06` §3.1).
 *
 * <p>Émis sur toute modification issue du sous-arbre {@code /bingo team} (`docs/05` §4.1), et à
 * l'entrée en jeu à la suite de {@code board_sync}.
 *
 * <p>Porte des {@link TeamSnapshot} complets, complétion incluse : voir la note d'écart en tête
 * de ce record.
 */
public record TeamSyncPayload(List<TeamSnapshot> teams) {

	public void write(PacketByteBuf buf) {
		buf.writeCollection(teams, (target, team) -> team.write(target));
	}

	public static TeamSyncPayload read(PacketByteBuf buf) {
		return new TeamSyncPayload(buf.readCollection(ArrayList::new, TeamSnapshot::read));
	}
}
