package com.bingo.mod.game;

import com.bingo.mod.board.WinLines;
import com.bingo.mod.data.Ruleset;
import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.objective.Objective;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Score d'une case, score d'une équipe et classement final (`docs/05` §2, §1.3).
 *
 * <p>Tout est <strong>dérivé</strong> de {@code completionMask} et recalculé de zéro à chaque
 * appel. Un score accumulé se désynchronise à la première annulation
 * ({@code /bingo debug uncomplete}) ou au premier rechargement ; un score dérivé de l'état ne
 * peut pas dériver de l'état.
 *
 * <p>Le coût est de 25 additions par équipe et par appel, sur des événements qui arrivent
 * quelques dizaines de fois par manche.
 */
public final class BingoScoring {

	private BingoScoring() {
	}

	/**
	 * {@code PointsBase × 2^(niveau−1)} (`docs/05` §2.1).
	 *
	 * <p>Décalage de bits et non {@code Math.pow} : non pour la vitesse, mais parce qu'un score
	 * entier doit rester entier sans arrondi possible.
	 */
	public static int tileScore(Objective objective, int rulesetPointsBase) {
		int base = objective.pointsBase().orElse(rulesetPointsBase);
		return base << (objective.level() - 1);
	}

	/** Somme des scores des cases validées par l'équipe (`docs/05` §2.2). */
	public static int teamScore(BingoTeam team, List<Objective> tiles, int pointsBase) {
		int total = 0;
		for (int index = 0; index < tiles.size(); index++) {
			if (team.isCompleted(index)) {
				total += tileScore(tiles.get(index), pointsBase);
			}
		}
		return total;
	}

	/**
	 * Une ligne de classement.
	 *
	 * @param bestLineProgress meilleur avancement sur une combinaison, 0 à 5 — le troisième
	 *                         critère d'égalité de `docs/05` §1.3 (« 4/5 bat 3/5 »)
	 */
	public record Standing(BingoTeam team, int score, int tileCount, int bestLineProgress) {
	}

	/**
	 * Classement, meilleure équipe en tête, selon les trois critères de `docs/05` §1.3 :
	 * score, puis nombre de cases, puis meilleure combinaison entamée.
	 *
	 * <p>Le quatrième cas (« match nul ») n'est pas un critère de tri mais une lecture du
	 * résultat : voir {@link #isDraw(List)}.
	 */
	public static List<Standing> ranking(Collection<BingoTeam> teams,
	                                     List<Objective> tiles,
	                                     int pointsBase,
	                                     Collection<Ruleset.WinCondition> enabled) {
		List<Standing> standings = new ArrayList<>(teams.size());
		for (BingoTeam team : teams) {
			standings.add(new Standing(
					team,
					teamScore(team, tiles, pointsBase),
					team.tileCount(),
					WinLines.bestProgress(team.completionMask(), enabled)));
		}

		standings.sort(Comparator.comparingInt(Standing::score)
				.thenComparingInt(Standing::tileCount)
				.thenComparingInt(Standing::bestLineProgress)
				.reversed()
				// Départage stable et reproductible quand les trois critères sont épuisés : sans
				// lui, l'ordre d'affichage d'un match nul dépendrait de l'ordre d'itération.
				.thenComparing(standing -> standing.team().id()));
		return standings;
	}

	/**
	 * Les équipes de tête sont-elles parfaitement à égalité ? (cas 4 de `docs/05` §1.3)
	 *
	 * @param ranking un classement issu de {@link #ranking}
	 */
	public static boolean isDraw(List<Standing> ranking) {
		return ranking.size() > 1 && tiedWithFirst(ranking) == ranking.size();
	}

	/** Nombre d'équipes à égalité parfaite avec la tête du classement, au moins 1. */
	public static int tiedWithFirst(List<Standing> ranking) {
		if (ranking.isEmpty()) {
			return 0;
		}
		Standing best = ranking.get(0);
		int tied = 1;
		for (int i = 1; i < ranking.size(); i++) {
			Standing other = ranking.get(i);
			if (other.score() != best.score()
					|| other.tileCount() != best.tileCount()
					|| other.bestLineProgress() != best.bestLineProgress()) {
				break;
			}
			tied++;
		}
		return tied;
	}
}
