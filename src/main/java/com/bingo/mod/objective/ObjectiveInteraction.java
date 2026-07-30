package com.bingo.mod.objective;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Arrays;
import java.util.Optional;

/**
 * Effet du clic sur une case (`docs/01` §5).
 *
 * <p>Jamais lu directement depuis un objectif : la valeur par défaut dépend du type
 * (`docs/01` §5), d'où {@link Objective#effectiveInteraction()}.
 *
 * <p><strong>L'ordre est contractuel</strong> : l'interaction est encodée par son
 * {@code ordinal()} dans la projection d'affichage (`docs/06` §3.4). Ajouter à la fin
 * uniquement.
 */
public enum ObjectiveInteraction {

	/** Ouvre JEI sur la recette. */
	JEI("jei"),

	/** Affiche le pop-up de description. */
	TOOLTIP("tooltip"),

	/** Le clic ne fait rien. */
	NONE("none");

	public static final Codec<ObjectiveInteraction> CODEC = Codec.STRING.flatXmap(
			name -> byName(name)
					.map(DataResult::success)
					.orElseGet(() -> DataResult.error(() -> "Interaction inconnue : '" + name + "'")),
			interaction -> DataResult.success(interaction.name));

	private final String name;

	ObjectiveInteraction(String name) {
		this.name = name;
	}

	public String serializedName() {
		return name;
	}

	private static Optional<ObjectiveInteraction> byName(String name) {
		return Arrays.stream(values()).filter(value -> value.name.equals(name)).findFirst();
	}

	/**
	 * Décode une interaction reçue sur le réseau.
	 *
	 * @return la valeur correspondante, ou {@link #TOOLTIP} si l'ordinal est hors bornes. Le
	 *         repli le plus sûr : un tooltip est purement client et ne peut rien casser, alors
	 *         que {@code JEI} sur un objectif inconnu ouvrirait un écran vide.
	 */
	public static ObjectiveInteraction byOrdinal(int ordinal) {
		ObjectiveInteraction[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TOOLTIP;
	}
}
