package com.bingo.mod.objective.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Cible {@code bingo:action} — la soupape du système (`docs/01` §4.5).
 *
 * <p>{@code trigger} est un ID du registre des {@code ActionTrigger} (lot 2, tâche 2.8), et
 * {@code params} une charge utile libre que seul le trigger interprète.
 *
 * <p>{@code params} est stocké en {@link NbtCompound} plutôt qu'en {@code JsonObject} :
 * {@link NbtCompound#CODEC} sait décoder depuis n'importe quel {@code DynamicOps}, donc
 * depuis du JSON, et le résultat se relit avec les accesseurs typés du NBT sans dépendre de
 * Gson dans le code de jeu.
 *
 * <p>Volontairement en {@link Optional} plutôt qu'avec un défaut : un {@code NbtCompound}
 * vide passé en valeur par défaut à un codec serait une instance <em>mutable partagée</em>
 * entre tous les objectifs sans {@code params}.
 */
public record ActionTarget(Identifier trigger, Optional<NbtCompound> params) implements ObjectiveTarget {

	public static final Codec<ActionTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.fieldOf("trigger").forGetter(ActionTarget::trigger),
			NbtCompound.CODEC.optionalFieldOf("params").forGetter(ActionTarget::params)
	).apply(instance, ActionTarget::new));

	/** Paramètres du trigger, jamais {@code null} — un compound neuf si absent. */
	public NbtCompound paramsOrEmpty() {
		return params.orElseGet(NbtCompound::new);
	}

	@Override
	public ObjectiveType type() {
		return ObjectiveType.ACTION;
	}
}
