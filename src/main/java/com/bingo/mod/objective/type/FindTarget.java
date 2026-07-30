package com.bingo.mod.objective.type;

import com.bingo.mod.objective.condition.ItemMatcher;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Cible {@code bingo:find} — l'item est <em>présent dans l'inventaire</em> (`docs/01` §4.2).
 *
 * <p>Même forme que {@link CraftTarget}, mais type distinct : la détection est un scan
 * périodique et non un événement, et l'index inversé du lot 2 les sépare (`docs/06` §6).
 * Fusionner les deux records ferait perdre cette distinction au niveau du dispatch.
 */
public record FindTarget(ItemMatcher item, boolean matchNbt) implements ObjectiveTarget {

	public static final Codec<FindTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ItemMatcher.MAP_CODEC.forGetter(FindTarget::item),
			Codec.BOOL.optionalFieldOf("match_nbt", false).forGetter(FindTarget::matchNbt)
	).apply(instance, FindTarget::new));

	@Override
	public ObjectiveType type() {
		return ObjectiveType.FIND;
	}
}
