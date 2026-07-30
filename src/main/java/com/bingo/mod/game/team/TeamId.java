package com.bingo.mod.game.team;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Identifiant d'équipe : une chaîne courte, ex. {@code red} (`docs/05` §3).
 *
 * <p>Un record plutôt qu'un {@code String} nu : les paquets, les commandes et la persistance
 * manipulent tous des identifiants d'équipe à côté d'identifiants d'objectifs et de noms de
 * joueurs, et le compilateur ne peut distinguer trois {@code String} entre eux. Le coût est
 * d'une allocation par équipe, soit quatre par partie.
 *
 * <p>La normalisation en minuscules est faite à la construction : {@code Red} et {@code red}
 * doivent désigner la même équipe, sinon {@code /bingo team join Red} crée un doublon
 * invisible.
 */
public record TeamId(String value) implements Comparable<TeamId> {

	/** Longueur maximale — assez pour un nom lisible, assez court pour tenir dans le HUD. */
	public static final int MAX_LENGTH = 24;

	public TeamId {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("Identifiant d'équipe vide");
		}
	}

	/**
	 * Normalise et valide un identifiant saisi.
	 *
	 * @return l'identifiant, ou {@code null} si la forme est refusée — le retour nul plutôt
	 *         qu'une exception parce que l'appelant est presque toujours une commande, qui doit
	 *         répondre par une erreur Brigadier traduite (`docs/05` §4.4).
	 */
	public static @Nullable TeamId parse(String raw) {
		if (raw == null) {
			return null;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
			return null;
		}
		// Le même jeu de caractères qu'un chemin d'Identifier : c'est ce qui garantit qu'un
		// identifiant d'équipe reste utilisable en clé NBT et en argument de commande sans
		// guillemets.
		for (int i = 0; i < normalized.length(); i++) {
			char character = normalized.charAt(i);
			boolean allowed = (character >= 'a' && character <= 'z')
					|| (character >= '0' && character <= '9')
					|| character == '_' || character == '-';
			if (!allowed) {
				return null;
			}
		}
		return new TeamId(normalized);
	}

	@Override
	public int compareTo(TeamId other) {
		return value.compareTo(other.value);
	}

	@Override
	public String toString() {
		return value;
	}
}
