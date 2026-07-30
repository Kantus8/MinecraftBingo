package com.bingo.mod.objective;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import java.util.Arrays;
import java.util.Optional;

/**
 * Rôle de l'ingrédient dans la recherche JEI (`docs/01` §5).
 *
 * <p>{@code OUTPUT} = recettes qui <em>produisent</em> l'item, {@code INPUT} = recettes
 * qui le <em>consomment</em>. Le défaut dépend du type, d'où
 * {@link Objective#effectiveJeiRole()}.
 *
 * <p><strong>L'ordre est contractuel</strong> : le rôle est encodé par son {@code ordinal()}
 * dans la projection d'affichage (`docs/06` §3.4). Ajouter à la fin uniquement.
 */
public enum JeiRole {

	OUTPUT("output"),
	INPUT("input");

	public static final Codec<JeiRole> CODEC = Codec.STRING.flatXmap(
			name -> byName(name)
					.map(DataResult::success)
					.orElseGet(() -> DataResult.error(() -> "Rôle JEI inconnu : '" + name + "'")),
			role -> DataResult.success(role.name));

	private final String name;

	JeiRole(String name) {
		this.name = name;
	}

	public String serializedName() {
		return name;
	}

	private static Optional<JeiRole> byName(String name) {
		return Arrays.stream(values()).filter(value -> value.name.equals(name)).findFirst();
	}

	/**
	 * Décode un rôle reçu sur le réseau.
	 *
	 * <p>L'absence de rôle est encodée par un ordinal négatif (`docs/06` §3.4) : les types
	 * {@code KILL_MOB}, {@code DEATH} et {@code ACTION} n'en ont pas.
	 *
	 * @return le rôle, ou vide si l'ordinal est hors bornes.
	 */
	public static Optional<JeiRole> byOrdinal(int ordinal) {
		JeiRole[] values = values();
		return ordinal >= 0 && ordinal < values.length ? Optional.of(values[ordinal]) : Optional.empty();
	}
}
