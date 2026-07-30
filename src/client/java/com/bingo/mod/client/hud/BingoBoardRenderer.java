package com.bingo.mod.client.hud;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.client.BingoClientState;
import com.bingo.mod.client.roll.RollAnimationState;
import com.bingo.mod.client.roll.RollSparks;
import com.bingo.mod.game.phase.GamePhase;
import com.bingo.mod.network.payload.ObjectiveProjection;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.network.payload.TeamSnapshot;
import com.bingo.mod.util.BingoConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Dessin de la grille, partagé par le HUD et l'écran cliquable (`docs/03` §1-2).
 *
 * <p>Un seul chemin de rendu pour les deux composants : c'est la contrepartie de
 * {@link BingoBoardLayout}. Le HUD n'est pas cliquable, l'écran l'est, mais ils doivent produire
 * des pixels identiques — sinon l'illusion « le HUD devient cliquable » se voit au moment où
 * l'écran s'ouvre.
 *
 * <p>Le panneau et les cases sont texturés depuis le lot 4 ({@code panel.png} en 9-slice,
 * {@code cell.png} en atlas de 4 états, {@code check.png}). Tout ce qui reste en {@code fill()} y
 * reste pour une raison : voiles translucides posés <em>par-dessus</em> l'icône, pastilles à la
 * couleur d'équipe, flash de verrouillage et étincelles — autant de couleurs décidées à l'exécution,
 * qu'aucun sprite ne peut porter.
 */
public final class BingoBoardRenderer {

	/** Aucune case survolée — le cas du HUD, qui ne reçoit jamais la souris. */
	public static final int NO_HOVER = -1;

	// ── Textures (`docs/03` §6, tâche 4.10) ────────────────────────────────────

	private static final Identifier PANEL_TEXTURE = BingoConstants.id("textures/gui/hud/panel.png");
	private static final Identifier CELL_TEXTURE = BingoConstants.id("textures/gui/hud/cell.png");
	private static final Identifier CHECK_TEXTURE = BingoConstants.id("textures/gui/hud/check.png");

	/**
	 * Région 9-slice du panneau dans une feuille de 256×256.
	 *
	 * <p>La feuille <em>doit</em> faire 256×256 : {@code drawNineSlicedTexture} délègue à la
	 * surcharge de {@code drawTexture} qui suppose cette taille, et les UV seraient faux sur une
	 * feuille plus petite. Le reste de la feuille est transparent.
	 */
	private static final int PANEL_REGION = 64;
	private static final int PANEL_CORNER = 4;

	/** Atlas des 4 états de case, 18×18 chacun, en 2×2 dans une feuille de 64×64. */
	private static final int CELL_SHEET = 64;
	private static final int CELL_NORMAL_U = 0;
	private static final int CELL_NORMAL_V = 0;
	private static final int CELL_HOVERED_U = 18;
	private static final int CELL_HOVERED_V = 0;
	private static final int CELL_DONE_U = 0;
	private static final int CELL_DONE_V = 18;
	private static final int CELL_GOLD_U = 18;
	private static final int CELL_GOLD_V = 18;

	private static final int CHECK_SIZE = 8;

	private BingoBoardRenderer() {
	}

	/**
	 * Dessine le panneau complet à la position de {@link BingoBoardLayout}.
	 *
	 * @param hoveredIndex index survolé, ou {@link #NO_HOVER}
	 */
	public static void render(DrawContext context, TextRenderer textRenderer, int hoveredIndex) {
		boolean footer = BingoClientState.revealOpponentProgress();
		int originX = BingoBoardLayout.originX();
		int originY = BingoBoardLayout.originY();
		float scale = BingoBoardLayout.scale();

		context.getMatrices().push();
		if (scale != 1.0f) {
			context.getMatrices().scale(scale, scale, 1.0f);
		}

		// Les textures du panneau et des cases sont translucides : sans mélange explicite, elles
		// s'affichent sur fond noir opaque. L'état de mélange du HUD n'est pas garanti au moment où
		// notre callback passe, donc on le pose plutôt que de l'espérer.
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		context.drawNineSlicedTexture(PANEL_TEXTURE,
				originX, originY,
				BingoBoardLayout.PANEL_W, BingoBoardLayout.panelHeight(footer),
				PANEL_CORNER, PANEL_REGION, PANEL_REGION, 0, 0);

		renderTitleBar(context, textRenderer, originX, originY);

		// Calculé une fois pour les 25 cases : il coûte un balayage des 12 combinaisons, ce qui n'est
		// rien une fois par frame et commence à compter 25 fois par frame.
		int highlight = BingoClientState.highlightMask();

		for (int index = 0; index < BingoBoard.TILE_COUNT; index++) {
			renderCell(context, textRenderer, index, index == hoveredIndex, highlight);
		}

		if (footer) {
			renderFooter(context, textRenderer, originX,
					originY + BingoBoardLayout.panelHeight(true) - BingoBoardLayout.PADDING
							- BingoBoardLayout.FOOTER_H);
		}

		// Après la grille : les étincelles doivent passer devant, sinon elles ont l'air de jaillir
		// de derrière le panneau (`docs/04` §4).
		RollSparks.render(context);

		RenderSystem.disableBlend();
		context.getMatrices().pop();
	}

