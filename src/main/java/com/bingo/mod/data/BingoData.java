package com.bingo.mod.data;

import com.bingo.mod.data.loader.JsonRegistryLoader;
import com.bingo.mod.data.loader.ObjectiveLoader;
import com.bingo.mod.util.BingoConstants;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Point d'accès unique aux données de datapack du mod.
 *
 * <p>Regroupe les quatre registres et leur enregistrement, pour que {@code BingoMod} n'ait
 * qu'un appel à faire et qu'ajouter un registre plus tard ne touche pas l'entrypoint.
 */
public final class BingoData {

	public static final ObjectiveLoader OBJECTIVES = ObjectiveLoader.INSTANCE;

	public static final JsonRegistryLoader<Pool> POOLS =
			new JsonRegistryLoader<>("pools", Pool.CODEC, false);

	/**
	 * Rejets en ERROR : un profil refusé rend {@code /bingo start <profil>} impossible, ce qui
	 * mérite d'être visible sans avoir à chercher (`docs/01` §7).
	 */
	public static final JsonRegistryLoader<DifficultyProfile> DIFFICULTIES =
			new JsonRegistryLoader<>("difficulties", DifficultyProfile.CODEC, true);

	public static final JsonRegistryLoader<Ruleset> RULESETS =
			new JsonRegistryLoader<>("rulesets", Ruleset.CODEC, false);

	private BingoData() {
	}

	/**
	 * Enregistre les quatre chargeurs sur {@code SERVER_DATA}.
	 *
	 * <p>Aucune dépendance déclarée entre eux : les références croisées sont résolues à l'usage
	 * (voir {@link JsonRegistryLoader}), donc l'ordre d'exécution est indifférent.
	 */
	public static void registerLoaders() {
		ResourceManagerHelper helper = ResourceManagerHelper.get(ResourceType.SERVER_DATA);
		helper.registerReloadListener(OBJECTIVES);
		helper.registerReloadListener(POOLS);
		helper.registerReloadListener(DIFFICULTIES);
		helper.registerReloadListener(RULESETS);
	}

	/**
	 * Ruleset associé à un profil, si le profil en désigne un et qu'il est chargé.
	 *
	 * <p>Un ruleset manquant n'est pas fatal — les défauts du record couvrent tout — mais il est
	 * journalisé, parce que c'est presque toujours une faute de frappe dans le profil.
	 */
	public static Optional<Ruleset> rulesetFor(DifficultyProfile profile) {
		Optional<Identifier> id = profile.ruleset();
		if (id.isEmpty()) {
			return Optional.empty();
		}
		Optional<Ruleset> ruleset = RULESETS.get(id.get());
		if (ruleset.isEmpty()) {
			BingoConstants.LOGGER.warn("Ruleset '{}' introuvable — défauts appliqués", id.get());
		}
		return ruleset;
	}

	/**
	 * Révision commune des données, celle qui part sur le réseau (`docs/06` §3.4 garde-fou 2).
	 *
	 * <p>Les quatre chargeurs sont réexécutés par le même rechargement de ressources, donc leurs
	 * compteurs avancent ensemble : celui des objectifs suffit à représenter l'ensemble. Si un
	 * registre devenait rechargeable séparément, cette méthode devrait combiner les quatre.
	 */
	public static int revision() {
		return OBJECTIVES.revision();
	}
}
