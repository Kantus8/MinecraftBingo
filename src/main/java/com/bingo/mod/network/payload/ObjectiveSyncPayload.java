package com.bingo.mod.network.payload;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code bingo:objective_sync} — le catalogue d'affichage (`docs/06` §3.4).
 *
 * <p>Envoyé à l'entrée en jeu <strong>avant</strong> {@link BoardSyncPayload} (garde-fou 1) et
 * après chaque rechargement de datapack. Le client résout ensuite les 25 identifiants de
 * {@code board_sync} dans ce catalogue, ce qui ramène un {@code board_sync} de plusieurs
 * kilo-octets à ~300 octets.
 *
 * <p>À ~150 octets par objectif, les 45 livrés font ~7 Ko et le plafond de 1 048 576 octets
 * d'un {@code CustomPayloadS2CPacket} n'est atteint que vers 7 000 objectifs : pas de découpage
 * en lots tant qu'aucun datapack tiers n'y arrive.
 *
 * @param revision compteur du loader, présent aussi dans {@code board_sync} — c'est l'entier qui
 *                 ferme le trou de désynchronisation (garde-fou 2)
 */
public record ObjectiveSyncPayload(int revision, List<ObjectiveProjection> objectives) {

	/**
	 * Plafond d'allocation à la lecture.
	 *
	 * <p>Calé au-delà des ~7 000 objectifs qui saturent le paquet : un catalogue légitime ne peut
	 * pas dépasser ce chiffre sans être refusé à l'émission de toute façon. Ce qui est fermé ici,
	 * c'est le {@code varint} de deux milliards qui dimensionnerait l'{@link ArrayList} avant que
	 * la lecture n'échoue — la règle déjà appliquée aux 25 cases de {@code board_sync}.
	 */
	private static final int MAX_OBJECTIVES = 16_384;

	public void write(PacketByteBuf buf) {
		buf.writeInt(revision);
		buf.writeCollection(objectives, (target, projection) -> projection.write(target));
	}

	public static ObjectiveSyncPayload read(PacketByteBuf buf) {
		int revision = buf.readInt();
		List<ObjectiveProjection> objectives = buf.readCollection(
				size -> new ArrayList<>(Math.min(size, MAX_OBJECTIVES)), ObjectiveProjection::read);
		return new ObjectiveSyncPayload(revision, objectives);
	}
}