	// ── Barre de titre ────────────────────────────────────────────────────────

	private static void renderTitleBar(DrawContext context, TextRenderer textRenderer, int originX, int originY) {
		int textY = originY + BingoBoardLayout.PADDING + 2;

		context.drawText(textRenderer, Text.translatable(BingoConstants.key("hud.title")),
				originX + BingoBoardLayout.PADDING, textY, BingoBoardLayout.TEXT_PRIMARY, true);

		Text right = titleBarRight();
		int width = textRenderer.getWidth(right);
		context.drawText(textRenderer, right,
				originX + BingoBoardLayout.PANEL_W - BingoBoardLayout.PADDING - width, textY,
				BingoBoardLayout.TEXT_PRIMARY, true);
	}

	/**
	 * Contenu de droite : le chrono en manche, l'état de la phase sinon.
	 *
	 * <p>Le chrono affiché est le temps <em>restant</em> et non l'écoulé : c'est la seule des deux
	 * valeurs sur laquelle une équipe prend une décision.
	 */
	private static Text titleBarRight() {
		GamePhase phase = BingoClientState.phase();
		if (phase == GamePhase.COUNTDOWN) {
			int seconds = BingoClientState.phaseSecondsLeft();
			return Text.translatable(BingoConstants.key("phase.countdown"),
					Math.max(seconds, 0));
		}
		if (phase == GamePhase.ROLLING || phase == GamePhase.LOBBY) {
			return phase.displayName();
		}
		if (phase == GamePhase.PAUSED) {
			return phase.displayName();
		}
		return Text.literal(formatClock(BingoClientState.remainingSeconds()));
	}

