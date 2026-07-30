package com.bingo.mod.data.loader;

import com.bingo.mod.util.BingoConstants;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFinder;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chargeur générique d'un dossier de datapack vers une map {@code Identifier -> T}.
 *
 * <p>Sert aux pools, profils de difficulté et rulesets, qui partagent la même mécanique :
 * un fichier = une entrée, un codec, aucun état croisé. Les références entre eux
 * ({@code difficulty.pool}, {@code difficulty.ruleset}) sont résolues <strong>à l'usage</strong>
 * et non au chargement — c'est ce qui rend l'ordre d'exécution des listeners indifférent et
 * évite d'avoir à déclarer des dépendances entre eux.
 *
 * <p>{@code ObjectiveLoader} n'utilise pas cette classe : son codec dépend de l'ID du fichier
 * et il fait une seconde passe de validation croisée. Le rapprochement des deux est un candidat
 * de nettoyage, pas un prérequis.
 */
public final class JsonRegistryLoader<T> implements SimpleSynchronousResourceReloadListener {

	private final String directory;
	private final Codec<T> codec;
	private final boolean rejectAsError;
	private final ResourceFinder finder;

	private Map<Identifier, T> entries = Map.of();
	private int revision;

	/**
	 * @param rejectAsError journalise les rejets en ERROR plutôt qu'en WARN. Vrai pour les
	 *                      profils de difficulté, dont un rejet rend une partie impossible
	 *                      (`docs/01` §7) ; faux là où un fichier ignoré est bénin.
	 */
	public JsonRegistryLoader(String directory, Codec<T> codec, boolean rejectAsError) {
		this.directory = directory;
		this.codec = codec;
		this.rejectAsError = rejectAsError;
		this.finder = ResourceFinder.json(directory);
	}

	@Override
	public Identifier getFabricId() {
		return BingoConstants.id(directory);
	}

	@Override
	public void reload(ResourceManager manager) {
		Map<Identifier, T> loaded = new LinkedHashMap<>();
		int rejected = 0;

		for (Map.Entry<Identifier, List<Resource>> found : finder.findAllResources(manager).entrySet()) {
			Identifier id = finder.toResourceId(found.getKey());
			List<Resource> stack = found.getValue();

			// Le dernier datapack de la pile gagne : sémantique d'écrasement attendue.
			Resource winner = stack.get(stack.size() - 1);

			Optional<T> parsed = parse(id, winner);
			if (parsed.isPresent()) {
				loaded.put(id, parsed.get());
			} else {
				rejected++;
			}
		}

		entries = Map.copyOf(loaded);
		revision++;

		BingoConstants.LOGGER.info("{} : {} entrée(s), révision {}", directory, entries.size(), revision);
		if (rejected > 0) {
			BingoConstants.LOGGER.warn("{} : {} fichier(s) rejeté(s)", directory, rejected);
		}
	}

	private Optional<T> parse(Identifier id, Resource resource) {
		try (Reader reader = resource.getReader()) {
			JsonElement json = DatapackJson.stripNulls(JsonParser.parseReader(reader));
			DataResult<T> result = codec.parse(JsonOps.INSTANCE, json);

			Optional<T> value = result.result();
			if (value.isEmpty()) {
				result.error().ifPresent(error -> log(id, error.message()));
			}
			return value;
		} catch (Exception exception) {
			log(id, "illisible — " + exception.getMessage());
			return Optional.empty();
		}
	}

	private void log(Identifier id, String message) {
		if (rejectAsError) {
			BingoConstants.LOGGER.error("{}/{} rejeté : {}", directory, id, message);
		} else {
			BingoConstants.LOGGER.warn("{}/{} rejeté : {}", directory, id, message);
		}
	}

	// ── Accès en lecture ──────────────────────────────────────────────────────

	public int revision() {
		return revision;
	}

	public Optional<T> get(Identifier id) {
		return Optional.ofNullable(entries.get(id));
	}

	public Collection<T> all() {
		return entries.values();
	}

	public Set<Identifier> keys() {
		return entries.keySet();
	}

	public int size() {
		return entries.size();
	}
}
