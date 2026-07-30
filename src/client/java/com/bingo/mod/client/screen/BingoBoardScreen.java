package com.bingo.mod.client.screen;

import com.bingo.mod.client.BingoClientState;
import com.bingo.mod.client.hud.BingoBoardLayout;
import com.bingo.mod.client.hud.BingoBoardRenderer;
import com.bingo.mod.client.integration.jei.BingoJeiBridge;
import com.bingo.mod.network.payload.ObjectiveProjection;
import com.bingo.mod.network.payload.TeamSnapshot;
import com.bingo.mod.objective.ObjectiveInteraction;
import com.bingo.mod.registry.BingoSounds;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * La grille rendue cliquable (`docs/03` en tête, §1, §3 ; tâche 2.13).
 *
 * <p>L'écran redessine la grille <strong>exactement</strong> à la position et à la taille du HUD,
 * <strong>sans assombrir l'arrière-plan</strong>. Résultat perçu par le joueur : il appuie sur
 * {@code B} et « le HUD devient cliquable ». C'est la seule façon propre d'obtenir ce comportement
 * dans Minecraft, et l'illusion tient parce que le rendu et le layout sont partagés avec l'overlay
 * ({@link BingoBoardRenderer}, {@link BingoBoardLayout}).
 *
 * <p>Le clic n'envoie <strong>rien</strong> au serveur (`docs/06` §3.2) : ouvrir JEI ou afficher un
 * tooltip est purement client.
 */
public class BingoBoardScreen extends Screen {

	/** Largeur de retour à la ligne des descriptions (`docs/03` §3.3). */
	private static final int TOOLTIP_WRAP = 200;

	/** Durée d'affichage de la mention « aucune recette » après un clic JEI infructueux. */
	private static final long NO_RECIPE_NOTICE_MS = 4_000L;

	/**
	 * Case dont le clic JEI n'a rien ouvert, et jusqu'à quand le signaler.
	 *
	 * <p>Sans cette trace, la dégradation de `docs/03` §3.1 serait invisible : le tooltip suit déjà
	 * le curseur, donc « retomber sur le tooltip » ne change rien à l'écran et le joueur ne
	 * distingue pas un objectif sans recette d'un clic ignoré. Une ligne temporaire dans le tooltip
	 * répond à la seule question qu'il se pose à cet instant.
	 */
	private int noRecipeIndex = BingoBoardRenderer.NO_HOVER;

	private long noRecipeUntilMs;

	private BingoBoardScreen() {
		super(Text.translatable(BingoConstants.key("screen.board.title")));
	}

