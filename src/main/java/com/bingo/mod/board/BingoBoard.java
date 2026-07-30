package com.bingo.mod.board;

/**
 * Géométrie de la grille.
 *
 * <p>Source unique des dimensions : la distribution d'un profil de difficulté doit sommer à
 * {@link #TILE_COUNT} (`docs/01` §7), et les 12 combinaisons gagnantes du lot 2 en dépendent
 * (`docs/05` §1.1).
 *
 * <p>La grille est fixée à 5×5. Le ruleset expose bien {@code board.width} / {@code board.height},
 * mais {@code LINE_MASKS} sera calibré pour 5×5 : tant que ces 12 masques sont écrits en dur,
 * changer ces constantes ne suffirait pas à obtenir une grille d'une autre taille.
 */
public final class BingoBoard {

	public static final int SIZE = 5;

	/** 25 cases, index = {@code row * SIZE + col} (`docs/00` glossaire). */
	public static final int TILE_COUNT = SIZE * SIZE;

	private BingoBoard() {
	}

	public static int index(int row, int col) {
		return row * SIZE + col;
	}

	public static int row(int index) {
		return index / SIZE;
	}

	public static int col(int index) {
		return index % SIZE;
	}
}
