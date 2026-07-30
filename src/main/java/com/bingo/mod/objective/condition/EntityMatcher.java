package com.bingo.mod.objective.condition;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;

/**
 * Prédicat sur une entité : soit un type précis, soit un tag.
 *
 * <p>Sert à la cible {@code kill_mob} (`docs/01` §4.3), où le JSON accepte
 * {@code "entity_type": "minecraft:creeper"} ou {@code "tag": "#minecraft:skeletons"}.
 */
public sealed interface EntityMatcher {

	/** Codec « à plat » — même raison que {@link ItemMatcher#MAP_CODEC}. */
	MapCodec<EntityMatcher> MAP_CODEC = Codec.mapEither(
					Registries.ENTITY_TYPE.getCodec().fieldOf("entity_type"),
					TagKey.codec(RegistryKeys.ENTITY_TYPE).fieldOf("tag"))
			.xmap(
					either -> either.map(OfType::new, OfTag::new),
					matcher -> matcher instanceof OfType ofType
							? Either.left(ofType.type())
							: Either.right(((OfTag) matcher).tag()));

	Codec<EntityMatcher> CODEC = MAP_CODEC.codec();

	boolean matches(Entity entity);

	/** Un type d'entité précis. */
	record OfType(EntityType<?> type) implements EntityMatcher {
		@Override
		public boolean matches(Entity entity) {
			return entity.getType() == type;
		}
	}

	/** N'importe quelle entité du tag. */
	record OfTag(TagKey<EntityType<?>> tag) implements EntityMatcher {
		@Override
		public boolean matches(Entity entity) {
			return entity.getType().isIn(tag);
		}
	}
}
