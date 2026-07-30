package com.bingo.mod.board;

import com.bingo.mod.data.Ruleset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Les 12 combinaisons gagnantes d'une grille 5×5 (`docs/05` §1.1).
 *
 * <p>Chaque combinaison est réduite à un <strong>masque de 25 bits</strong> : la détection de
 * victoire devient 12 {@code and} et 12 comparaisons, donc appelable à chaque validation sans
 * réflexion sur le coût. Un {@code int} suffit pour 25 cases, ce qui rend aussi la
 * sérialisation réseau triviale (4 octets, `docs/06` §3.3).
 *
 * <p>Les combinaisons sont construites une fois au chargement de la classe plutôt qu'écrites
 * en dur : les 60 index d'un tableau littéral seraient impossibles à relire, et la géométrie
 * reste ainsi dérivée de {@link BingoBoard#SIZE}.
 */
public final class WinLines {

	/**
	 * Une combinaison gagnante.
	 *
	 * @param kind    forme de la combinaison, pour la filtrer selon {@code win_conditions} du
	 *                ruleset (`docs/01` §8)
	 * @param mask    les 5 bits de ses cases
	 * @param indices les 5 index de case, dans l'ordre de lecture — c'est cette forme qui part
	 *                dans {@code bingo:game_end} (`docs/06` §3.1)
	 */
	public record Line(Ruleset.WinCondition kind, int mask, List<Integer> indices) {
	}

	/** 5 lignes, puis 5 colonnes, puis les 2 diagonales. */
	public static final List<Line> ALL = buildLines();

	/** Les mêmes combinaisons réduites à leur masque, dans l'ordre de {@link #ALL}. */
	public static final int[] LINE_MASKS = ALL.stream().mapToInt(Line::mask).toArray();

	/** Masque des 25 cases, utile pour borner un masque lu en NBT ou sur le réseau. */
	public static final int FULL_MASK = (1 << BingoBoard.TILE_COUNT) - 1;

	private WinLines() {
	}

	private static List<Line> buildLines() {
		List<Line> lines = new ArrayList<>(12);

		for (int row = 0; row < BingoBoard.SIZE; row++) {
			List<Integer> indices = new ArrayList<>(BingoBoard.SIZE);
			for (int col = 0; col < BingoBoard.SIZE; col++) {
				indices.add(BingoBoard.index(row, col));
			}
			lines.add(line(Ruleset.WinCondition.LINE, indices));
		}

		for (int col = 0; col < BingoBoard.SIZE; col++) {
			List<Integer> indices = new ArrayList<>(BingoBoard.SIZE);
			for (int row = 0; row < BingoBoard.SIZE; row++) {
				indices.add(BingoBoard.index(row, col));
			}
			lines.add(line(Ruleset.WinCondition.COLUMN, indices));
		}

		List<Integer> descending = new ArrayList<>(BingoBoard.SIZE);
		List<Integer> ascending = new ArrayList<>(BingoBoard.SIZE);
		for (int step = 0; step < BingoBoard.SIZE; step++) {
			descending.add(BingoBoard.index(step, step));
			ascending.add(BingoBoard.index(step, BingoBoard.SIZE - 1 - step));
		}
		lines.add(line(Ruleset.WinCondition.DIAGONAL, descending));
		lines.add(line(Ruleset.WinCondition.DIAGONAL, ascending));

		return List.copyOf(lines);
	}

	private static Line line(Ruleset.WinCondition kind, List<Integer> indices) {
		int mask = 0;
		for (int index : indices) {
			mask |= bit(index);
		}
		return new Line(kind, mask, List.copyOf(indices));
	}

	/** Le bit d'une case dans un {@code completionMask}. */
	public static int bit(int index) {
		return 1 << index;
	}

	public static boolean isCompleted(int completionMask, int index) {
		return (completionMask & bit(index)) != 0;
	}

	/** Nombre de cases validées, toutes combinaisons confondues. */
	public static int tileCount(int completionMask) {
		return Integer.bitCount(completionMask & FULL_MASK);
	}

	/**
	 * Détection de victoire (`docs/05` §1.1).
	 *
	 * <p>Surcharge sans filtre : les 12 combinaisons comptent. À réserver aux appels qui n'ont
	 * pas de ruleset sous la main, la version filtrée étant la règle.
	 */
	public static boolean hasWon(int completionMask) {
		for (int mask : LINE_MASKS) {
			if ((completionMask & mask) == mask) {
				return true;
			}
		}
		return false;
	}

	/** Détection de victoire restreinte aux formes activées par le ruleset (`docs/01` §8). */
	public static boolean hasWon(int completionMask, Collection<Ruleset.WinCondition> enabled) {
		return firstCompleted(completionMask, enabled).isPresent();
	}

	/**
	 * La première combinaison complétée, dans l'ordre de {@link #ALL}.
	 *
	 * <p>« Première » est un ordre de déclaration, pas un ordre chronologique : quand deux
	 * combinaisons se complètent sur la même case, laquelle est annoncée n'a aucune incidence
	 * sur le vainqueur. L'arbitrage entre équipes, lui, se fait au timestamp (`docs/05` §1.4).
	 */
	public static Optional<Line> firstCompleted(int completionMask, Collection<Ruleset.WinCondition> enabled) {
		for (Line line : ALL) {
			if (enabled.contains(line.kind()) && (completionMask & line.mask()) == line.mask()) {
				return Optional.of(line);
			}
		}
		return Optional.empty();
	}

	/**
	 * Meilleur avancement sur une combinaison, de 0 à 5.
	 *
	 * <p>Sert à l'égalité n°3 de `docs/05` §1.3 (« 4/5 bat 3/5 ») et au son local de
	 * `docs/05` §5 quand une équipe atteint 4.
	 */
	public static int bestProgress(int completionMask, Collection<Ruleset.WinCondition> enabled) {
		int best = 0;
		for (Line line : ALL) {
			if (enabled.contains(line.kind())) {
				best = Math.max(best, Integer.bitCount(completionMask & line.mask()));
			}
		}
		return best;
	}

	/**
	 * Les combinaisons à exactement une case de la victoire.
	 *
	 * <p>Consommée par la bordure dorée du HUD (`docs/03` §2) et par le son de `docs/05` §5.
	 */
	public static List<Line> oneAway(int completionMask, Collection<Ruleset.WinCondition> enabled) {
		List<Line> lines = new ArrayList<>();
		for (Line line : ALL) {
			if (enabled.contains(line.kind())
					&& Integer.bitCount(completionMask & line.mask()) == BingoBoard.SIZE - 1) {
				lines.add(line);
			}
		}
		return lines;
	}
}
