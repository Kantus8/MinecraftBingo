package com.bingo.mod.objective;

import com.bingo.mod.util.BingoConstants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.Codecs;

import java.util.Optional;
import java.util.function.Function;

/**
 * Partie affichable d'un objectif (`docs/01` §2).
 *
 * <p>{@code icon} reste un {@link Identifier} et non un {@code Item} : c'est la forme qui
 * part sur le réseau (`docs/06` §3.4), et garder le record purement déclaratif évite de
 * dépendre de l'état d'un registre pour construire une donnée.
 *
 * @param icon        item rendu dans la case — jamais une entité (`docs/01` §2)
 * @param iconCount   badge numérique sur l'icône
 * @param name        titre affiché ; optionnel selon le schéma, voir {@link com.bingo.mod.objective.Objective#displayName()}
 * @param description texte du pop-up
 */
public record ObjectiveDisplay(Identifier icon, int iconCount, Optional<Text> name, Optional<Text> description) {

	/** Repli de la règle de validation n°3 (`docs/01` §2). */
	public static final Identifier FALLBACK_ICON = new Identifier("minecraft", "barrier");

	public static final Codec<ObjectiveDisplay> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.fieldOf("icon").xmap(ObjectiveDisplay::resolveIcon, Function.identity())
					.forGetter(ObjectiveDisplay::icon),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("icon_count", 1).forGetter(ObjectiveDisplay::iconCount),
			Codecs.TEXT.optionalFieldOf("name").forGetter(ObjectiveDisplay::name),
			Codecs.TEXT.optionalFieldOf("description").forGetter(ObjectiveDisplay::description)
	).apply(instance, ObjectiveDisplay::new));

	/**
	 * Règle de validation n°3 : une icône absente du registre n'invalide pas l'objectif, elle
	 * retombe sur {@code minecraft:barrier} avec un WARN.
	 *
	 * <p>Le repli est fait ici, pendant le décodage, et non après : le corriger plus tard
	 * imposerait de recopier tout le record pour changer un seul champ. {@code Registries.ITEM}
	 * est un registre statique, disponible à tout instant — contrairement aux types de dégâts
	 * (voir {@code DeathTarget}).
	 */
	private static Identifier resolveIcon(Identifier icon) {
		if (Registries.ITEM.containsId(icon)) {
			return icon;
		}
		BingoConstants.LOGGER.warn("Icône inconnue '{}' — repli sur {}", icon, FALLBACK_ICON);
		return FALLBACK_ICON;
	}
}
