package com.bingo.mod.game.team;

import com.bingo.mod.board.WinLines;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.objective.condition.EntityMatcher;
import com.bingo.mod.objective.condition.ItemMatcher;
import com.bingo.mod.objective.type.CraftTarget;
import com.bingo.mod.objective.type.FindTarget;
import com.bingo.mod.objective.type.KillMobTarget;
import com.bingo.mod.objective.type.ObjectiveType;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Index inversés des cases <strong>non encore validées</strong> d'une équipe (`docs/06` §6).
 *
 * <p>Le point chaud du mod est l'étape 3 du chemin critique : à chaque craft, chaque mort et
 * surtout à chaque scan d'inventaire, il faut savoir quelles cases peuvent réagir. Parcourir
 * les 25 objectifs à chaque événement est le piège que `docs/06` §6 désigne nommément ; ces
 * tables ramènent le coût à un lookup de hash.
 *
 * <p><strong>Cibles par tag.</strong> Un objectif dont la cible est un tag n'a pas de clé
 * {@link Item} ni {@link EntityType} unique, donc pas d'entrée dans les tables. Il tombe dans
 * une liste à part, testée en plus du lookup. Ces listes sont courtes (au plus 25 entrées, en
 * pratique 2 ou 3) et le prédicat de tag est de toute façon le seul moyen de les trancher.
 *
 * <p><strong>Immuable, reconstruit à chaque validation.</strong> Retirer chirurgicalement un
 * index de six collections à chaque complétion serait une source de dérive silencieuse pour un
 * gain nul : une reconstruction coûte 25 insertions et n'arrive au plus que 25 fois par équipe
 * et par manche.
 */
public final class TeamPendingIndex {

	/** Index d'une partie sans carte tirée. */
	public static final TeamPendingIndex EMPTY = new TeamPendingIndex(List.of(), 0);

	private final Map<ObjectiveType, List<Integer>> byType = new EnumMap<>(ObjectiveType.class);
	private final Map<Item, List<Integer>> craftItems = new HashMap<>();
	private final Map<Item, List<Integer>> findItems = new HashMap<>();
	private final Map<EntityType<?>, List<Integer>> kills = new HashMap<>();
	private final List<Integer> craftTagged = new ArrayList<>();
	private final List<Integer> findTagged = new ArrayList<>();
	private final List<Integer> killTagged = new ArrayList<>();

	private TeamPendingIndex(List<Objective> tiles, int completionMask) {
		for (int index = 0; index < tiles.size(); index++) {
			if (WinLines.isCompleted(completionMask, index)) {
				continue;
			}
			add(tiles.get(index), index);
		}
	}

	/**
	 * @param tiles          les 25 cases de la carte partagée, ou une liste vide hors manche
	 * @param completionMask les cases déjà validées par l'équipe, qui n'entrent pas dans l'index
	 */
	public static TeamPendingIndex build(List<Objective> tiles, int completionMask) {
		return tiles.isEmpty() ? EMPTY : new TeamPendingIndex(tiles, completionMask);
	}

	private void add(Objective objective, int index) {
		byType.computeIfAbsent(objective.type(), type -> new ArrayList<>()).add(index);

		// Chaîne de instanceof et non switch sur motifs : ces derniers sont encore en preview sur
		// Java 17, la cible imposée par 1.20.1.
		if (objective.target() instanceof CraftTarget craft) {
			addItem(craftItems, craftTagged, craft.item(), index);
		} else if (objective.target() instanceof FindTarget find) {
			addItem(findItems, findTagged, find.item(), index);
		} else if (objective.target() instanceof KillMobTarget kill) {
			if (kill.entity() instanceof EntityMatcher.OfType ofType) {
				kills.computeIfAbsent(ofType.type(), type -> new ArrayList<>()).add(index);
			} else {
				killTagged.add(index);
			}
		}
		// DEATH et ACTION n'ont pas de clé indexable : leur cible est un type de dégâts ou un
		// déclencheur. Ils passent par byType, dont les listes font au plus 25 entrées pour des
		// événements rares — mourir ou dormir n'arrive pas 4 fois par seconde.
	}

	private static void addItem(Map<Item, List<Integer>> exact,
	                            List<Integer> tagged,
	                            ItemMatcher matcher,
	                            int index) {
		if (matcher instanceof ItemMatcher.OfItem ofItem) {
			exact.computeIfAbsent(ofItem.item(), item -> new ArrayList<>()).add(index);
		} else {
			tagged.add(index);
		}
	}

	/** Cases en attente de ce type. Vide si aucune. */
	public List<Integer> byType(ObjectiveType type) {
		return byType.getOrDefault(type, List.of());
	}

	/** Cases {@code CRAFT} ciblant exactement cet item. */
	public List<Integer> craftByItem(Item item) {
		return craftItems.getOrDefault(item, List.of());
	}

	/** Cases {@code FIND} ciblant exactement cet item. */
	public List<Integer> findByItem(Item item) {
		return findItems.getOrDefault(item, List.of());
	}

	/** Cases {@code KILL_MOB} ciblant exactement ce type d'entité. */
	public List<Integer> killByEntity(EntityType<?> type) {
		return kills.getOrDefault(type, List.of());
	}

	/** Cases {@code CRAFT} dont la cible est un tag, à tester par prédicat. */
	public List<Integer> craftTagged() {
		return craftTagged;
	}

	/** Cases {@code FIND} dont la cible est un tag, à tester par prédicat. */
	public List<Integer> findTagged() {
		return findTagged;
	}

	/** Cases {@code KILL_MOB} dont la cible est un tag, à tester par prédicat. */
	public List<Integer> killTagged() {
		return killTagged;
	}

	/**
	 * Y a-t-il encore quelque chose à chercher dans un inventaire ?
	 *
	 * <p>Court-circuite le scan périodique complet : dès qu'une équipe a validé toutes ses cases
	 * {@code FIND}, ses joueurs cessent d'être scannés (`docs/01` §4.2).
	 */
	public boolean hasFindTargets() {
		return !findItems.isEmpty() || !findTagged.isEmpty();
	}
}
