package com.bingo.mod.board;

import com.bingo.mod.data.DifficultyProfile;
import com.bingo.mod.data.PoolResolver;
import com.bingo.mod.data.Ruleset;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.objective.type.ObjectiveType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>Le tirage se fait en deux temps : <em>quelles</em> cases (la distribution du profil), puis
 * <em>où</em> les poser. La seconde étape équilibre les 5 lignes et les 5 colonnes autour de
 * {@link BoardDraw#lineTarget()}. Sans elle, une distribution parfaitement dosée produit quand même
 * des grilles où une ligne cumule 5 niveaux 1 pendant qu'une autre en additionne 18 : les 12 chemins
 * de victoire de {@link WinLines} n'ont alors plus rien de comparable, et la manche se joue sur la
 * chance du tirage plutôt que sur la course.
 */
public final class BoardGenerator {

	/**
	 * Écart maximal toléré entre la somme des niveaux d'une ligne et le niveau cible.
	 *
	 * <p>Zéro serait inatteignable : les 5 lignes couvrent exactement les 25 cases, donc leur somme
	 * vaut le total de la grille — dès que ce total n'est pas un multiple de 5, aucune disposition
	 * n'a ses cinq lignes à la moyenne exacte. ±1 est aussi ce qui rend la recherche rapide, là où
	 * une contrainte plus serrée la ferait sécher sur un optimum qui n'existe pas.
	 */
	public static final int LINE_TOLERANCE = 1;

	/**
	 * Poids d'une ligne hors tolérance dans le coût d'une disposition.
	 *
	 * <p>Les deux critères — d'abord le nombre de lignes hors bande, ensuite l'écart quadratique —
	 * sont empilés dans un seul entier pour que la descente n'ait qu'une valeur à faire baisser ;
	 * une paire {@code (violations, écart)} imposerait un ordre lexicographique à chaque comparaison
	 * pour le même résultat. La valeur dépasse le pire écart quadratique possible
	 * (10 lignes × 20² = 4000), sans quoi une ligne franchement hors bande pourrait passer pour
	 * meilleure que dix lignes à peine décalées.
	 */
	private static final int VIOLATION_WEIGHT = 10_000;

	/**
	 * Nombre de dispositions de départ essayées avant d'accepter la meilleure trouvée.
	 *
	 * <p>La descente stricte s'arrête au premier optimum local. À 25 cases, repartir d'un brassage
	 * neuf coûte moins cher qu'une heuristique d'échappement, et les 5 profils livrés tombent dans
	 * la bande à la première ou à la deuxième tentative — {@code easiest} n'a même rien à équilibrer,
	 * 25 cases de niveau 1 donnant 5 par ligne quel que soit le placement.
	 */
	private static final int MAX_ARRANGEMENTS = 16;

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
			for (int level = Objective.MIN_LEVEL; level <= Objective.MAX_LEVEL; level++) {
				counts.put(level, 0);
			}
			tiles.forEach(objective -> counts.merge(objective.level(), 1, Integer::sum));
			return counts;
		}

		/**
		 * Niveau cumulé visé par chaque ligne et chaque colonne : la moyenne des niveaux tirés.
		 *
		 * <p>Dérivé du tirage et non lu dans le profil, parce que c'est la seule valeur tenable :
		 * les 5 lignes couvrent les 25 cases, donc une cible posée dans le datapack serait fausse
		 * dès que la distribution ne totalise pas cinq fois cette cible. C'est le profil qui règle
		 * la difficulté, en choisissant une distribution dont la moyenne tombe sur le niveau voulu.
		 */
		public int lineTarget() {
			int total = tiles.stream().mapToInt(Objective::level).sum();
			return Math.round((float) total / BingoBoard.SIZE);
		}

		/**
		 * Somme des niveaux de chaque combinaison d'une forme donnée, dans l'ordre de
		 * {@link WinLines#ALL}.
		 *
		 * <p>La géométrie est empruntée à {@link WinLines} plutôt que reconstruite ici : les lignes
		 * dont on veut égaliser la difficulté sont exactement les chemins de victoire, et deux
		 * définitions de « ligne » finiraient par diverger.
		 */
		public int[] lineSums(Ruleset.WinCondition kind) {
			return WinLines.ALL.stream()
					.filter(line -> line.kind() == kind)
					.mapToInt(line -> line.indices().stream()
							.mapToInt(index -> index < tiles.size() ? tiles.get(index).level() : 0)
							.sum())
					.toArray();
		}

		/**
		 * Vrai si les 5 lignes et les 5 colonnes tiennent dans {@link #LINE_TOLERANCE} du niveau
		 * cible. Les diagonales sont mesurées ({@link #lineSums}) mais pas contraintes : les forcer
		 * dans la bande ajoute deux équations à un système qui n'a déjà pas toujours de solution.
		 */
		public boolean isBalanced() {
			int target = lineTarget();
			for (Ruleset.WinCondition kind : List.of(Ruleset.WinCondition.LINE, Ruleset.WinCondition.COLUMN)) {
				for (int sum : lineSums(kind)) {
					if (Math.abs(sum - target) > LINE_TOLERANCE) {
						return false;
					}
				}
			}
			return true;
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
		for (int level = Objective.MIN_LEVEL; level <= Objective.MAX_LEVEL; level++) {
			byLevel.put(level, new ArrayList<>());
		}
		candidates.forEach(candidate -> byLevel.get(candidate.objective().level()).add(candidate));

		List<Objective> picked = new ArrayList<>(BingoBoard.TILE_COUNT);

		for (int level = Objective.MIN_LEVEL; level <= Objective.MAX_LEVEL; level++) {
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
		// difficulté croissante et les lignes du haut toujours triviales. C'est aussi le point de
		// départ de l'équilibrage, qui a besoin d'une disposition quelconque à améliorer.
		Collections.shuffle(picked, random);
		return new BoardDraw(arrange(picked, random, warnings), seed, warnings);
	}

	// ── Tirage des cases ──────────────────────────────────────────────────────

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
		int maxDistance = Objective.MAX_LEVEL - Objective.MIN_LEVEL;
		for (int distance = 1; distance <= maxDistance; distance++) {
			if (level - distance >= Objective.MIN_LEVEL) {
				order.add(level - distance);
			}
			if (level + distance <= Objective.MAX_LEVEL) {
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

	// ── Équilibrage des lignes et des colonnes ────────────────────────────────

	/**
	 * Dispose les cases tirées pour que chaque ligne et chaque colonne cumule le même niveau, à
	 * {@link #LINE_TOLERANCE} près.
	 *
	 * <p>Recherche locale par échanges de deux cases, relancée depuis un brassage neuf tant qu'elle
	 * bloque hors de la bande. Une construction directe ligne par ligne aurait été plus rapide mais
	 * ne sait pas se rattraper : les cinq dernières cases n'ont plus aucune liberté et la contrainte
	 * de colonne tombe presque toujours à côté. L'échange, lui, ne peut jamais casser la
	 * distribution puisqu'il ne fait que déplacer des cases déjà tirées.
	 *
	 * <p>Déterministe : les paires sont parcourues dans un ordre fixe et les relances consomment la
	 * graine du tirage. Même graine, même grille — l'animation de tirage en dépend (`docs/04` §3).
	 *
	 * @return les cases dans l'ordre de la grille, au mieux de ce que la recherche a trouvé
	 */
	private static List<Objective> arrange(List<Objective> picked, Random random, List<String> warnings) {
		// Une grille incomplète est déjà refusée par BingoGame.start : l'équilibrer n'apporterait
		// rien et les sommes de lignes seraient calculées sur des cases absentes.
		if (picked.size() < BingoBoard.TILE_COUNT) {
			return List.copyOf(picked);
		}

		Objective[] grid = picked.toArray(new Objective[0]);
		int target = Math.round((float) totalLevel(grid) / BingoBoard.SIZE);

		int[] rowSum = new int[BingoBoard.SIZE];
		int[] colSum = new int[BingoBoard.SIZE];
		recount(grid, rowSum, colSum);
		descend(grid, rowSum, colSum, target);

		Objective[] best = grid.clone();
		int bestCost = cost(rowSum, colSum, target);

		for (int attempt = 1; attempt < MAX_ARRANGEMENTS && bestCost >= VIOLATION_WEIGHT; attempt++) {
			shuffle(grid, random);
			recount(grid, rowSum, colSum);
			descend(grid, rowSum, colSum, target);

			int current = cost(rowSum, colSum, target);
			if (current < bestCost) {
				bestCost = current;
				best = grid.clone();
			}
		}

		if (bestCost >= VIOLATION_WEIGHT) {
			// Non fatal : une grille légèrement déséquilibrée reste jouable, et l'opérateur a plus
			// besoin de savoir que sa distribution est difficile à répartir que d'un refus sec.
			warnings.add("Lignes non équilibrées : " + bestCost / VIOLATION_WEIGHT
					+ " ligne(s)/colonne(s) hors de " + target + "±" + LINE_TOLERANCE
					+ " — la distribution du profil s'y répartit mal");
		}

		return List.of(best);
	}

	/**
	 * Descente stricte : à chaque tour, l'échange qui fait le plus baisser le coût, jusqu'à ce
	 * qu'aucun n'améliore plus.
	 *
	 * <p>Le coût est un entier qui décroît strictement, donc la boucle termine. Les 300 paires sont
	 * évaluées en appliquant l'échange puis en le défaisant : recalculer les 10 sommes coûte
	 * quelques dizaines d'opérations, contre un calcul de delta par cas (même ligne, même colonne,
	 * ni l'un ni l'autre) qu'il faudrait relire à chaque modification.
	 */
	private static void descend(Objective[] grid, int[] rowSum, int[] colSum, int target) {
		int current = cost(rowSum, colSum, target);

		while (true) {
			int bestCost = current;
			int bestA = -1;
			int bestB = -1;

			for (int a = 0; a < BingoBoard.TILE_COUNT; a++) {
				for (int b = a + 1; b < BingoBoard.TILE_COUNT; b++) {
					// Deux cases de même niveau s'échangent sans rien changer aux sommes : les
					// écarter ici évite autant d'évaluations inutiles que la grille a de doublons.
					if (grid[a].level() == grid[b].level()) {
						continue;
					}
					swap(grid, rowSum, colSum, a, b);
					int candidate = cost(rowSum, colSum, target);
					swap(grid, rowSum, colSum, a, b);

					if (candidate < bestCost) {
						bestCost = candidate;
						bestA = a;
						bestB = b;
					}
				}
			}

			if (bestA < 0) {
				return;
			}
			swap(grid, rowSum, colSum, bestA, bestB);
			current = bestCost;
		}
	}

	/**
	 * Échange deux cases en tenant les sommes à jour. L'opération est sa propre inverse, ce qui
	 * permet de l'utiliser pour sonder une paire sans recopier la grille.
	 */
	private static void swap(Objective[] grid, int[] rowSum, int[] colSum, int a, int b) {
		int delta = grid[b].level() - grid[a].level();

		// Quand les deux cases partagent leur ligne (ou leur colonne), le += et le -= s'annulent
		// sur la même case du tableau : pas de cas particulier à écrire.
		rowSum[BingoBoard.row(a)] += delta;
		rowSum[BingoBoard.row(b)] -= delta;
		colSum[BingoBoard.col(a)] += delta;
		colSum[BingoBoard.col(b)] -= delta;

		Objective held = grid[a];
		grid[a] = grid[b];
		grid[b] = held;
	}

	/** Coût d'une disposition : lignes hors bande d'abord, écart quadratique en arbitrage. */
	private static int cost(int[] rowSum, int[] colSum, int target) {
		int total = 0;
		for (int index = 0; index < BingoBoard.SIZE; index++) {
			total += penalty(rowSum[index], target) + penalty(colSum[index], target);
		}
		return total;
	}

	private static int penalty(int sum, int target) {
		int deviation = Math.abs(sum - target);
		int squared = deviation * deviation;
		return deviation > LINE_TOLERANCE ? VIOLATION_WEIGHT + squared : squared;
	}

	private static void recount(Objective[] grid, int[] rowSum, int[] colSum) {
		Arrays.fill(rowSum, 0);
		Arrays.fill(colSum, 0);
		for (int index = 0; index < grid.length; index++) {
			rowSum[BingoBoard.row(index)] += grid[index].level();
			colSum[BingoBoard.col(index)] += grid[index].level();
		}
	}

	/**
	 * Brassage de Fisher-Yates sur place : {@link Collections#shuffle} demanderait une {@code List},
	 * donc une copie aller-retour à chaque relance.
	 */
	private static void shuffle(Objective[] grid, Random random) {
		for (int index = grid.length - 1; index > 0; index--) {
			int target = random.nextInt(index + 1);
			Objective held = grid[index];
			grid[index] = grid[target];
			grid[target] = held;
		}
	}

	private static int totalLevel(Objective[] grid) {
		int total = 0;
		for (Objective objective : grid) {
			total += objective.level();
		}
		return total;
	}
}
