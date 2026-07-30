package com.bingo.mod.network.payload;

import com.bingo.mod.board.BingoBoard;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code bingo:roll_start} — l'unique paquet de l'animation de tirage (`docs/04` §1, tâche 4.1).
 *
 * <p><strong>Un seul paquet pour toute l'animation.</strong> Chaque client rejoue les 3 secondes
 * localement à partir de {@link #seed}, donc tous voient exactement la même séquence d'icônes
 * défiler sans qu'un octet ne circule pendant l'animation.
 *
 * <p>Compromis assumé de `docs/04` §1 : le client connaît la carte finale dès {@code t=0} et un
 * joueur motivé pourrait la lire avant la fin. L'alternative — n'envoyer la carte qu'à
 * {@code t=3000} — rend le reveal dépendant de la latence, ce qui casse le moment collectif. Entre
 * amis, la triche théorique coûte moins cher qu'un reveal désynchronisé.
 *
 * @param startTimeMs horloge <em>serveur</em> au départ de l'animation. Utilisable seulement quand
 *                    les deux horloges sont la même — solo et LAN, où l'écart est nul — d'où le
 *                    garde-fou de {@code RollAnimationState} qui ne s'en sert que si la valeur
 *                    tombe dans la fenêtre de l'animation.
 * @param durationMs  durée réelle, dérivée de {@code timings.roll_ticks}. Transportée plutôt que
 *                    recopiée en dur côté client : c'est ce qui permet à l'animation de se
 *                    recalculer proportionnellement quand un datapack change {@code roll_ticks},
 *                    au lieu de partir en incohérence comme le redoutait `docs/04` §1.
 */
public record RollStartPayload(
		List<Identifier> tiles,
		long seed,
		long startTimeMs,
		int durationMs
) {

	public void write(PacketByteBuf buf) {
		buf.writeCollection(tiles, PacketByteBuf::writeIdentifier);
		buf.writeLong(seed);
		buf.writeLong(startTimeMs);
		buf.writeVarInt(durationMs);
	}

	public static RollStartPayload read(PacketByteBuf buf) {
		// Borné à 25, comme board_sync : une taille lue du réseau ne dimensionne jamais une
		// allocation sans plafond.
		List<Identifier> tiles = buf.readCollection(
				size -> new ArrayList<>(Math.min(size, BingoBoard.TILE_COUNT)),
				PacketByteBuf::readIdentifier);
		return new RollStartPayload(tiles, buf.readLong(), buf.readLong(), buf.readVarInt());
	}
}
