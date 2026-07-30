package com.bingo.mod.game;

import com.bingo.mod.util.BingoConstants;

/**
 * Pourquoi une manche s'est arrêtée (`docs/06` §3.1, charge utile de {@code bingo:game_end}).
 *
 * <p><strong>L'ordre est contractuel</strong> : encodé par {@code writeByte(ordinal())}.
 *
 * <p>Le match nul de `docs/05` §1.3 n'est pas une raison distincte : c'est un {@link #TIME}
 * dont la liste de gagnants est vide. Ajouter une constante {@code DRAW} obligerait tous les
 * lecteurs à traiter deux cas là où « aucun gagnant » suffit à décrire la situation.
 */
public enum GameEndReason {

	/** Une équipe a complété une combinaison (`docs/05` §1.1). */
	LINE("line"),

	/** {@code time_limit_seconds} écoulé (`docs/05` §1.3). */
	TIME("time"),

	/** {@code /bingo stop} — arrêt sans vainqueur (`docs/05` §4.1). */
	STOP("stop");

	private final String key;

	GameEndReason(String key) {
		this.key = key;
	}

	/** Clé de traduction de l'annonce de fin, {@code bingo.message.end.<raison>}. */
	public String translationKey() {
		return BingoConstants.key("message.end." + key);
	}

	/** @return la raison, ou {@link #STOP} si l'ordinal est hors bornes. */
	public static GameEndReason byOrdinal(int ordinal) {
		GameEndReason[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : STOP;
	}
}
