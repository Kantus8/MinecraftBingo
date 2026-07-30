package com.bingo.mod.data;

import com.bingo.mod.registry.BingoItemTags;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.registry.RegistryWrapper;

import java.util.concurrent.CompletableFuture;

/**
 * Génère {@code data/bingo/tags/items/roll_decoys.json} (tâche 5.1, `docs/04` §3).
 *
 * <p>Remplace le JSON écrit à la main : la liste vit désormais dans {@link BingoItemTags}, en
 * constantes {@code Items.*} vérifiées à la compilation. {@code gradlew runDatagen} réécrit le
 * fichier dans {@code src/main/generated}, d'où il est embarqué comme n'importe quelle ressource.
 *
 * <p>{@code replace = false} par défaut (comportement de {@link FabricTagProvider}) : un datapack
 * tiers peut compléter la liste de leurres sans avoir à la redéclarer entièrement, ce qui était
 * déjà l'intention du {@code "replace": false} du JSON d'origine.
 */
public final class BingoItemTagProvider extends FabricTagProvider.ItemTagProvider {

	public BingoItemTagProvider(FabricDataOutput output,
	                            CompletableFuture<RegistryWrapper.WrapperLookup> registriesFuture) {
		super(output, registriesFuture);
	}

	@Override
	protected void configure(RegistryWrapper.WrapperLookup wrapperLookup) {
		FabricTagBuilder builder = getOrCreateTagBuilder(BingoItemTags.ROLL_DECOYS);
		BingoItemTags.ROLL_DECOYS_ITEMS.forEach(builder::add);
	}
}