	/**
	 * Ouvre l'écran, en demandant une resynchronisation au passage.
	 *
	 * <p>`docs/06` §3.2 place l'ouverture de l'écran parmi les déclencheurs de
	 * {@code request_sync} : c'est le moment où le joueur va lire attentivement une grille qu'il n'a
	 * peut-être regardée que du coin de l'œil pendant dix minutes.
	 */
	public static void open() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		BingoClientState.requestResync();
		client.setScreen(new BingoBoardScreen());
		client.getSoundManager().play(
				PositionedSoundInstance.master(BingoSounds.CARD_FLIP, 1.0f, 0.6f));
	}

	/**
	 * Pas d'assombrissement : {@link Screen#renderBackground} n'est volontairement pas appelé.
	 *
	 * <p>C'est le cœur de l'illusion. Un fond assombri annoncerait « un écran s'est ouvert » là où
	 * l'effet recherché est « le HUD a changé d'état ».
	 */
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		OptionalInt hovered = BingoBoardLayout.hitTest(mouseX, mouseY);
		BingoBoardRenderer.render(context, textRenderer, hovered.orElse(BingoBoardRenderer.NO_HOVER));

		super.render(context, mouseX, mouseY, delta);

		hovered.ifPresent(index ->
				context.drawOrderedTooltip(textRenderer, tooltip(index), mouseX, mouseY));
	}

	/**
	 * Routage du clic (`docs/03` §3).
	 *
	 * <pre>
	 * gauche → interaction de l'objectif : jei | tooltip | none
	 * droit  → toujours le tooltip, quel que soit le type
	 * </pre>
	 *
	 * <p>Le tooltip suit déjà le curseur en permanence dans cet écran : les branches
	 * {@code tooltip} n'ont donc rien à déclencher, elles confirment simplement au joueur que son
	 * clic a été pris en compte. Le clic droit est là pour lire la description d'un {@code CRAFT}
	 * sans que JEI s'ouvre.
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		OptionalInt hovered = BingoBoardLayout.hitTest(mouseX, mouseY);
		if (hovered.isEmpty()) {
			return super.mouseClicked(mouseX, mouseY, button);
		}
		int index = hovered.getAsInt();

		Optional<ObjectiveProjection> objective = BingoClientState.objectiveAt(index);
		if (objective.isEmpty()) {
			return true;
		}

		// La projection porte déjà l'interaction effective : le défaut dérivé du type a été résolu
		// côté serveur (`docs/01` §5), le client n'a pas à connaître cette table.
		boolean rightClick = button == 1;
		ObjectiveInteraction interaction = rightClick
				? ObjectiveInteraction.TOOLTIP
				: objective.get().interaction();

		switch (interaction) {
			case JEI -> openInJei(index, objective.get());
			case TOOLTIP -> clickFeedback();
			case NONE -> clickFeedback();
		}
		return true;
	}

	/**
	 * Clic gauche sur une case {@code CRAFT} ou {@code FIND} (`docs/03` §3.1).
	 *
	 * <p>Le repli n'est pas une précaution mais une nécessité : quand un item n'a aucune recette —
	 * {@code minecraft:ancient_debris} en est l'exemple canonique — {@code show()} ne fait rien de
	 * visible et ne le dit pas. Le seul signal exploitable est que l'écran courant n'a pas changé.
	 */
	private void openInJei(int index, ObjectiveProjection objective) {
		MinecraftClient client = MinecraftClient.getInstance();
		Screen before = client.currentScreen;

		boolean sent = BingoJeiBridge.showRecipe(objective);
		if (sent && client.currentScreen != before) {
			return;
		}

		BingoConstants.LOGGER.debug("Aucune recette JEI pour '{}' — repli sur le tooltip",
				objective.id());
		noRecipeIndex = index;
		noRecipeUntilMs = System.currentTimeMillis() + NO_RECIPE_NOTICE_MS;
		clickFeedback();
	}

	/**
	 * Retour sonore discret, volume 0,3 comme spécifié pour {@code none} (`docs/03` §3).
	 *
	 * <p>{@code .value()} : depuis 1.19.3, {@code SoundEvents.UI_BUTTON_CLICK} est une
	 * {@code RegistryEntry.Reference} et non un {@code SoundEvent}.
	 */
	private void clickFeedback() {
		MinecraftClient.getInstance().getSoundManager().play(
				PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.3f));
	}

	/**
	 * Contenu du tooltip (`docs/03` §3.3).
	 *
	 * <p>Un identifiant inconnu du catalogue produit un tooltip d'ID brut plutôt qu'un tooltip vide :
	 * c'est ce qui rend une désynchronisation de datapack diagnosticable sans lire les logs
	 * (garde-fou 3 de `docs/06` §3.4).
	 */
	private List<OrderedText> tooltip(int index) {
		List<OrderedText> lines = new ArrayList<>();
		Optional<ObjectiveProjection> found = BingoClientState.objectiveAt(index);

		if (found.isEmpty()) {
			Identifier raw = BingoClientState.tileId(index).orElse(null);
			lines.add(Text.translatable(BingoConstants.key("tooltip.unknown_objective"))
					.formatted(Formatting.RED).asOrderedText());
			lines.add(Text.literal(raw == null ? "?" : raw.toString())
					.formatted(Formatting.DARK_GRAY).asOrderedText());
			return lines;
		}

		ObjectiveProjection objective = found.get();

		MutableText title = objective.displayName().copy().formatted(Formatting.WHITE);
		if (objective.count() > 1) {
			title.append(Text.literal("  ×" + objective.count()).formatted(Formatting.GRAY));
		}
		lines.add(title.asOrderedText());

		// wrapLines rend des OrderedText : c'est précisément pourquoi tout le tooltip est construit
		// dans ce type plutôt qu'en Text — reconvertir une ligne coupée en Text perdrait le
		// découpage, seule chose que wrapLines apporte.
		objective.description().ifPresent(description -> {
			lines.add(OrderedText.EMPTY);
			lines.addAll(textRenderer.wrapLines(
					description.copy().formatted(Formatting.GRAY), TOOLTIP_WRAP));
		});

		lines.add(OrderedText.EMPTY);
		lines.add(Text.translatable(BingoConstants.key("tooltip.level_points"),
						objective.level(),
						BingoClientState.pointsBase() << (objective.level() - 1))
				.formatted(Formatting.DARK_GRAY).asOrderedText());

		if (index == noRecipeIndex && System.currentTimeMillis() < noRecipeUntilMs) {
			lines.add(OrderedText.EMPTY);
			lines.add(Text.translatable(BingoConstants.key("tooltip.no_recipe"))
					.formatted(Formatting.YELLOW).asOrderedText());
		}

		List<TeamSnapshot> completedBy = BingoClientState.teams().stream()
				.filter(team -> team.isCompleted(index))
				.toList();
		if (!completedBy.isEmpty()) {
			lines.add(Text.translatable(BingoConstants.key("tooltip.completed_by"))
					.formatted(Formatting.DARK_GRAY).asOrderedText());
			completedBy.forEach(team ->
					lines.add(Text.literal("  ").append(team.coloredName()).asOrderedText()));
		}

		return lines;
	}

	/** Le monde continue de tourner derrière l'écran : c'est un HUD cliquable, pas une pause. */
	@Override
	public boolean shouldPause() {
		return false;
	}
}
