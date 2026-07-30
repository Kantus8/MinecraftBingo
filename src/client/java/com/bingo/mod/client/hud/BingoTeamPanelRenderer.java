package com.bingo.mod.client.hud;

import com.bingo.mod.client.BingoClientState;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.network.payload.TeamSnapshot;
import com.bingo.mod.util.BingoConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tableau des équipes, coin haut-droit du HUD : composition et points individuels.
 *
 * <p>Il répond à trois questions que la grille ne peut pas porter — qui joue, avec qui, et pour
 * combien de points cumulés — et il y répond <strong>en permanence</strong>, salon compris
 * ({@code BingoClientState#shouldRenderTeamPanel}).
 *
 * <p>Deux unités différentes, volontairement : la ligne d'équipe compte des <em>cases</em>, la ligne
 * de joueur des <em>points</em>. C'est ce qui distingue d'un coup d'œil l'avancement de la manche en
 * cours du total qui, lui, traverse les manches ({@code PlayerPoints}).
 *
 * <p>Le nombre de cases d'une équipe adverse suit {@code reveal_opponent_progress} comme le pied de
 * score de la grille (`docs/03` §1) : un panneau qui divulgue l'avancement adverse quand la règle
 * l'interdit contournerait le réglage par la fenêtre. Les points individuels, eux, ne sont pas de
 * l'avancement de manche — {@code /bingo points} les donne à tout le monde — et restent affichés.
 *
 * <p>Le rendu se fait en deux passes : mesurer, puis dessiner. Une seule passe imposerait de choisir
 * la largeur avant de connaître le contenu, donc de la fixer à la plus longue valeur imaginable.
 */
public final class BingoTeamPanelRenderer {

	/** Points d'un joueur : la seule couleur chaude du panneau, parce que c'est ce qu'on y cherche. */
	private static final int TEXT_POINTS = 0xFFFFD54F;

	/** Voile de la ligne du joueur local, assez discret pour ne pas concurrencer la couleur d'équipe. */
	private static final int VEIL_SELF = 0x33FFFFFF;

	/** Repli de pastille pour une couleur d'équipe sans valeur RGB ({@code Formatting.RESET}, etc.). */
	private static final int PIP_FALLBACK = 0xFFFFFFFF;

	/**
	 * Une ligne prête à dessiner, mesurable sans être encore positionnée.
	 *
	 * <p>Les libellés sont des {@code String} et non des {@code Text} : ils doivent pouvoir être
	 * tronqués, et {@link TextRenderer#trimToWidth(String, int)} est la seule surcharge dont le
	 * résultat se redessine tel quel. La couleur est portée à côté, donc rien n'est perdu.
	 *
	 * @param pipColor couleur de la pastille d'équipe, {@code 0} pour une ligne sans pastille
	 */
	private record Row(int indent, String left, int leftColor, String right, int rightColor,
	                   int pipColor, boolean highlight) {

		static Row team(String name, int color, String tiles) {
			return new Row(0, name, color, tiles, BingoBoardLayout.TEXT_MUTED, color, false);
		}

		static Row member(String name, String points, boolean self) {
			return new Row(BingoTeamPanelLayout.MEMBER_INDENT, name,
					BingoBoardLayout.TEXT_PRIMARY, points, TEXT_POINTS, 0, self);
		}

		static Row muted(int indent, String label) {
			return new Row(indent, label, BingoBoardLayout.TEXT_MUTED, "", 0, 0, false);
		}

		/** Largeur du décor à gauche du libellé : retrait et pastille. */
		int leadWidth() {
			return indent + (pipColor == 0 ? 0 : BingoTeamPanelLayout.PIP_SIZE + 2);
		}
	}

	private BingoTeamPanelRenderer() {
	}

	/** Dessine le panneau à la position de {@link BingoTeamPanelLayout}. Sans effet s'il déborde. */
	public static void render(DrawContext context, TextRenderer textRenderer) {
		MinecraftClient client = MinecraftClient.getInstance();
		List<Row> rows = clamp(rows(), BingoTeamPanelLayout.maxRows(client));

		int width = BingoTeamPanelLayout.panelWidth(contentWidth(textRenderer, rows));
		if (!BingoTeamPanelLayout.fitsOnScreen(client, width)) {
			return;
		}

		float scale = BingoTeamPanelLayout.scale();
		int originX = BingoTeamPanelLayout.originX(client, width);
		int originY = BingoTeamPanelLayout.originY();

		context.getMatrices().push();
		if (scale != 1.0f) {
			context.getMatrices().scale(scale, scale, 1.0f);
		}

		// Même raison que pour la grille : la texture du panneau est translucide et l'état de mélange
		// du HUD n'est pas garanti au moment où notre callback passe.
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		BingoBoardRenderer.drawPanel(context, originX, originY, width,
				BingoTeamPanelLayout.panelHeight(rows.size()));

		context.drawText(textRenderer, Text.translatable(BingoConstants.key("hud.teams.title")),
				originX + BingoTeamPanelLayout.PADDING,
				originY + BingoTeamPanelLayout.PADDING + 2,
				BingoBoardLayout.TEXT_PRIMARY, true);

		int y = originY + BingoTeamPanelLayout.PADDING + BingoTeamPanelLayout.TITLE_H;
		for (Row row : rows) {
			drawRow(context, textRenderer, row, originX, y, width);
			y += BingoTeamPanelLayout.LINE_H;
		}

		RenderSystem.disableBlend();
		context.getMatrices().pop();
	}

	// ── Modèle ────────────────────────────────────────────────────────────────

	/**
	 * Les lignes à afficher : une par équipe, puis une par membre.
	 *
	 * <p>Les équipes gardent leur ordre de création, comme dans {@code /bingo team list} et au pied de
	 * la grille — les trier par score ferait sauter les lignes d'une équipe à l'autre en pleine partie,
	 * alors que ce panneau sert d'abord à lire une composition. Les membres gardent de même leur ordre
	 * d'arrivée dans l'équipe.
	 */
	private static List<Row> rows() {
		List<TeamSnapshot> teams = BingoClientState.teams();
		if (teams.isEmpty()) {
			return List.of(Row.muted(0, Text.translatable(BingoConstants.key("hud.no_teams")).getString()));
		}

		UUID self = BingoClientState.self();
		Optional<TeamSnapshot> myTeam = BingoClientState.myTeam();
		boolean reveal = BingoClientState.revealOpponentProgress();

		List<Row> rows = new ArrayList<>();
		for (TeamSnapshot team : teams) {
			boolean mine = myTeam.isPresent() && myTeam.get().id().equals(team.id());
			rows.add(Row.team(team.name().getString(), pipColor(team), tileLabel(team, reveal || mine)));

			if (team.members().isEmpty()) {
				rows.add(Row.muted(BingoTeamPanelLayout.MEMBER_INDENT,
						Text.translatable(BingoConstants.key("hud.teams.no_members")).getString()));
				continue;
			}
			for (UUID member : team.members()) {
				rows.add(Row.member(
						BingoClientState.playerName(member),
						Text.translatable(BingoConstants.key("hud.points"),
								BingoClientState.playerPoints(member)).getString(),
						member.equals(self)));
			}
		}
		return rows;
	}

	/**
	 * Tronque la liste à ce que la fenêtre peut afficher, la dernière ligne disant ce qui manque.
	 *
	 * <p>La ligne de débordement remplace la dernière visible au lieu de s'y ajouter : sans quoi le
	 * panneau dépasserait d'exactement la ligne qu'elle est censée annoncer.
	 */
	private static List<Row> clamp(List<Row> rows, int maxRows) {
		if (rows.size() <= maxRows) {
			return rows;
		}
		List<Row> clamped = new ArrayList<>(rows.subList(0, Math.max(0, maxRows - 1)));
		clamped.add(Row.muted(0, "+" + (rows.size() - clamped.size())));
		return clamped;
	}

	/**
	 * Nombre de cases validées par l'équipe, chaîne vide si l'information est cachée.
	 *
	 * <p>La valeur vient de {@code score_update} et non du masque de complétion de l'instantané : les
	 * deux disent la même chose, mais le pied de la grille lit déjà le premier et deux sources
	 * finiraient par afficher deux nombres différents pendant un tick.
	 */
	private static String tileLabel(TeamSnapshot team, boolean visible) {
		if (!visible || !BingoClientState.hasCard()) {
			return "";
		}
		return BingoClientState.scores().stream()
				.filter(entry -> entry.teamId().equals(team.id()))
				.findFirst()
				.map(ScoreUpdatePayload.Entry::tileCount)
				.map(count -> Text.translatable(BingoConstants.key("hud.teams.tiles"), count).getString())
				.orElse("");
	}

	private static int pipColor(TeamSnapshot team) {
		Integer value = team.color().getColorValue();
		return value == null ? PIP_FALLBACK : 0xFF000000 | value;
	}

	// ── Mesure et dessin ──────────────────────────────────────────────────────

	/** Largeur du contenu le plus large, titre compris — la seule entrée de {@code panelWidth}. */
	private static int contentWidth(TextRenderer textRenderer, List<Row> rows) {
		int widest = textRenderer.getWidth(Text.translatable(BingoConstants.key("hud.teams.title")));
		for (Row row : rows) {
			int right = row.right().isEmpty() ? 0 : BingoTeamPanelLayout.COLUMN_GAP
					+ textRenderer.getWidth(row.right());
			widest = Math.max(widest, row.leadWidth() + textRenderer.getWidth(row.left()) + right);
		}
		return widest;
	}

	/**
	 * Une ligne : voile éventuel, pastille, libellé tronqué à gauche, nombre aligné à droite.
	 *
	 * <p>L'alignement à droite est calculé depuis le bord du panneau et non depuis la fin du libellé :
	 * c'est ce qui met les points en colonne, seule façon de les comparer d'un coup d'œil.
	 */
	private static void drawRow(DrawContext context, TextRenderer textRenderer, Row row,
	                            int originX, int y, int width) {
		int left = originX + BingoTeamPanelLayout.PADDING;
		int right = originX + width - BingoTeamPanelLayout.PADDING;

		if (row.highlight()) {
			context.fill(left - 1, y - 1, right + 1, y + BingoTeamPanelLayout.LINE_H - 2, VEIL_SELF);
		}

		if (row.pipColor() != 0) {
			context.fill(left, y + 2, left + BingoTeamPanelLayout.PIP_SIZE,
					y + 2 + BingoTeamPanelLayout.PIP_SIZE, row.pipColor());
		}

		int rightWidth = row.right().isEmpty() ? 0 : textRenderer.getWidth(row.right());
		if (rightWidth > 0) {
			context.drawText(textRenderer, row.right(), right - rightWidth, y + 1,
					row.rightColor(), true);
		}

		// Le libellé est tronqué sur la place qui reste après le nombre, jamais l'inverse : un nom
		// coupé reste reconnaissable, un total coupé devient faux.
		int available = right - (left + row.leadWidth())
				- (rightWidth == 0 ? 0 : rightWidth + BingoTeamPanelLayout.COLUMN_GAP);
		context.drawText(textRenderer, trim(textRenderer, row.left(), available),
				left + row.leadWidth(), y + 1, row.leftColor(), true);
	}

	/** Tronque avec une ellipse, en réservant sa largeur pour ne pas la voir déborder à son tour. */
	private static String trim(TextRenderer textRenderer, String label, int available) {
		if (available <= 0) {
			return "";
		}
		if (textRenderer.getWidth(label) <= available) {
			return label;
		}
		int ellipsis = textRenderer.getWidth("…");
		return textRenderer.trimToWidth(label, Math.max(0, available - ellipsis)) + "…";
	}
}
