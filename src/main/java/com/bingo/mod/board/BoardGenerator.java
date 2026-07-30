package com.bingo.mod.board;

import com.bingo.mod.data.DifficultyProfile;
import com.bingo.mod.data.PoolResolver;
import com.bingo.mod.data.Ruleset;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.objective.type.ObjectiveType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Tirage d'une carte 5×5 respectant la distribution d'un profil (`docs/01` §7).
 *
 * <p>Déterministe : à graine, profil et données identiques, la carte est identique. Les candidats
 * sont triés par ID avant tirage — l'ordre d'itération d'une {@code Map} immuable n'est pas
 * spécifié, s'y fier rendrait le tirage irreproductible sans que ça se voie.
 */
public final class BoardGenerator {

	private BoardGenerator() {
	}

	/**
	 * Résultat d'un tirage.
	 *
	 * @param tiles    les cases, dans l'ordre de la grille ; peut contenir moins de 25 entrées si
	 *                 le pool est trop pauvre, auquel cas {@code warnings} le dit
	 * @param warnings anomalies non fatales, à remonter à l'opérateur
	 */
	public record BoardDraw(List<Objective> tiles, long seed, List<String> warnings) {

		public boolean isComplete() {
			return tiles.size() == BingoBoard.TILE_COUNT;
		}

		/** Distribution réellement obtenue, qui peut différer de la demandée après comblement. */
		public Map<Integer, Integer> actualDistribution() {
			Map<Integer, Integer> counts = new LinkedHashMap<>();
			for (int level = 1; level <= 4; level++) {
				counts.put(level, 0);
			}
			tiles.forEach(objective -> counts.merge(objective.level(), 1, Integer::sum));
			return counts;
		}
	}

	public static BoardDraw generate(DifficultyProfile profile, Optional<Ruleset> ruleset, long seed) {
		List<String> warnings = new ArrayList<>();
		Random random = new Random(seed);

		List<PoolResolver.Candidate> candidates = new ArrayList<>(PoolResolver.resolve(profile.pool()));
		candidates.sort(Comparator.comparing(candidate -> candidate.objective().id()));

		if (candidates.isEmpty()) {
			warnings.add("Le pool '" + profile.pool() + "' ne fournit aucun objectif");
			return new BoardDraw(List.of(), seed, warnings);
		}

		// `docs/01` §4.4 demande au loader de rejeter les objectifs DEATH quand le ruleset a
		// elimination_on_death. Impossible au chargement : plusieurs rulesets coexistent et le
		// loader ne sait pas lequel servira. Le filtre est donc appliqué ici, au seul endroit où
		// le ruleset effectif est connu.
		if (ruleset.map(Ruleset::eliminationOnDeath).orElse(false)) {
			int before = candidates.size();
			candidates.removeIf(candidate -> candidate.objective().type() == ObjectiveType.DEATH);
			int removed = before - candidates.size();
			if (removed > 0) {
				warnings.add(removed + " objectif(s) DEATH écarté(s) : le ruleset a elimination_on_death");
			}
		}

		// Un poids nul veut dire « présent mais jamais tiré » : on l'écarte avant le tirage plutôt
		// que de le laisser fausser les totaux cumulés.
		candidates.removeIf(candidate -> candidate.weight() <= 0);

		Map<Integer, List<PoolResolver.Candidate>> byLevel = new LinkedHashMap<>();
		for (int level = 1; level <= 4; level++) {
			byLevel.put(level, new ArrayList<>());
		}
		candidates.forEach(candidate -> byLevel.get(candidate.objective().level()).add(candidate));

		List<Objective> picked = new ArrayList<>(BingoBoard.TILE_COUNT);

		for (int level = 1; level <= 4; level++) {
			int wanted = profile.countFor(level);
			int missing = drawFrom(byLevel.get(level), wanted, picked, random);

			if (missing > 0) {
				int filled = fillFromNearestLevels(byLevel, level, missing, picked, random);
				warnings.add("Niveau " + level + " sous-alimenté : " + missing + " case(s) manquante(s), "
						+ filled + " comblée(s) par un niveau voisin");
			}
		}

		if (picked.size() < BingoBoard.TILE_COUNT) {
			warnings.add("Grille incomplète : " + picked.size() + " case(s) sur " + BingoBoard.TILE_COUNT
					+ " — le pool est trop pauvre, même après comblement");
		}

		// Les niveaux ont été tirés dans l'ordre : sans ce mélange, la grille serait triée par
		// difficulté croissante et les lignes du haut toujours triviales.
		Collections.shuffle(picked, random);
		return new BoardDraw(List.copyOf(picked), seed, warnings);
	}

