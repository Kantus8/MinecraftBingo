package com.bingo.mod.game.detect;

import com.bingo.mod.objective.Objective;
import com.bingo.mod.objective.type.ActionTarget;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Le registre des déclencheurs {@code bingo:action} (tâche 2.8, `docs/01` §4.5).
 *
 * <p>{@code ACTION} est la soupape du système : tout ce qui n'entre pas dans les quatre autres
 * types passe par un déclencheur codé en Java. {@link #ADVANCEMENT} couvre à lui seul une
 * centaine d'objectifs potentiels sans une ligne de code — <strong>le privilégier
 * systématiquement</strong>, et n'écrire un déclencheur dédié que si aucun advancement ne
 * correspond.
 *
 * <p>Un registre nommé plutôt qu'un {@code switch} sur l'identifiant : c'est ce qui permet de
 * détecter un {@code trigger} inconnu au <em>chargement</em> ({@link #warnUnknownTriggers}) plutôt
 * qu'au moment où le joueur constate qu'une case ne se valide jamais.
 */
public final class ActionTriggers {

	public static final Identifier ADVANCEMENT = BingoConstants.id("advancement");
	public static final Identifier SLEEP_IN_BED = BingoConstants.id("sleep_in_bed");
	public static final Identifier ENTER_DIMENSION = BingoConstants.id("enter_dimension");
	public static final Identifier ENCHANT_ITEM = BingoConstants.id("enchant_item");
	public static final Identifier TRADE_WITH_VILLAGER = BingoConstants.id("trade_with_villager");
	public static final Identifier TAME_ANIMAL = BingoConstants.id("tame_animal");
	public static final Identifier REACH_Y_LEVEL = BingoConstants.id("reach_y_level");
	public static final Identifier USE_ITEM_ON_BLOCK = BingoConstants.id("use_item_on_block");
	public static final Identifier REACH_XP_LEVEL = BingoConstants.id("reach_xp_level");
	public static final Identifier RIDE_EQUIPPED_HORSE = BingoConstants.id("ride_equipped_horse");

	private static final Map<Identifier, ActionTrigger> REGISTRY = buildRegistry();

	/** Déclencheurs inconnus déjà signalés — un WARN par identifiant, pas un par rechargement. */
	private static final Set<Identifier> REPORTED_UNKNOWN = new HashSet<>();

	/** Voir {@link #reportBadExclude()} : le WARN ne doit pas se répéter à chaque tick. */
	private static boolean badExcludeReported;

	private ActionTriggers() {
	}

	private static Map<Identifier, ActionTrigger> buildRegistry() {
		Map<Identifier, ActionTrigger> registry = new LinkedHashMap<>();

		registry.put(ADVANCEMENT, (event, params) -> event instanceof ActionEvent.AdvancementDone done
				&& matchesId(params, "advancement", done.advancement()));

		// Aucun paramètre : dormir est dormir.
		registry.put(SLEEP_IN_BED, (event, params) -> event instanceof ActionEvent.SleptInBed);

		registry.put(ENTER_DIMENSION, (event, params) -> event instanceof ActionEvent.DimensionEntered entered
				&& matchesId(params, "dimension", entered.dimension()));

		registry.put(ENCHANT_ITEM, (event, params) -> {
			if (!(event instanceof ActionEvent.ItemEnchanted enchanted)) {
				return false;
			}
			int minLevel = params.contains("min_level") ? params.getInt("min_level") : 1;
			Optional<Identifier> wanted = readId(params, "enchantment");

			// Sans 'enchantment', n'importe quel enchantement au niveau demandé suffit : c'est le
			// cas de l'objectif livré (« any enchantment, any level »).
			return enchanted.enchantments().entrySet().stream()
					.filter(entry -> wanted.isEmpty() || wanted.get().equals(entry.getKey()))
					.anyMatch(entry -> entry.getValue() >= minLevel);
		});

		registry.put(TRADE_WITH_VILLAGER, (event, params) -> {
			if (!(event instanceof ActionEvent.VillagerTraded traded)) {
				return false;
			}
			Optional<Identifier> wanted = readId(params, "profession");
			return wanted.isEmpty() || traded.profession().filter(wanted.get()::equals).isPresent();
		});

		registry.put(TAME_ANIMAL, (event, params) -> event instanceof ActionEvent.AnimalTamed tamed
				&& matchesId(params, "entity_type",
						net.minecraft.registry.Registries.ENTITY_TYPE.getId(tamed.type())));

		registry.put(REACH_Y_LEVEL, (event, params) -> {
			if (!(event instanceof ActionEvent.YLevelReached reached) || !params.contains("y")) {
				return false;
			}
			double threshold = params.getDouble("y");
			// Défaut 'below' : descendre est la direction qui a un sens dans Minecraft, et un
			// objectif « atteindre Y=-59 » sans comparateur veut évidemment dire « descendre ».
			boolean above = "above".equals(params.getString("comparator"));
			return above ? reached.y() >= threshold : reached.y() <= threshold;
		});

		registry.put(USE_ITEM_ON_BLOCK, (event, params) -> {
			if (!(event instanceof ActionEvent.ItemUsedOnBlock used)) {
				return false;
			}
			return matchesId(params, "item", used.item()) && matchesId(params, "block", used.block());
		});

		// 'level' est exigé, contrairement au joker habituel : un seuil absent voudrait dire
		// « n'importe quel niveau », donc validation au niveau 0, donc au premier échantillon.
		registry.put(REACH_XP_LEVEL, (event, params) -> {
			if (!(event instanceof ActionEvent.XpLevelReached reached) || !params.contains("level")) {
				return false;
			}
			return reached.level() >= params.getInt("level");
		});

		registry.put(RIDE_EQUIPPED_HORSE, (event, params) -> {
			if (!(event instanceof ActionEvent.RodeEquippedHorse ridden)
					|| !matchesId(params, "armor", ridden.armor())) {
				return false;
			}
			// Une liste 'exclude' plutôt qu'un tag d'items : la seule exclusion utile aujourd'hui est
			// le cuir, et un tag imposerait un fichier généré pour une liste d'un seul élément.
			NbtList excluded = params.getList("exclude", NbtElement.STRING_TYPE);

			// getList rend une liste vide en cas de type inattendu, sans lever : un 'exclude' déclaré
			// mais illisible validerait alors le cuir en silence. On préfère le dire.
			if (params.contains("exclude") && excluded.isEmpty()) {
				reportBadExclude();
				return false;
			}

			String armor = ridden.armor().toString();
			return excluded.stream().noneMatch(entry -> armor.equals(entry.asString()));
		});

		return Map.copyOf(registry);
	}

	/**
	 * Un identifiant attendu par le datapack correspond-il à celui de l'événement ?
	 *
	 * <p>Clé absente = pas de contrainte : c'est ce qui rend {@code "params": {}} équivalent à
	 * « n'importe lequel », la forme utilisée par plusieurs objectifs livrés.
	 */
	/** Un WARN une seule fois : ce test tombe à chaque échantillonnage, soit une fois par seconde. */
	private static void reportBadExclude() {
		if (badExcludeReported) {
			return;
		}
		badExcludeReported = true;
		BingoConstants.LOGGER.warn("Paramètre 'exclude' de '{}' illisible : une liste de chaînes"
				+ " est attendue, ex. [\"minecraft:leather_horse_armor\"]", RIDE_EQUIPPED_HORSE);
	}

	private static boolean matchesId(NbtCompound params, String key, Identifier actual) {
		Optional<Identifier> wanted = readId(params, key);
		return wanted.isEmpty() || wanted.get().equals(actual);
	}

	private static Optional<Identifier> readId(NbtCompound params, String key) {
		if (!params.contains(key)) {
			return Optional.empty();
		}
		Identifier parsed = Identifier.tryParse(params.getString(key));
		if (parsed == null) {
			BingoConstants.LOGGER.warn("Paramètre '{}' de déclencheur illisible : '{}'",
					key, params.getString(key));
		}
		return Optional.ofNullable(parsed);
	}

	/**
	 * L'objectif réagit-il à cet événement ?
	 *
	 * @return {@code false} si le déclencheur est inconnu — un objectif dont le déclencheur
	 *         n'existe pas ne se valide jamais, ce qui est le comportement le moins surprenant et
	 *         déjà signalé au chargement.
	 */
	public static boolean matches(Objective objective, ActionEvent event) {
		if (!(objective.target() instanceof ActionTarget target)) {
			return false;
		}
		if (!target.trigger().equals(event.trigger())) {
			return false;
		}
		ActionTrigger trigger = REGISTRY.get(target.trigger());
		if (trigger == null) {
			reportUnknown(target.trigger(), objective.id());
			return false;
		}
		return trigger.matches(event, target.paramsOrEmpty());
	}

	public static boolean isKnown(Identifier trigger) {
		return REGISTRY.containsKey(trigger);
	}

	/**
	 * Signale les objectifs qui référencent un déclencheur inexistant.
	 *
	 * <p>À appeler après chaque rechargement de datapack : c'est le seul moment où l'opérateur
	 * regarde encore la console, et une faute de frappe dans un {@code trigger} est indétectable
	 * en jeu — la case existe, s'affiche, et ne se coche jamais.
	 */
	public static void warnUnknownTriggers(Collection<Objective> objectives) {
		REPORTED_UNKNOWN.clear();
		for (Objective objective : objectives) {
			if (objective.target() instanceof ActionTarget target && !isKnown(target.trigger())) {
				reportUnknown(target.trigger(), objective.id());
			}
		}
	}

	private static void reportUnknown(Identifier trigger, Identifier objectiveId) {
		if (REPORTED_UNKNOWN.add(trigger)) {
			BingoConstants.LOGGER.warn(
					"Déclencheur inconnu '{}' (objectif '{}') — cette case ne se validera jamais. Connus : {}",
					trigger, objectiveId, REGISTRY.keySet());
		}
	}
}
