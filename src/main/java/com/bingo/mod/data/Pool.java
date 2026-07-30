package com.bingo.mod.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.Codecs;

import java.util.List;
import java.util.Optional;

/**
 * Ensemble d'objectifs éligibles au tirage (`docs/01` §6).
 *
 * <p>La résolution — {@code entries ∪ include_tags ∪ inherit − exclude_tags} — vit dans
 * {@link PoolResolver} : elle a besoin du registre d'objectifs et des autres pools, donc elle
 * ne peut pas être une méthode du record.
 *
 * @param entries      objectifs cités explicitement, avec surcharge de poids possible
 * @param includeTags  ajoute tous les objectifs portant l'un de ces tags
 * @param excludeTags  retire, priorité sur {@code includeTags}
 * @param inherit      composition : IDs d'autres pools
 */
public record Pool(
		Optional<Text> displayName,
		List<Entry> entries,
		List<Identifier> includeTags,
		List<Identifier> excludeTags,
		List<Identifier> inherit
) {

	public static final Codec<Pool> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codecs.TEXT.optionalFieldOf("display_name").forGetter(Pool::displayName),
			Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(Pool::entries),
			Identifier.CODEC.listOf().optionalFieldOf("include_tags", List.of()).forGetter(Pool::includeTags),
			Identifier.CODEC.listOf().optionalFieldOf("exclude_tags", List.of()).forGetter(Pool::excludeTags),
			Identifier.CODEC.listOf().optionalFieldOf("inherit", List.of()).forGetter(Pool::inherit)
	).apply(instance, Pool::new));

	/**
	 * Une entrée explicite du pool.
	 *
	 * @param weight surcharge le poids déclaré par l'objectif lui-même (`docs/01` §6)
	 */
	public record Entry(Identifier objective, Optional<Integer> weight) {

		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.fieldOf("objective").forGetter(Entry::objective),
				Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("weight").forGetter(Entry::weight)
		).apply(instance, Entry::new));
	}
}
