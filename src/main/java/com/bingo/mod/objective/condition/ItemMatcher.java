package com.bingo.mod.objective.condition;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;

/**
 * Prédicat sur un item : soit un item précis, soit un tag.
 *
 * <p>Sert aux cibles {@code craft} et {@code find} (`docs/01` §4.1-4.2), où le JSON
 * accepte {@code "item": "minecraft:dirt"} ou {@code "tag": "#minecraft:logs"}.
 *
 * <p>Le codec de l'item est celui du registre : un item inconnu fait échouer le décodage,
 * donc l'objectif entier est rejeté avec un WARN (règle de validation n°5 de `docs/01` §2).
 * C'est voulu — un objectif dont la cible n'existe pas ne peut jamais être validé.
 */
public sealed interface ItemMatcher {

	/**
	 * Codec « à plat » : les clés {@code item} / {@code tag} vivent au niveau de l'objet
	 * {@code target}, à côté de {@code match_nbt}. D'où un {@link MapCodec} et non un
	 * {@link Codec} — un Codec imbriquerait ces clés dans un sous-objet.
	 *
	 * <p>{@link TagKey#codec} attend la forme préfixée ({@code #minecraft:logs}), qui est
	 * exactement celle des datapacks livrés.
	 */
	MapCodec<ItemMatcher> MAP_CODEC = Codec.mapEither(
					Registries.ITEM.getCodec().fieldOf("item"),
					TagKey.codec(RegistryKeys.ITEM).fieldOf("tag"))
			.xmap(
					either -> either.map(OfItem::new, OfTag::new),
					matcher -> matcher instanceof OfItem ofItem
							? Either.left(ofItem.item())
							: Either.right(((OfTag) matcher).tag()));

	/** Variante autonome, pour un contexte où le prédicat est seul dans son objet. */
	Codec<ItemMatcher> CODEC = MAP_CODEC.codec();

	boolean matches(ItemStack stack);

	/** Un item précis. */
	record OfItem(Item item) implements ItemMatcher {
		@Override
		public boolean matches(ItemStack stack) {
			return stack.isOf(item);
		}
	}

	/** N'importe quel item du tag. */
	record OfTag(TagKey<Item> tag) implements ItemMatcher {
		@Override
		public boolean matches(ItemStack stack) {
			return stack.isIn(tag);
		}
	}
}
