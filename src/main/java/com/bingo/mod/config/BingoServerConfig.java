package com.bingo.mod.config;

import com.bingo.mod.util.BingoConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Réglages serveur, persistés dans {@code config/bingo-server.json} (`docs/05` §4.3).
 *
 * <p><strong>Ces valeurs sont le dernier repli</strong> de la précédence de `docs/01` §8 : le
 * profil de difficulté puis le ruleset les précèdent toujours. Elles ne servent donc qu'aux
 * profils et rulesets qui omettent une clé — ce qui n'arrive pour aucun des 4 profils livrés.
 * C'est la raison pour laquelle {@code /bingo config} rappelle le ruleset actif : sans ce rappel,
 * un opérateur qui pose {@code points_base 200} et voit les scores inchangés conclurait à un bug.
 *
 * <p>Les champs restent {@code public static} : ils sont lus directement par {@code BingoGame}, et
 * les cacher derrière des accesseurs qui ne feraient rien serait du bruit. {@link #settings()} en
 * donne une vue par clé, qui est ce dont {@code /bingo config} a besoin — un {@code switch} sur
 * seize noms de clés dans la commande aurait dupliqué les bornes et les défauts.
 *
 * <p>Les clés {@code hud_*} de `docs/05` §4.3 n'y figurent pas : elles sont <em>client</em> et
 * vivent dans {@code config/bingo-client.json} (tâche 4.13).
 */
public final class BingoServerConfig {

	private static final String FILE_NAME = "bingo-server.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Points d'une case de niveau 1, quand ni l'objectif ni le ruleset n'en fixent (`docs/05` §2.1). */
	public static int pointsBase = 100;

	/** Durée de manche de dernier recours, en secondes (`docs/01` §8). */
	public static int timeLimitSeconds = 3600;

	/** Décompte avant le départ, en secondes. */
	public static int countdownSeconds = 5;

	/** Taille d'équipe (`docs/05` §3). */
	public static int teamSize = 2;

	/** Nombre maximal d'équipes. */
	public static int maxTeams = 4;

	/**
	 * Verrouillage de case — <strong>inerte</strong>.
	 *
	 * <p>La clé est exposée parce que `docs/05` §4.3 la liste et que {@code ruleset.tile_lock}
	 * existe dans le schéma, mais `docs/01` §9 a tranché : carte partagée, pas de verrouillage.
	 * Aucun code ne la lit. La cacher creuserait un écart entre le tableau du doc et la commande ;
	 * l'afficher sans mention ferait croire à une fonctionnalité. D'où {@link Setting#inert()}.
	 */
	public static boolean tileLock = false;

	/** Annonce en chat à chaque validation (`docs/05` §5). */
	public static boolean announceCompletions = true;

	/** Le HUD adverse révèle-t-il la progression des autres équipes (`docs/03` §1) ? */
	public static boolean revealOpponentProgress = true;

	/** Animation Slot Machine au tirage (`docs/04`). À faux, {@code ROLLING} ne dure qu'un tick. */
	public static boolean rollAnimation = true;

	/** Gel des joueurs pendant l'animation (`docs/04` §5). */
	public static boolean freezeDuringRoll = true;

	/** Gestion des groupes vocaux par le mod (`docs/02`). À faux, Simple Voice Chat est laissé seul. */
	public static boolean voiceEnabled = true;

	/**
	 * Vidage des inventaires au lancement d'une manche.
	 *
	 * <p>À {@code true} par défaut : une manche se joue à égalité de matériel, et un joueur qui arrive
	 * avec un coffre d'obsidienne coche la moitié de la grille avant le décompte. La clé existe parce
	 * que l'opération est irréversible et touche aussi les joueurs qui ne font qu'observer
	 * ({@code BingoPlayerReset}).
	 */
	public static boolean clearInventoryOnStart = true;

	/**
	 * Remise à zéro des niveaux d'expérience au lancement d'une manche.
	 *
	 * <p>Même intention que le vidage d'inventaire : 30 niveaux conservés valident « enchanter un
	 * objet » avant le premier coup de pioche.
	 */
	public static boolean resetLevelsOnStart = true;

	/**
	 * Révocation de tous les succès au lancement d'une manche.
	 *
	 * <p>À {@code true} par défaut parce que c'est une <strong>condition de jouabilité</strong> et pas
	 * un réglage d'équité : les objectifs {@code bingo:advancement} se détectent à l'octroi du succès,
	 * donc un joueur qui l'a déjà ne pourra jamais valider la case ({@code BingoPlayerReset}).
	 */
	public static boolean resetAdvancementsOnStart = true;

	/**
	 * Bornes de la zone de départ tirée par l'option {@code teleport} de {@code /bingo start}, en
	 * blocs depuis le spawn du monde ({@code BingoTeleport}).
	 *
	 * <p>Le défaut vise un terrain que personne n'a fouillé sans imposer un voyage de retour
	 * impossible. Ce sont des <em>bornes</em> et non une distance fixe : un rayon unique ferait
	 * atterrir toutes les manches sur le même cercle.
	 */
	public static int teleportMinDistance = 1500;

	public static int teleportMaxDistance = 5000;

	private BingoServerConfig() {
	}

	// ── Description des clés ──────────────────────────────────────────────────

	/**
	 * Une clé exposée par {@code /bingo config}.
	 *
	 * <p>Porte son nom, son type, son domaine, son défaut et l'accès à la valeur vivante. Les
	 * bornes des entiers reprennent <strong>exactement</strong> celles des codecs de
	 * {@code Ruleset} : les deux chemins écrivent la même donnée, et un {@code max_teams} refusé
	 * par le datapack mais accepté par la commande serait une incohérence à débusquer en jeu.
	 */
	public abstract sealed static class Setting {

		private final String name;
		private final boolean inert;

		private Setting(String name, boolean inert) {
			this.name = name;
			this.inert = inert;
		}

		public String name() {
			return name;
		}

		/** La clé est exposée pour la complétude du schéma mais aucun code ne la lit. */
		public boolean inert() {
			return inert;
		}

		/** Nom du type, pour les messages d'erreur : {@code int} ou {@code bool}. */
		public abstract String typeName();

		/** Domaine accepté, en clair — {@code 2..2147483647}, {@code true|false}. */
		public abstract String domain();

		/** Valeurs à proposer en complétion. Vide quand le domaine est trop large pour être listé. */
		public abstract Collection<String> suggestions();

		public abstract String value();

		public abstract String defaultValue();

		public boolean isDefault() {
			return value().equals(defaultValue());
		}

		/**
		 * Écrit la valeur, sans persister.
		 *
		 * @return {@code false} si le texte n'est pas décodable ou sort du domaine — la commande
		 *         doit alors échouer, pas appliquer un repli silencieux.
		 */
		public abstract boolean parseAndSet(String raw);

		/** La valeur vivante, sous la forme attendue par le JSON de persistance. */
		abstract void writeTo(JsonObject json);
	}

	/** Une clé entière et ses bornes. */
	public static final class IntSetting extends Setting {

		private final int min;
		private final int max;
		private final int fallback;
		private final IntSupplier getter;
		private final IntConsumer setter;

		private IntSetting(String name, int min, int max, int fallback,
		                   IntSupplier getter, IntConsumer setter) {
			super(name, false);
			this.min = min;
			this.max = max;
			this.fallback = fallback;
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		public String typeName() {
			return "int";
		}

		@Override
		public String domain() {
			return min + ".." + max;
		}

		/** Le défaut et la valeur courante : proposer 2 milliards d'entiers n'aiderait personne. */
		@Override
		public Collection<String> suggestions() {
			return Set.of(defaultValue(), value());
		}

		@Override
		public String value() {
			return String.valueOf(getter.getAsInt());
		}

		@Override
		public String defaultValue() {
			return String.valueOf(fallback);
		}

		@Override
		public boolean parseAndSet(String raw) {
			int parsed;
			try {
				parsed = Integer.parseInt(raw.trim());
			} catch (NumberFormatException exception) {
				return false;
			}
			if (parsed < min || parsed > max) {
				return false;
			}
			setter.accept(parsed);
			return true;
		}

		@Override
		void writeTo(JsonObject json) {
			json.addProperty(name(), getter.getAsInt());
		}
	}

	/** Une clé booléenne. */
	public static final class BoolSetting extends Setting {

		private final boolean fallback;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;

		private BoolSetting(String name, boolean inert, boolean fallback,
		                    BooleanSupplier getter, Consumer<Boolean> setter) {
			super(name, inert);
			this.fallback = fallback;
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		public String typeName() {
			return "bool";
		}

		@Override
		public String domain() {
			return "true|false";
		}

		@Override
		public Collection<String> suggestions() {
			return Set.of("true", "false");
		}

		@Override
		public String value() {
			return String.valueOf(getter.getAsBoolean());
		}

		@Override
		public String defaultValue() {
			return String.valueOf(fallback);
		}

		/**
		 * {@code true} / {@code false} uniquement, et non le laxisme de
		 * {@link Boolean#parseBoolean} qui rend {@code "oui"} en {@code false} sans rien dire.
		 */
		@Override
		public boolean parseAndSet(String raw) {
			String trimmed = raw.trim();
			if ("true".equalsIgnoreCase(trimmed)) {
				setter.accept(true);
				return true;
			}
			if ("false".equalsIgnoreCase(trimmed)) {
				setter.accept(false);
				return true;
			}
			return false;
		}

		@Override
		void writeTo(JsonObject json) {
			json.addProperty(name(), getter.getAsBoolean());
		}
	}

	/**
	 * Les 11 clés serveur de `docs/05` §4.3, dans l'ordre du tableau du doc, suivies des 5 clés
	 * ajoutées depuis (départ de manche : table rase des joueurs et zone de téléportation).
	 *
	 * <p><strong>Écart avec `docs/05` §4.3</strong>, assumé : le doc décrit onze clés. Les cinq
	 * dernières viennent de demandes postérieures et sont placées à la fin plutôt qu'insérées dans
	 * l'ordre alphabétique, pour que la comparaison avec le tableau du doc reste ligne à ligne.
	 *
	 * <p>{@link LinkedHashMap} et non {@code Map.of} : {@code /bingo config list} doit se relire à
	 * côté du doc, et une map à ordre d'itération non spécifié rendrait la comparaison pénible.
	 */
	private static final Map<String, Setting> SETTINGS = buildSettings();

	private static Map<String, Setting> buildSettings() {
		Map<String, Setting> settings = new LinkedHashMap<>();
		put(settings, new IntSetting("points_base", 0, Integer.MAX_VALUE, 100,
				() -> pointsBase, value -> pointsBase = value));
		put(settings, new IntSetting("time_limit_seconds", 1, Integer.MAX_VALUE, 3600,
				() -> timeLimitSeconds, value -> timeLimitSeconds = value));
		put(settings, new IntSetting("countdown_seconds", 0, 60, 5,
				() -> countdownSeconds, value -> countdownSeconds = value));
		put(settings, new IntSetting("team_size", 1, Integer.MAX_VALUE, 2,
				() -> teamSize, value -> teamSize = value));
		put(settings, new IntSetting("max_teams", 2, Integer.MAX_VALUE, 4,
				() -> maxTeams, value -> maxTeams = value));
		put(settings, new BoolSetting("tile_lock", true, false,
				() -> tileLock, value -> tileLock = value));
		put(settings, new BoolSetting("reveal_opponent_progress", false, true,
				() -> revealOpponentProgress, value -> revealOpponentProgress = value));
		put(settings, new BoolSetting("roll_animation", false, true,
				() -> rollAnimation, value -> rollAnimation = value));
		put(settings, new BoolSetting("freeze_during_roll", false, true,
				() -> freezeDuringRoll, value -> freezeDuringRoll = value));
		put(settings, new BoolSetting("voice_enabled", false, true,
				() -> voiceEnabled, value -> voiceEnabled = value));
		put(settings, new BoolSetting("announce_completions", false, true,
				() -> announceCompletions, value -> announceCompletions = value));
		put(settings, new BoolSetting("clear_inventory_on_start", false, true,
				() -> clearInventoryOnStart, value -> clearInventoryOnStart = value));
		put(settings, new BoolSetting("reset_levels_on_start", false, true,
				() -> resetLevelsOnStart, value -> resetLevelsOnStart = value));
		put(settings, new BoolSetting("reset_advancements_on_start", false, true,
				() -> resetAdvancementsOnStart, value -> resetAdvancementsOnStart = value));
		// Bornes larges mais non nulles : la distance minimale peut valoir 0 (« n'importe où »), la
		// maximale non — un intervalle [0, 0] ferait atterrir tout le monde sur le spawn.
		put(settings, new IntSetting("teleport_min_distance", 0, 10_000_000, 1500,
				() -> teleportMinDistance, value -> teleportMinDistance = value));
		put(settings, new IntSetting("teleport_max_distance", 1, 10_000_000, 5000,
				() -> teleportMaxDistance, value -> teleportMaxDistance = value));
		return settings;
	}

	private static void put(Map<String, Setting> settings, Setting setting) {
		settings.put(setting.name(), setting);
	}

	/** Les clés, dans l'ordre du tableau de `docs/05` §4.3. */
	public static Collection<Setting> settings() {
		return SETTINGS.values();
	}

	public static @Nullable Setting setting(String key) {
		return SETTINGS.get(key);
	}

	public static Collection<String> keys() {
		return SETTINGS.keySet();
	}

	// ── Persistance ───────────────────────────────────────────────────────────

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/**
	 * Charge {@code config/bingo-server.json}, en écrivant les défauts s'il n'existe pas.
	 *
	 * <p>Le fichier est relu clé par clé <em>à travers</em> {@link Setting#parseAndSet} et non
	 * désérialisé sur un POJO : c'est ce qui lui applique les mêmes bornes qu'un
	 * {@code /bingo config set}. Un fichier édité à la main avec {@code max_teams: 0} laisse donc
	 * le défaut en place avec un WARN, au lieu d'installer un état qu'aucune commande n'aurait
	 * accepté.
	 *
	 * <p>Un fichier illisible n'est <strong>pas</strong> écrasé : défauts en mémoire et WARN.
	 * Réécrire effacerait la configuration d'un serveur à cause d'une virgule en trop.
	 */
	public static void load() {
		Path file = path();
		if (!Files.exists(file)) {
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject json = GSON.fromJson(reader, JsonObject.class);
			if (json == null) {
				BingoConstants.LOGGER.warn("{} vide — défauts appliqués", FILE_NAME);
				return;
			}
			for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
				// Les clés de commentaire sont tolérées : le piège du `_comment` qui fait rejeter tout
				// le fichier a déjà coûté une passe au lot 4 (encadré de `docs/04` §7).
				if (entry.getKey().startsWith("_")) {
					continue;
				}
				apply(entry.getKey(), entry.getValue());
			}
			BingoConstants.LOGGER.info("Config serveur chargée : {}", file);
		} catch (IOException | JsonSyntaxException | IllegalStateException exception) {
			BingoConstants.LOGGER.warn("Config serveur illisible ({}) — défauts appliqués, fichier conservé",
					exception.getMessage());
		}
	}

	private static void apply(String key, JsonElement element) {
		Setting setting = SETTINGS.get(key);
		if (setting == null) {
			BingoConstants.LOGGER.warn("{} : clé inconnue '{}' ignorée", FILE_NAME, key);
			return;
		}
		String text = element.isJsonPrimitive() ? element.getAsString() : element.toString();
		if (!setting.parseAndSet(text)) {
			BingoConstants.LOGGER.warn("{} : '{}' = {} refusé (attendu {} dans {}) — défaut {} conservé",
					FILE_NAME, key, text, setting.typeName(), setting.domain(), setting.defaultValue());
		}
	}

	/** Réécrit le fichier depuis les valeurs vivantes. Appelé par {@code /bingo config set}. */
	public static void save() {
		try {
			Path file = path();
			Files.createDirectories(file.getParent());

			JsonObject json = new JsonObject();
			json.addProperty("_comment", "Repli de dernier recours : le profil de difficulte puis le "
					+ "ruleset sont prioritaires (docs/01 §8). Modifiable en jeu par /bingo config set.");
			SETTINGS.values().forEach(setting -> setting.writeTo(json));

			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(json, writer);
			}
		} catch (IOException exception) {
			BingoConstants.LOGGER.warn("Config serveur non sauvegardée : {}", exception.getMessage());
		}
	}
}
