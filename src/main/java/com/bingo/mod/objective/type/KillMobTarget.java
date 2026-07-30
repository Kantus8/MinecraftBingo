package com.bingo.mod.objective.type;

import com.bingo.mod.objective.condition.EntityMatcher;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.util.Optional;

/**
 * Cible {@code bingo:kill_mob} — le joueur tue l'entité (`docs/01` §4.3).
 *
 * @param requireWeapon arme exigée dans la main principale, optionnel
 * @param maxDistance   distance minimale de la mise à mort en blocs, optionnel
 */
public record KillMobTarget(EntityMatcher entity, Optional<Item> requireWeapon, Optional<Double> maxDistance)
		implements ObjectiveTarget {

	public static final Codec<KillMobTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			EntityMatcher.MAP_CODEC.forGetter(KillMobTarget::entity),
			Registries.ITEM.getCodec().optionalFieldOf("require_weapon").forGetter(KillMobTarget::requireWeapon),
			Codec.DOUBLE.optionalFieldOf("max_distance").forGetter(KillMobTarget::maxDistance)
	).apply(instance, KillMobTarget::new));

	@Override
	public ObjectiveType type() {
		return ObjectiveType.KILL_MOB;
	}
}
