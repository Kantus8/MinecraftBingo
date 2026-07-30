package com.bingo.mod.data.loader;

import com.bingo.mod.objective.Objective;
import com.bingo.mod.objective.type.ObjectiveType;
import com.bingo.mod.util.BingoConstants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFinder;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Charge {@code data/<ns>/objectives/**.json} à chaque rechargement de datapack (`docs/01` §1).
 *
 * <p>Registre plat côté serveur, <strong>pas</strong> un registre dynamique : décision actée
 * en `docs/06` §3.4. Le client en reçoit une projection d'affichage par paquet, ce qui permet
 * à {@code /bingo reload} de fonctionner réellement — un registre dynamique n'est pas
 * rechargé par {@code /reload} en 1.20.1.
 *
 * <p>Aucune erreur de datapack ne fait crasher le jeu : un objectif invalide est ignoré avec
 * un WARN et le reste se charge (`docs/01` §2).
 */
public final class ObjectiveLoader implements SimpleSynchronousResourceReloadListener {

	/** Instance unique, enregistrée une fois au démarrage par {@code BingoMod}. */
	public static final ObjectiveLoader INSTANCE = new ObjectiveLoader();

	private static final ResourceFinder FINDER = ResourceFinder.json("objectives");

	private Map<Identifier, Objective> objectives = Map.of();
	private int revision;

	private ObjectiveLoader() {
	}

	@Override
	public Identifier getFabricId() {
		return BingoConstants.id("objectives");
	}

	@Override
	public void reload(ResourceManager manager) {
		Map<Identifier, Objective> loaded = new LinkedHashMap<>();
		int rejected = 0;

		for (Map.Entry<Identifier, List<Resource>> entry : FINDER.findAllResources(manager).entrySet()) {
			Identifier id = FINDER.toResourceId(entry.getKey());
			List<Resource> stack = entry.getValue();

			// findAllResources rend la pile de datapacks du plus faible au plus fort :
			// le dernier gagne, c'est la sémantique d'écrasement attendue d'un datapack.
			Resource winner = stack.get(stack.size() - 1);

			Objective objective = parse(id, winner);
			if (objective == null) {
				rejected++;
			} else {
				loaded.put(id, objective);
			}
		}

		validateConflicts(loaded);

		objectives = Map.copyOf(loaded);
		revision++;
		logSummary(rejected);
	}

	/** @return l'objectif, ou {@code null} si le fichier est invalide (déjà journalisé). */
	private @Nullable Objective parse(Identifier id, Resource resource) {
		try (Reader reader = resource.getReader()) {
			JsonElement json = stripNulls(JsonParser.parseReader(reader));
			DataResult<Objective> result = Objective.codec(id).parse(JsonOps.INSTANCE, json);

			Optional<Objective> success = result.result();
			if (success.isPresent()) {
				return success.get();
			}

			// Volontairement result() et non resultOrPartial() : un objectif à moitié décodé
			// serait pire qu'absent — il produirait une case injouable sans erreur visible.
			result.error().ifPresent(error ->
					BingoConstants.LOGGER.warn("Objectif '{}' ignoré : {}", id, error.message()));
			return null;
		} catch (Exception exception) {
			BingoConstants.LOGGER.warn("Objectif '{}' illisible : {}", id, exception.getMessage());
			return null;
		}
	}

	/**
	 * Règle de validation n°6 : les {@code conflicts} doivent référencer des objectifs
	 * existants (`docs/01` §2).
	 *
	 * <p>On journalise sans réécrire l'objectif : un ID de conflit inconnu ne correspond à
	 * aucun objectif chargé, donc le tirage du lot 1.7 ne le rencontrera jamais. Recopier
	 * tout le record pour retirer une entrée déjà inerte serait du bruit sans effet.
	 */
	private static void validateConflicts(Map<Identifier, Objective> loaded) {
		loaded.forEach((id, objective) -> objective.conflicts().stream()
				.filter(conflict -> !loaded.containsKey(conflict))
				.forEach(conflict -> BingoConstants.LOGGER.warn(
						"Objectif '{}' déclare un conflit avec '{}', qui n'existe pas — entrée ignorée",
						id, conflict)));
	}

	/**
	 * Supprime récursivement les clés à valeur {@code null}.
	 *
	 * <p>Le schéma de `docs/01` §2 documente les champs optionnels avec {@code "champ": null},
	 * et ce document sert de modèle. Or {@code optionalFieldOf} de DFU attend une clé
	 * <em>absente</em> : un {@code null} explicite fait échouer le décodage. Sans ce nettoyage,
	 * un datapack copié depuis la doc serait rejeté — un piège coûteux pour un gain nul.
	 */
	private static JsonElement stripNulls(JsonElement element) {
		if (element instanceof JsonObject object) {
			object.entrySet().removeIf(entry -> entry.getValue().isJsonNull());
			object.entrySet().forEach(entry -> stripNulls(entry.getValue()));
		}
		return element;
	}

	private void logSummary(int rejected) {
		Map<ObjectiveType, Integer> byType = countByType();
		StringBuilder levels = new StringBuilder();
		for (int level = 1; level <= 4; level++) {
			levels.append(level > 1 ? " / " : "").append(countByLevel(level)).append(" N").append(level);
		}

		BingoConstants.LOGGER.info("{} objectifs chargés ({}), révision {}",
				objectives.size(), levels, revision);
		BingoConstants.LOGGER.info("Répartition par type : {}", byType);

		if (rejected > 0) {
			BingoConstants.LOGGER.warn("{} fichier(s) d'objectif rejeté(s) — voir les WARN ci-dessus", rejected);
		}
	}

	// ── Accès en lecture ──────────────────────────────────────────────────────

	/** Incrémentée à chaque rechargement, y compris raté (`docs/06` §3.4 garde-fou 2). */
	public int revision() {
		return revision;
	}

	public Optional<Objective> get(Identifier id) {
		return Optional.ofNullable(objectives.get(id));
	}

	public Collection<Objective> all() {
		return objectives.values();
	}

	public int size() {
		return objectives.size();
	}

	public int countByLevel(int level) {
		return (int) objectives.values().stream().filter(objective -> objective.level() == level).count();
	}

	public Map<ObjectiveType, Integer> countByType() {
		Map<ObjectiveType, Integer> counts = new EnumMap<>(ObjectiveType.class);
		for (Objective objective : objectives.values()) {
			counts.merge(objective.type(), 1, Integer::sum);
		}
		return counts;
	}

	/** IDs chargés, triés — pour {@code /bingo debug objectives}. */
	public List<Identifier> sortedIds() {
		List<Identifier> ids = new ArrayList<>(objectives.keySet());
		ids.sort(Identifier::compareTo);
		return ids;
	}
}
