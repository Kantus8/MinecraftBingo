package com.bingo.mod.client.hud;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.client.config.BingoClientConfig;
import net.minecraft.client.MinecraftClient;

import java.util.OptionalInt;

/**
 * <strong>Source unique</strong> des constantes de layout de la grille (`docs/03` §1, tâche 2.11).
 *
 * <p>Le HUD n'est pas cliquable — un overlay dessiné par {@code HudRenderCallback} ne reçoit
 * jamais d'événement de souris. L'illusion « le HUD devient cliquable » repose sur un
 * {@code Screen} qui redessine la grille <em>exactement</em> au même endroit et à la même échelle
 * (`docs/03` en tête). Cette classe est ce qui rend l'illusion parfaite : si le HUD et l'écran
 * calculaient chacun leurs positions, ils finiraient désalignés d'un pixel et l'effet tomberait.
 *
 * <p>Les valeurs sont dérivées de {@link BingoBoard#SIZE} plutôt que recopiées depuis le tableau
 * de `docs/03` §1 : {@code GRID_SIZE = 98} est un résultat, pas une donnée.
 */
public final class BingoBoardLayout {

	public static final int PADDING = 4;

	/** Taille d'un emplacement vanilla : l'item 16×16 y est centré sans calcul supplémentaire. */
	public static final int CELL_SIZE = 18;
	public static final int CELL_GAP = 2;
	public static final int CELL_PITCH = CELL_SIZE + CELL_GAP;

	/** {@code 5 × 20 − 2 = 98} : le dernier écart ne compte pas. */
	public static final int GRID_SIZE = BingoBoard.SIZE * CELL_PITCH - CELL_GAP;

	public static final int TITLE_H = 12;

	/** Pied de score, absent si {@code reveal_opponent_progress} est désactivé (`docs/03` §1). */
	public static final int FOOTER_H = 10;

	public static final int PANEL_W = 2 * PADDING + GRID_SIZE;

	// ── Couleurs (`docs/03` §2) ────────────────────────────────────────────────

	/**
	 * Les couleurs de <em>fond</em> et de <em>bordure</em> ont quitté cette classe au lot 4 : elles
	 * sont maintenant dans {@code panel.png} et {@code cell.png} (`docs/03` §6). Les garder ici en
	 * double garantirait qu'un jour l'une des deux versions change sans l'autre.
	 *
	 * <p>Ne restent que les couleurs qui n'ont pas de sprite parce qu'elles se superposent à l'icône
	 * ou au texte — un voile translucide ne peut pas être pré-dessiné sous l'item qu'il assombrit.
	 */
	public static final int VEIL_DONE = 0x8022CC44;
	public static final int VEIL_WINNING = 0x40FFDD00;
	public static final int TEXT_PRIMARY = 0xFFFFFFFF;
	public static final int TEXT_MUTED = 0xFFAAAAAA;

	private BingoBoardLayout() {
	}

	/** Hauteur totale : 128 avec le pied de score, 118 sans (`docs/03` §1). */
	public static int panelHeight(boolean withFooter) {
		return 2 * PADDING + TITLE_H + GRID_SIZE + (withFooter ? FOOTER_H : 0);
	}

	/**
	 * Échelle d'affichage, {@code hud_scale} de la config client (0,75 → 1,5, tâche 4.13).
	 *
	 * <p>Tout passe par cette méthode — rendu, hit-test, test de débordement — ce qui garantit
	 * qu'ils ne peuvent pas diverger quand le joueur change le réglage en cours de partie.
	 */
	public static float scale() {
		return BingoClientConfig.hudScale();
	}

	/** Marge gauche, {@code hud_margin_x} (`docs/05` §4.3). */
	public static int originX() {
		return BingoClientConfig.hudMarginX();
	}

	/**
	 * Marge haute, {@code hud_margin_y}.
	 *
	 * <p>Configurable pour une raison précise (`docs/03` §4) : 8 px chevauche le titre de barre de
	 * boss sous certains packs de ressources.
	 */
	public static int originY() {
		return BingoClientConfig.hudMarginY();
	}

	/** Centre du panneau — origine des étincelles de la finale (`docs/04` §4). */
	public static int centerX() {
		return originX() + PANEL_W / 2;
	}

	public static int centerY(boolean withFooter) {
		return originY() + panelHeight(withFooter) / 2;
	}

	/** Abscisse du coin haut-gauche d'une case. */
	public static int cellX(int col) {
		return originX() + PADDING + col * CELL_PITCH;
	}

	/** Ordonnée du coin haut-gauche d'une case. */
	public static int cellY(int row) {
		return originY() + PADDING + TITLE_H + row * CELL_PITCH;
	}

	/**
	 * Index de la case sous le curseur (`docs/03` §1).
	 *
	 * <p>Les coordonnées sont divisées par {@link #scale()} avant le test : l'échelle est appliquée
	 * en {@code matrices.scale()} au rendu, donc la souris vit dans l'espace non mis à l'échelle.
	 * L'oublier décale le hit-test d'autant.
	 *
	 * @return l'index {@code row * 5 + col}, ou vide si le curseur est hors grille ou dans un écart
	 *         entre deux cases.
	 */
	public static OptionalInt hitTest(double mouseX, double mouseY) {
		double scaled = scale();
		int localX = (int) (mouseX / scaled) - originX() - PADDING;
		int localY = (int) (mouseY / scaled) - originY() - PADDING - TITLE_H;

		if (localX < 0 || localY < 0) {
			return OptionalInt.empty();
		}

		int col = localX / CELL_PITCH;
		int row = localY / CELL_PITCH;

		boolean inCell = col >= 0 && col < BingoBoard.SIZE
				&& row >= 0 && row < BingoBoard.SIZE
				// Le reste dans l'écart n'est pas dans la case : sans ce test, les 2 px de gap
				// seraient attribués à la case précédente et le clic « raterait » visuellement.
				&& (localX % CELL_PITCH) < CELL_SIZE
				&& (localY % CELL_PITCH) < CELL_SIZE;

		return inCell ? OptionalInt.of(BingoBoard.index(row, col)) : OptionalInt.empty();
	}

	/** Le HUD tient-il dans la fenêtre courante ? Sert à ne pas dessiner hors écran. */
	public static boolean fitsOnScreen(MinecraftClient client, boolean withFooter) {
		int width = (int) (PANEL_W * scale());
		int height = (int) (panelHeight(withFooter) * scale());
		return originX() + width <= client.getWindow().getScaledWidth()
				&& originY() + height <= client.getWindow().getScaledHeight();
	}
}
