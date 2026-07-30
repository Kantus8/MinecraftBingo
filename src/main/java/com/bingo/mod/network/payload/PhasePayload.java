package com.bingo.mod.network.payload;

import com.bingo.mod.game.phase.GamePhase;
import net.minecraft.network.PacketByteBuf;

/**
 * {@code bingo:phase} — une transition de phase (`docs/06` §3.1).
 *
 * <p><strong>Aucun paquet par tick</strong> (`docs/06` §4) : ce paquet est le seul point de
 * resynchronisation du chrono, envoyé une fois par transition. Le client extrapole entre deux
 * transitions, et une dérive de quelques centaines de millisecondes sur l'affichage d'un chrono
 * est invisible — contre 20 paquets/seconde/joueur pour l'éviter.
 *
 * <p>Ce sont des <em>durées</em> qui partent, pas un {@code System.currentTimeMillis()} serveur :
 * comparer deux horloges système donnerait un décalage arbitraire, quand une durée additionnée à
 * l'instant de réception client est juste par construction.
 *
 * @param elapsedMs        temps de jeu écoulé, chrono de pause déduit
 * @param remainingSeconds temps restant avant la fin de la manche
 * @param phaseEndsInMs    durée restante de la phase courante pour {@code ROLLING} et
 *                         {@code COUNTDOWN}, {@code -1} pour les phases sans échéance
 */
public record PhasePayload(GamePhase phase, long elapsedMs, int remainingSeconds, int phaseEndsInMs) {

	/** Marqueur de phase sans échéance (`LOBBY`, `RUNNING`, `PAUSED`, `FINISHED`). */
	public static final int NO_DEADLINE = -1;

	public void write(PacketByteBuf buf) {
		buf.writeByte(phase.ordinal());
		buf.writeLong(elapsedMs);
		buf.writeVarInt(remainingSeconds);
		buf.writeVarInt(phaseEndsInMs + 1);
	}

	public static PhasePayload read(PacketByteBuf buf) {
		GamePhase phase = GamePhase.byOrdinal(buf.readByte());
		long elapsedMs = buf.readLong();
		int remainingSeconds = buf.readVarInt();
		// Décalé de 1 à l'écriture : un VarInt encode -1 sur 5 octets, contre 1 octet pour 0.
		int phaseEndsInMs = buf.readVarInt() - 1;
		return new PhasePayload(phase, elapsedMs, remainingSeconds, phaseEndsInMs);
	}
}