	/** @return le nombre de cases qui n'ont pas pu être tirées. */
	private static int drawFrom(List<PoolResolver.Candidate> pool,
	                            int wanted,
	                            List<Objective> picked,
	                            Random random) {
		int missing = 0;
		for (int i = 0; i < wanted; i++) {
			Objective drawn = pickWeighted(pool, picked, random);
			if (drawn == null) {
				missing++;
			} else {
				picked.add(drawn);
			}
		}
		return missing;
	}

	/**
	 * Comblement gracieux (`docs/01` §7, tâche 1.8) : puiser dans le niveau le plus proche.
	 *
	 * <p>À distance égale on descend d'abord : compléter par un objectif plus facile allonge moins
	 * la partie qu'un objectif plus dur, donc c'est le repli le moins invasif.
	 */
	private static int fillFromNearestLevels(Map<Integer, List<PoolResolver.Candidate>> byLevel,
	                                         int level,
	                                         int missing,
	                                         List<Objective> picked,
	                                         Random random) {
		List<Integer> order = new ArrayList<>();
		for (int distance = 1; distance <= 3; distance++) {
			if (level - distance >= 1) {
				order.add(level - distance);
			}
			if (level + distance <= 4) {
				order.add(level + distance);
			}
		}

		int filled = 0;
		for (int candidateLevel : order) {
			while (filled < missing) {
				Objective drawn = pickWeighted(byLevel.get(candidateLevel), picked, random);
				if (drawn == null) {
					break;
				}
				picked.add(drawn);
				filled++;
			}
		}
		return filled;
	}

	/**
	 * Tirage pondéré sans remise, en écartant les objectifs incompatibles avec ceux déjà tirés.
	 *
	 * <p>Les incompatibles sont filtrés <em>avant</em> le calcul du total cumulé : les écarter
	 * après le tirage biaiserait les poids des autres.
	 *
	 * @return {@code null} si aucun candidat n'est éligible.
	 */
	private static @Nullable Objective pickWeighted(List<PoolResolver.Candidate> pool,
	                                                List<Objective> picked,
	                                                Random random) {
		List<PoolResolver.Candidate> eligible = pool.stream()
				.filter(candidate -> isCompatible(candidate.objective(), picked))
				.toList();

		if (eligible.isEmpty()) {
			return null;
		}

		int total = eligible.stream().mapToInt(PoolResolver.Candidate::weight).sum();
		int roll = random.nextInt(total);
		for (PoolResolver.Candidate candidate : eligible) {
			roll -= candidate.weight();
			if (roll < 0) {
				pool.remove(candidate);
				return candidate.objective();
			}
		}

		// Inatteignable : la somme des poids couvre tout l'intervalle du tirage.
		PoolResolver.Candidate last = eligible.get(eligible.size() - 1);
		pool.remove(last);
		return last.objective();
	}

	/**
	 * Les {@code conflicts} sont traités comme <strong>symétriques</strong> : « jamais sur la même
	 * carte » (`docs/01` §2) est une relation mutuelle, et exiger la déclaration des deux côtés
	 * ferait dépendre le résultat de l'ordre de tirage.
	 */
	private static boolean isCompatible(Objective candidate, List<Objective> picked) {
		for (Objective already : picked) {
			if (already.id().equals(candidate.id())
					|| candidate.conflicts().contains(already.id())
					|| already.conflicts().contains(candidate.id())) {
				return false;
			}
		}
		return true;
	}
}
