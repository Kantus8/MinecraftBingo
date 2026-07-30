package com.bingo.mod.data;

import com.bingo.mod.objective.Objective;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Résolution d'un pool en liste de candidats pondérés (`docs/01` §6).
 *
 * <p>Formule : {@code entries ∪ include_tags ∪ inherit − exclude_tags}.
 */
public final class PoolResolver {

	private PoolResolver() {
	}

	/**
	 * Un objectif éligible et son poids effectif.
	 *
	 * @param weight poids de l'entrée explicite si elle en surcharge un, sinon celui de l'objectif
	 */
	public record Candidate(Objective objective, int weight) {
	}

	/**
	 * @return les candidats du pool, vide si le pool est introuvable (journalisé).
	 */
	public static List<Candidate> resolve(Identifier poolId) {
		Map<Identifier, Integer> weightOverrides = new HashMap<>();
		Map<Identifier, Objective> selected = new LinkedHashMap<>();
		Set<Identifier> excludeTags = new HashSet<>();

		if (!collect(poolId, selected, weightOverrides, excludeTags, new HashSet<>())) {
			return List.of();
		}

		// Les exclusions de toute la chaîne d'héritage s'appliquent en dernier : « priorité sur
		// include » (`docs/01` §6) vaut aussi face à une entrée explicite, sinon un pool parent ne
		// pourrait pas retirer un objectif introduit par un pool hérité.
		List<Candidate> candidates = new ArrayList<>();
		selected.forEach((id, objective) -> {
			if (objective.tags().stream().anyMatch(excludeTags::contains)) {
				return;
			}
			candidates.add(new Candidate(objective, weightOverrides.getOrDefault(id, objective.weight())));
		});
		return candidates;
	}

	/**
	 * @param visited pools déjà traversés — un {@code inherit} cyclique se contenterait sinon de
	 *                boucler jusqu'au débordement de pile.
	 * @return {@code false} si le pool est introuvable.
	 */
	private static boolean collect(Identifier poolId,
	                               Map<Identifier, Objective> selected,
	                               Map<Identifier, Integer> weightOverrides,
	                               Set<Identifier> excludeTags,
	                               Set<Identifier> visited) {
		if (!visited.add(poolId)) {
			BingoConstants.LOGGER.warn("Pool '{}' déjà traversé — héritage cyclique ignoré", poolId);
			return true;
		}

		Optional<Pool> found = BingoData.POOLS.get(poolId);
		if (found.isEmpty()) {
			BingoConstants.LOGGER.warn("Pool '{}' introuvable", poolId);
			return false;
		}
		Pool pool = found.get();

		excludeTags.addAll(pool.excludeTags());

		for (Pool.Entry entry : pool.entries()) {
			Optional<Objective> objective = BingoData.OBJECTIVES.get(entry.objective());
			if (objective.isEmpty()) {
				BingoConstants.LOGGER.warn("Pool '{}' cite l'objectif '{}', qui n'existe pas",
						poolId, entry.objective());
				continue;
			}
			selected.put(entry.objective(), objective.get());
			entry.weight().ifPresent(weight -> weightOverrides.put(entry.objective(), weight));
		}

		if (!pool.includeTags().isEmpty()) {
			Set<Identifier> includeTags = new HashSet<>(pool.includeTags());
			for (Objective objective : BingoData.OBJECTIVES.all()) {
				if (objective.tags().stream().anyMatch(includeTags::contains)) {
					selected.putIfAbsent(objective.id(), objective);
				}
			}
		}

		for (Identifier parent : pool.inherit()) {
			collect(parent, selected, weightOverrides, excludeTags, visited);
		}
		return true;
	}
}
