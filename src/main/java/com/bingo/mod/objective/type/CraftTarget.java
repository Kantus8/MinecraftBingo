package com.bingo.mod.objective.type;

import com.bingo.mod.objective.condition.ItemMatcher;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Cible {@code bingo:craft} — le joueur <em>fabrique</em> l'item (`docs/01` §4.1).
 *
 * <p>La simple possession ne compte pas : c'est ce qui distingue ce type de {@code find}.
 */
public record CraftTarget(ItemMatcher item, boolean matchNbt) implements ObjectiveTarget {

	public static final Codec<CraftTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ItemMatcher.MAP_CODEC.forGetter(CraftTarget::item),
			Codec.BOOL.optionalFieldOf("match_nbt", false).forGetter(CraftTarget::matchNbt)
	).apply(instance, CraftTarget::new));

	@Override
	public ObjectiveType type() {
		return ObjectiveType.CRAFT;
	}
}
