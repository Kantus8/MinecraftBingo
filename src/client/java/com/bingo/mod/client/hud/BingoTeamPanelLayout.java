package com.bingo.mod.client.hud;

import com.bingo.mod.client.config.BingoClientConfig;
import net.minecraft.client.MinecraftClient;

/**
 * <strong>Source unique</strong> des constantes de layout du tableau des équipes (coin haut-droit).
 *
 * <p>Même rôle que {@link BingoBoardLayout} pour la grille, et même raison d'exister : le panneau est
 * dessiné à deux endroits — l'overlay HUD et {@code BingoBoardScreen} — qui doivent produire des
 * pixels identiques.
 *
 * <p>Deux différences de fond avec la grille :
 * <ul>
 *   <li><strong>ancrage à droite</strong> : l'abscisse se déduit de la largeur de la fenêtre, donc
 *       elle change avec la fenêtre <em>et</em> avec le contenu ;</li>
 *   <li><strong>largeur variable</strong> : une grille fait toujours 98 px, un nom de joueur fait
 *       ce qu'il fait. La largeur est donc mesurée sur le contenu, puis bornée — sans borne haute,
 *       un pseudo de 16 caractères déciderait de la taille du panneau.</li>
 * </ul>
 */
public final class BingoTeamPanelLayout {

	public static final int PADDING = 4;

	/** Hauteur du titre, alignée sur celle de la grille pour que les deux panneaux se répondent. */
	public static final int TITLE_H = 12;

	/** Hauteur d'une ligne d'équipe comme de joueur : un interligne unique se lit comme une liste. */
	public static final int LINE_H = 10;

	/** Retrait des lignes de joueur sous leur équipe. */
	public static final int MEMBER_INDENT = 8;

	/** Côté de la pastille de couleur d'équipe, identique aux pastilles du pied de score. */
	public static final int PIP_SIZE = 5;

	/** Écart minimal entre un nom et le nombre aligné à droite, pour qu'ils ne se touchent jamais. */
	public static final int COLUMN_GAP = 8;

	public static final int MIN_W = 84;

	/**
	 * Largeur maximale.
	 *
	 * <p>Au-delà, les noms sont tronqués avec une ellipse : un panneau qui grandit jusqu'au tiers de
	 * l'écran parce qu'un joueur s'appelle {@code xXx_DarkSlayer_xXx} coûte plus de lisibilité qu'il
	 * n'en apporte.
	 */
	public static final int MAX_W = 150;

	private BingoTeamPanelLayout() {
	}

	/** Hauteur totale pour un nombre donné de lignes (équipes et joueurs confondus). */
	public static int panelHeight(int lines) {
		return 2 * PADDING + TITLE_H + Math.max(1, lines) * LINE_H;
	}

	/** Le panneau partage l'échelle du HUD : {@code hud_scale} (tâche 4.13). */
	public static float scale() {
		return BingoBoardLayout.scale();
	}

	/**
	 * Abscisse du coin haut-gauche, déduite du bord droit de la fenêtre.
	 *
	 * <p>La largeur de fenêtre est divisée par l'échelle parce que celle-ci est appliquée en
	 * {@code matrices.scale()} au rendu : sans cette division, le panneau sortirait de l'écran dès que
	 * {@code hud_scale} dépasse 1.
	 */
	public static int originX(MinecraftClient client, int width) {
		int available = (int) (client.getWindow().getScaledWidth() / scale());
		return Math.max(0, available - BingoClientConfig.teamPanelMarginX() - width);
	}

	public static int originY() {
		return BingoClientConfig.teamPanelMarginY();
	}

	/** Largeur retenue pour un contenu mesuré, bornée à {@link #MIN_W}–{@link #MAX_W}. */
	public static int panelWidth(int contentWidth) {
		return Math.max(MIN_W, Math.min(MAX_W, 2 * PADDING + contentWidth));
	}

	/** Le panneau tient-il en largeur dans la fenêtre courante ? Sert à ne pas dessiner hors écran. */
	public static boolean fitsOnScreen(MinecraftClient client, int width) {
		int scaledWidth = (int) (width * scale());
		return scaledWidth + BingoClientConfig.teamPanelMarginX() <= client.getWindow().getScaledWidth();
	}

	/**
	 * Nombre de lignes affichables sans sortir par le bas de la fenêtre.
	 *
	 * <p>La hauteur est <em>bornée</em> plutôt que testée : un panneau qui disparaît entièrement parce
	 * qu'un opérateur a entassé six joueurs dans une équipe serait un bug aux yeux du joueur, alors
	 * qu'une liste tronquée d'une ligne « +3 » se lit pour ce qu'elle est.
	 *
	 * @return au moins 1 — sur une fenêtre trop basse, mieux vaut une ligne coupée que rien
	 */
	public static int maxRows(MinecraftClient client) {
		int available = (int) (client.getWindow().getScaledHeight() / scale())
				- originY() - 2 * PADDING - TITLE_H;
		return Math.max(1, available / LINE_H);
	}
}
