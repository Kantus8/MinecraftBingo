package com.bingo.mod.objective.type;

import com.mojang.serialization.MapCodec;

/**
 * Charge utile de validation d'un objectif, spécifique à son type (`docs/01` §4).
 *
 * <p>Le JSON place le type et la cible à deux clés distinctes du même objet :
 *
 * <pre>{@code { "type": "bingo:craft", "target": { "item": "…" } }}</pre>
 *
 * <p>D'où le {@link MapCodec} dispatché ci-dessous : {@code dispatchMap} lit la clé
 * {@code type}, puis délègue à {@code type.targetFieldCodec()}, qui est le codec de la
 * cible replié sur le champ {@code target}. Les deux clés restent au même niveau, sans
 * objet intermédiaire artificiel.
 */
public sealed interface ObjectiveTarget permits CraftTarget, FindTarget, KillMobTarget, DeathTarget, ActionTarget {

	MapCodec<ObjectiveTarget> MAP_CODEC =
			ObjectiveType.CODEC.dispatchMap("type", ObjectiveTarget::type, ObjectiveType::targetFieldCodec);

	/** Le type dont cette cible est la charge utile. Doit rester cohérent avec le codec. */
	ObjectiveType type();
}