	private static String formatClock(int seconds) {
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	// ── Cases (`docs/03` §2) ───────────────────────────────────────────────────

	private static void renderCell(DrawContext context,
	                               TextRenderer textRenderer,
	                               int index,
	                               boolean hovered,
	                               int highlightMask) {
		int x = BingoBoardLayout.cellX(BingoBoard.col(index));
		int y = BingoBoardLayout.cellY(BingoBoard.row(index));

		if (RollAnimationState.isActive()) {
			renderRollingCell(context, index, x, y);
			return;
		}

		Optional<TeamSnapshot> myTeam = BingoClientState.myTeam();
		boolean doneByMe = myTeam.map(team -> team.isCompleted(index)).orElse(false);
		boolean onWinningLine = (highlightMask & 1 << index) != 0;

		// Couches 1 et 2 : fond et bordure, en un seul sprite de l'atlas. L'ordre de priorité est
		// celui de l'urgence de l'information : la dorée passe devant le survol, parce que pendant
		// les 10 s de FINISHED c'est elle qui compte ; la verte passe derrière, parce que la coche
		// dit déjà la même chose.
		drawCellFrame(context, x, y, onWinningLine, hovered, doneByMe);

		// Couche 3 : icône.
		context.drawItem(iconStack(index), x + 1, y + 1);

		// Couche 4 : voile d'état.
		if (doneByMe) {
			context.fill(x + 1, y + 1, x + BingoBoardLayout.CELL_SIZE - 1,
					y + BingoBoardLayout.CELL_SIZE - 1, BingoBoardLayout.VEIL_DONE);
			drawCheck(context, x + 5, y + 5);
		} else if (onWinningLine) {
			context.fill(x + 1, y + 1, x + BingoBoardLayout.CELL_SIZE - 1,
					y + BingoBoardLayout.CELL_SIZE - 1, BingoBoardLayout.VEIL_WINNING);
		}

		// Couche 5 : pastilles adverses.
		renderOpponentPips(context, index, myTeam.orElse(null), x, y);

		// Couche 6 : badge d'avancement.
		renderProgressBadge(context, textRenderer, index, myTeam.orElse(null), x, y);
	}

	/**
	 * Une case pendant le tirage (`docs/04` §2, tâches 4.3 à 4.5).
	 *
	 * <p>Volontairement dépouillée : ni voile, ni pastille adverse, ni badge d'avancement. Aucune de
	 * ces informations n'a de sens avant que la manche commence, et les afficher sur une icône qui
	 * change toutes les 100 ms produirait un scintillement illisible.
	 *
	 * <p>Le punch d'échelle est appliqué <em>autour du centre de la case</em> : un simple
	 * {@code scale()} depuis l'origine de l'écran ferait dériver les cases vers le coin bas-droit à
	 * mesure qu'elles grossissent.
	 */
	private static void renderRollingCell(DrawContext context, int index, int x, int y) {
		int row = BingoBoard.row(index);
		boolean revealed = RollAnimationState.isRowLocked(row);
		float punch = RollAnimationState.punchScale(row);

		context.getMatrices().push();
		if (punch != 1.0f) {
			float centerX = x + BingoBoardLayout.CELL_SIZE / 2.0f;
			float centerY = y + BingoBoardLayout.CELL_SIZE / 2.0f;
			context.getMatrices().translate(centerX, centerY, 0.0f);
			context.getMatrices().scale(punch, punch, 1.0f);
			context.getMatrices().translate(-centerX, -centerY, 0.0f);
		}

		// Cadre doré au moment du verrou : c'est ce qui donne la sensation que la case « tombe »
		// dans sa position définitive.
		drawCellFrame(context, x, y, revealed, false, false);

		// Remplacement sec de l'icône, sans défilement vertical : dans 18 px un scroll est illisible
		// et coûte un clipping par case (`docs/04` §2.1).
		context.drawItem(revealed ? iconStack(index) : RollAnimationState.decoyStack(index), x + 1, y + 1);

		int flash = RollAnimationState.flashAlpha(row);
		if (flash > 0) {
			context.fill(x, y, x + BingoBoardLayout.CELL_SIZE, y + BingoBoardLayout.CELL_SIZE,
					flash << 24 | 0x00FFFFFF);
		}

		context.getMatrices().pop();
	}

	/**
	 * Au plus trois pastilles, aux coins haut-gauche, haut-droit et bas-gauche.
	 *
	 * <p>Le bas-droit est réservé au badge de compte. Avec {@code max_teams} à 4, il y a au plus 3
	 * équipes adverses : les trois emplacements suffisent exactement, aucun débordement à gérer
	 * (`docs/03` §2).
	 */
	private static void renderOpponentPips(DrawContext context, int index, TeamSnapshot myTeam, int x, int y) {
		if (!BingoClientState.revealOpponentProgress()) {
			return;
		}
		int slot = 0;
		for (TeamSnapshot team : BingoClientState.teams()) {
			if (myTeam != null && team.id().equals(myTeam.id())) {
				continue;
			}
			if (!team.isCompleted(index) || slot >= 3) {
				continue;
			}
			int pipX = slot == 1 ? x + BingoBoardLayout.CELL_SIZE - 4 : x + 1;
			int pipY = slot == 2 ? y + BingoBoardLayout.CELL_SIZE - 4 : y + 1;
			int color = 0xFF000000 | (team.color().getColorValue() == null ? 0xFFFFFF : team.color().getColorValue());
			context.fill(pipX, pipY, pipX + 3, pipY + 3, color);
			slot++;
		}
	}

	/**
	 * Badge {@code 3/8} en bas-droite, seulement si {@code count > 1}.
	 *
	 * <p>Dessiné à la moitié de l'échelle : « 3/8 » fait 17 px au corps normal, soit la largeur
	 * entière de la case. C'est le seul endroit du HUD où la police est réduite.
	 */
	private static void renderProgressBadge(DrawContext context,
	                                        TextRenderer textRenderer,
	                                        int index,
	                                        TeamSnapshot myTeam,
	                                        int x,
	                                        int y) {
		Optional<ObjectiveProjection> objective = BingoClientState.objectiveAt(index);
		if (objective.isEmpty() || objective.get().count() <= 1) {
			return;
		}
		int count = objective.get().count();
		int progress = myTeam == null ? 0 : Math.min(myTeam.progressAt(index), count);
		if (myTeam != null && myTeam.isCompleted(index)) {
			return;
		}

		Text badge = Text.literal(progress + "/" + count);
		int width = textRenderer.getWidth(badge);

		context.getMatrices().push();
		context.getMatrices().translate(
				x + BingoBoardLayout.CELL_SIZE - 1 - width / 2.0f,
				y + BingoBoardLayout.CELL_SIZE - 5.0f,
				0.0f);
		context.getMatrices().scale(0.5f, 0.5f, 1.0f);
		context.drawText(textRenderer, badge, 0, 0, BingoBoardLayout.TEXT_PRIMARY, true);
		context.getMatrices().pop();
	}

	/**
	 * Le cadre d'une case, pris dans l'atlas des 4 états (`docs/03` §6).
	 *
	 * <p>Le fond translucide et la bordure sont dans le même sprite : deux appels de dessin par case
	 * deviennent un, et surtout les quatre états restent cohérents entre eux — ils sont côte à côte
	 * dans le fichier, pas éparpillés dans quatre constantes de couleur.
	 */
	private static void drawCellFrame(DrawContext context, int x, int y,
	                                  boolean golden, boolean hovered, boolean done) {
		int u = CELL_NORMAL_U;
		int v = CELL_NORMAL_V;
		if (golden) {
			u = CELL_GOLD_U;
			v = CELL_GOLD_V;
		} else if (hovered) {
			u = CELL_HOVERED_U;
			v = CELL_HOVERED_V;
		} else if (done) {
			u = CELL_DONE_U;
			v = CELL_DONE_V;
		}
		context.drawTexture(CELL_TEXTURE, x, y, u, v,
				BingoBoardLayout.CELL_SIZE, BingoBoardLayout.CELL_SIZE, CELL_SHEET, CELL_SHEET);
	}

	/** Coche de validation, {@code check.png} 8×8 (`docs/03` §6). */
	private static void drawCheck(DrawContext context, int x, int y) {
		context.drawTexture(CHECK_TEXTURE, x, y, 0, 0, CHECK_SIZE, CHECK_SIZE, CHECK_SIZE, CHECK_SIZE);
	}

	/**
	 * L'item à dessiner dans la case.
	 *
	 * <p>Garde-fou 3 de `docs/06` §3.4 : un identifiant absent du catalogue donne une barrière et
	 * non un crash. Le tooltip de l'écran affiche alors l'ID brut, ce qui suffit à diagnostiquer un
	 * datapack désynchronisé.
	 */
	public static ItemStack iconStack(int index) {
		Optional<ObjectiveProjection> objective = BingoClientState.objectiveAt(index);
		if (objective.isEmpty()) {
			return new ItemStack(Items.BARRIER);
		}
		Identifier icon = objective.get().icon();
		ItemStack stack = new ItemStack(Registries.ITEM.get(icon));
		stack.setCount(Math.max(1, objective.get().iconCount()));
		return stack;
	}

	// ── Pied de score ─────────────────────────────────────────────────────────

	/**
	 * Pastille de couleur et nombre de cases par équipe.
	 *
	 * <p>Écart assumé avec la maquette de `docs/03` §1, qui montre les noms d'équipe : à 98 px de
	 * large, quatre noms ne tiennent pas. La pastille porte la même information — l'identité de
	 * l'équipe est sa couleur, partout ailleurs dans le HUD — et {@code /bingo score} donne le
	 * détail complet.
	 */
	private static void renderFooter(DrawContext context, TextRenderer textRenderer, int originX, int y) {
		List<ScoreUpdatePayload.Entry> scores = BingoClientState.scores();
		int x = originX + BingoBoardLayout.PADDING;

		for (ScoreUpdatePayload.Entry entry : scores) {
			Optional<TeamSnapshot> team = BingoClientState.team(entry.teamId());
			int color = team
					.map(snapshot -> snapshot.color().getColorValue())
					.map(value -> 0xFF000000 | value)
					.orElse(0xFFFFFFFF);

			context.fill(x, y + 2, x + 5, y + 7, color);
			Text count = Text.literal(String.valueOf(entry.tileCount()));
			context.drawText(textRenderer, count, x + 7, y + 1, BingoBoardLayout.TEXT_PRIMARY, true);
			x += 7 + textRenderer.getWidth(count) + 5;
		}

		if (scores.isEmpty()) {
			context.drawText(textRenderer, Text.translatable(BingoConstants.key("hud.no_teams")),
					x, y + 1, BingoBoardLayout.TEXT_MUTED, true);
		}
	}

	/** Le renderer de texte courant, pour les appelants qui n'ont pas le client sous la main. */
	public static TextRenderer textRenderer() {
		return MinecraftClient.getInstance().textRenderer;
	}
}
