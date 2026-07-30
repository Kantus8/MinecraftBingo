package com.bingo.mod.objective.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Cible {@code bingo:death} — le joueur meurt de la cause indiquée (`docs/01` §4.4).
 *
 * <p>Les types de dégâts sont un <strong>registre dynamique</strong> depuis 1.19.4 : ils ne
 * sont pas résolvables pendant un rechargement de ressources, faute d'accès au registre.
 * On stocke donc des {@link Identifier} bruts, convertis en {@code RegistryKey<DamageType>}
 * au moment de la comparaison ({@code source.isOf(key)} / {@code source.isIn(tag)}), au
 * lot 2. Corollaire : un {@code damage_type} inexistant ne se détecte pas au chargement.
 */
public record DeathTarget(Optional<Identifier> damageType, Optional<Identifier> damageTag, boolean anyDeath)
		implements ObjectiveTarget {

	public static final Codec<DeathTarget> CODEC = RecordCodecBuilder.<DeathTarget>create(instance -> instance.group(
					Identifier.CODEC.optionalFieldOf("damage_type").forGetter(DeathTarget::damageType),
					Identifier.CODEC.optionalFieldOf("damage_tag").forGetter(DeathTarget::damageTag),
					Codec.BOOL.optionalFieldOf("any_death", false).forGetter(DeathTarget::anyDeath)
			).apply(instance, DeathTarget::new))
			.flatXmap(DeathTarget::validate, DeathTarget::validate);

	/**
	 * Une cible {@code death} sans aucun critère serait validée par n'importe quelle mort
	 * sans le dire — c'est un piège silencieux, donc une erreur de schéma (règle n°5 de
	 * `docs/01` §2). Pour « n'importe quelle mort », il faut écrire {@code any_death: true}.
	 */
	private static DataResult<DeathTarget> validate(DeathTarget target) {
		if (target.damageType.isEmpty() && target.damageTag.isEmpty() && !target.anyDeath) {
			return DataResult.error(() -> "Cible 'death' sans critère : il faut 'damage_type', 'damage_tag' ou 'any_death: true'");
		}
		if (target.damageType.isPresent() && target.damageTag.isPresent()) {
			return DataResult.error(() -> "Cible 'death' avec 'damage_type' ET 'damage_tag' : choisir l'un ou l'autre");
		}
		return DataResult.success(target);
	}

	@Override
	public ObjectiveType type() {
		return ObjectiveType.DEATH;
	}
}
