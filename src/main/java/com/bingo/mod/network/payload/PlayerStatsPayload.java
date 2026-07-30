package com.bingo.mod.network.payload;

import com.bingo.mod.game.PlayerPoints;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code bingo:player_stats} — nom et points cumulés de chaque joueur connu du serveur.
 *
 * <p>Alimente le tableau des équipes du HUD, qui a besoin de deux choses que ni
 * {@code team_sync} ni {@code score_update} ne portent : le <em>nom</em> derrière chaque UUID de
 * {@link TeamSnapshot#members()}, et le total <em>individuel</em> qui survit aux manches
 * ({@link PlayerPoints}).
 *
 * <p>Émis à la connexion, sur {@code request_sync}, et à chaque case validée qui crédite un joueur —
 * soit aux mêmes instants qu'un {@code score_update}, et jamais par tick (`docs/06` §4).
 *
 * <p>Le nom part en clair plutôt que d'être résolu côté client depuis la liste des joueurs : un
 * membre déconnecté reste dans son équipe (`docs/05` §3) et n'a plus d'entrée dans cette liste, donc
 * sa ligne afficherait un UUID.
 */
public record PlayerStatsPayload(List<Entry> players) {

	/**
	 * Plafond d'allocation pour la liste lue du réseau.
	 *
	 * <p>La table grandit d'un joueur par première connexion et n'oublie personne : 512 couvre un
	 * serveur communautaire entier sans laisser une taille venue du réseau dimensionner l'allocation
	 * (même règle que les 25 cases et les 4 équipes).
	 */
	static final int MAX_PLAYERS = 512;

	/**
	 * Longueur maximale d'un nom — 16 en vanilla, 32 pour laisser de la marge.
	 *
	 * <p>Appliquée à l'<strong>écriture</strong> autant qu'à la lecture : un nom trop long lèverait
	 * côté client, sur le thread réseau, donc déconnecterait le joueur avec un message qui ne désigne
	 * rien. Tronqué, il reste lisible dans le tableau et personne ne quitte la partie.
	 */
	private static final int MAX_NAME_LENGTH = 32;

	public record Entry(UUID player, String name, int points) {

		void write(PacketByteBuf buf) {
			buf.writeUuid(player);
			buf.writeString(name);
			buf.writeVarInt(points);
		}

		static Entry read(PacketByteBuf buf) {
			UUID player = buf.readUuid();
			String name = buf.readString(MAX_NAME_LENGTH);
			int points = buf.readVarInt();
			return new Entry(player, name, Math.max(0, points));
		}
	}

	public static PlayerStatsPayload of(PlayerPoints points) {
		return new PlayerStatsPayload(points.entries().stream()
				.map(entry -> new Entry(entry.player(), clampName(entry.name()), entry.points()))
				.toList());
	}

	private static String clampName(String name) {
		return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
	}

	public void write(PacketByteBuf buf) {
		buf.writeCollection(players, (target, entry) -> entry.write(target));
	}

	public static PlayerStatsPayload read(PacketByteBuf buf) {
		return new PlayerStatsPayload(buf.readCollection(
				size -> new ArrayList<>(Math.min(size, MAX_PLAYERS)), Entry::read));
	}
}
