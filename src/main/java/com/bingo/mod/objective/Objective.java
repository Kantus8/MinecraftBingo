package com.bingo.mod.objective;

import com.bingo.mod.objective.type.ObjectiveTarget;
import com.bingo.mod.objective.type.ObjectiveType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Un objectif de datapack (`docs/01` §2).
 *
 * <p>{@code id} ne figure pas dans le JSON : il est construit depuis le chemin du fichier
 * (`docs/01` §1), d'où {@link #codec(Identifier)} plutôt qu'une constante {@code CODEC}.
 *
 * <p>{@code interaction} et {@code jeiRole} restent optionnels dans le record parce que leur
 * défaut dépend du {@code type} — un codec ne peut pas dériver la valeur par défaut d'un
 * champ à partir d'un autre champ décodé. La résolution est explicite :
 * {@link #effectiveInteraction()} et {@link #effectiveJeiRole()}.
 */
public record Objective(
		Identifier id,
		ObjectiveType type,
		int level,
		ObjectiveTarget target,
		ObjectiveDisplay display,
		int weight,
		List<Identifier> tags,
		List<Identifier> conflicts,
		Optional<Identifier> requiresDimension,
		int count,
		Optional<Integer> pointsBase,
		Optional<ObjectiveInteraction> interaction,
		Optional<JeiRole> jeiRole,
		boolean announce
) {

	/** Poids de tirage par défaut (`docs/01` §2). */
	public static final int DEFAULT_WEIGHT = 10;

	/**
	 * Codec d'un objectif dont l'ID est déjà connu.
	 *
	 * <p>Les règles de validation n°2 ({@code 1 <= level <= 4}) et n°4 ({@code count >= 1})
	 * sont portées par {@link Codec#intRange} : une valeur hors bornes devient une erreur de
	 * décodage, donc un objectif ignoré avec un WARN, sans code de vérification séparé.
	 */
	public static Codec<Objective> codec(Identifier id) {
		return RecordCodecBuilder.create(instance -> instance.group(
				ObjectiveTarget.MAP_CODEC.forGetter(Objective::target),
				Codec.intRange(1, 4).fieldOf("level").forGetter(Objective::level),
				ObjectiveDisplay.CODEC.fieldOf("display").forGetter(Objective::display),
				Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("weight", DEFAULT_WEIGHT).forGetter(Objective::weight),
				Identifier.CODEC.listOf().optionalFieldOf("tags", List.of()).forGetter(Objective::tags),
				Identifier.CODEC.listOf().optionalFieldOf("conflicts", List.of()).forGetter(Objective::conflicts),
				Identifier.CODEC.optionalFieldOf("requires_dimension").forGetter(Objective::requiresDimension),
				Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(Objective::count),
				Codec.INT.optionalFieldOf("points_base").forGetter(Objective::pointsBase),
				ObjectiveInteraction.CODEC.optionalFieldOf("interaction").forGetter(Objective::interaction),
				JeiRole.CODEC.optionalFieldOf("jei_role").forGetter(Objective::jeiRole),
				Codec.BOOL.optionalFieldOf("announce", true).forGetter(Objective::announce)
		).apply(instance, (target, level, display, weight, tags, conflicts, requiresDimension,
		                   count, pointsBase, interaction, jeiRole, announce) ->
				new Objective(id, target.type(), level, target, display, weight, tags, conflicts,
						requiresDimension, count, pointsBase, interaction, jeiRole, announce)));
	}

	/** Titre affiché, avec l'ID brut en dernier recours — {@code display.name} est optionnel. */
	public Text displayName() {
		return display.name().orElseGet(() -> Text.literal(id.toString()));
	}

	/** Effet du clic, défaut dérivé du type (`docs/01` §5). */
	public ObjectiveInteraction effectiveInteraction() {
		return interaction.orElseGet(type::defaultInteraction);
	}

	/** Rôle JEI, défaut dérivé du type (`docs/01` §5). */
	public Optional<JeiRole> effectiveJeiRole() {
		return jeiRole.isPresent() ? jeiRole : type.defaultJeiRole();
	}
}
