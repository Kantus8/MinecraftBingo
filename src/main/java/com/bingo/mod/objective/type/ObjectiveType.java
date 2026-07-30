package com.bingo.mod.objective.type;

import com.bingo.mod.objective.JeiRole;
import com.bingo.mod.objective.ObjectiveInteraction;
import com.bingo.mod.util.BingoConstants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Les 5 types d'objectifs (`docs/01` §4).
 *
 * <p>Chaque constante porte le codec de sa cible et ses défauts d'interaction
 * (`docs/01` §5) — la table de routage du clic vit donc ici, en un seul endroit, plutôt
 * qu'en {@code switch} dispersés dans le code client.
 *
 * <p><strong>L'ordre est contractuel</strong> : le type est encodé sur le réseau par son
 * {@code ordinal()} dans la projection d'affichage (`docs/06` §3.4). Ajouter à la fin
 * uniquement.
 */
public enum ObjectiveType {

	/** Le joueur fabrique l'item. */
	CRAFT("craft", CraftTarget.CODEC, ObjectiveInteraction.JEI, JeiRole.OUTPUT),

	/** L'item est présent dans l'inventaire. */
	FIND("find", FindTarget.CODEC, ObjectiveInteraction.JEI, JeiRole.OUTPUT),

	/** Le joueur tue l'entité. */
	KILL_MOB("kill_mob", KillMobTarget.CODEC, ObjectiveInteraction.TOOLTIP, null),

	/** Le joueur meurt de la cause indiquée. */
	DEATH("death", DeathTarget.CODEC, ObjectiveInteraction.TOOLTIP, null),

	/** Déclencheur codé en Java, adossé au registre des {@code ActionTrigger}. */
	ACTION("action", ActionTarget.CODEC, ObjectiveInteraction.TOOLTIP, null);

	private static final Map<Identifier, ObjectiveType> BY_ID = new LinkedHashMap<>();

	static {
		for (ObjectiveType type : values()) {
			BY_ID.put(type.id, type);
		}
	}

	public static final Codec<ObjectiveType> CODEC = Identifier.CODEC.flatXmap(
			id -> {
				ObjectiveType type = BY_ID.get(id);
				return type != null
						? DataResult.success(type)
						: DataResult.error(() -> "Type d'objectif inconnu : '" + id + "' (attendu : " + BY_ID.keySet() + ")");
			},
			type -> DataResult.success(type.id));

	private final Identifier id;
	private final Codec<? extends ObjectiveTarget> targetCodec;
	private final ObjectiveInteraction defaultInteraction;
	private final JeiRole defaultJeiRole;

	ObjectiveType(String path,
	              Codec<? extends ObjectiveTarget> targetCodec,
	              ObjectiveInteraction defaultInteraction,
	              JeiRole defaultJeiRole) {
		this.id = BingoConstants.id(path);
		this.targetCodec = targetCodec;
		this.defaultInteraction = defaultInteraction;
		this.defaultJeiRole = defaultJeiRole;
	}

	/** {@code bingo:craft}, {@code bingo:find}, … */
	public Identifier id() {
		return id;
	}

	/**
	 * Le codec de la cible, replié sur le champ {@code target} — voir {@link ObjectiveTarget}.
	 *
	 * <p>Le {@code .codec()} final n'est pas décoratif : {@code dispatchMap} exige un
	 * {@link Codec}, et {@code KeyDispatchCodec} de DFU ne fusionne les champs du sous-codec
	 * dans l'objet parent que si celui-ci est un {@code MapCodecCodec}. Sans lui, DFU irait
	 * chercher la cible sous une clé {@code value}, qui n'existe pas dans notre schéma.
	 */
	public Codec<? extends ObjectiveTarget> targetFieldCodec() {
		return targetCodec.fieldOf("target").codec();
	}

	/** Effet du clic par défaut pour ce type (`docs/01` §5). */
	public ObjectiveInteraction defaultInteraction() {
		return defaultInteraction;
	}

	/** Rôle JEI par défaut, vide pour les types affichant un simple tooltip. */
	public Optional<JeiRole> defaultJeiRole() {
		return Optional.ofNullable(defaultJeiRole);
	}

	/** Décode un type reçu sur le réseau. {@code null} si l'ordinal est hors bornes. */
	public static ObjectiveType byOrdinal(int ordinal) {
		ObjectiveType[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
	}
}
