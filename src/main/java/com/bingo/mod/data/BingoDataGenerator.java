package com.bingo.mod.data;

import com.bingo.mod.util.BingoConstants;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Entrypoint {@code fabric-datagen} — {@code gradlew runDatagen} (tâche 5.1).
 *
 * <p>Génère les <strong>tags</strong> du mod : pour l'instant le seul est {@code #bingo:roll_decoys}
 * ({@link BingoItemTagProvider}), dont la liste vit en constantes {@code Items.*} vérifiées à la
 * compilation dans {@link com.bingo.mod.registry.BingoItemTags}.
 *
 * <p><strong>Ce qui n'est délibérément pas généré</strong> : les 45 objectifs, les 4 profils et les
 * rulesets. Ils sont écrits à la main dans {@code src/main/resources/data/bingo/} et le restent.
 * Chacun porte des données irréductiblement éditoriales — nom et description traduisibles, poids de
 * tirage ajusté, {@code count}, tags de pool, conflits — qu'un provider ne ferait que recopier en
 * Java sans rien vérifier de plus. La règle « objectifs générables » de la tâche 5.1 vise les
 * familles purement mécaniques ; aucune des 45 cases livrées n'en est une, donc les générer serait
 * une traduction à perte. La porte reste ouverte : le jour où une telle famille apparaît (p. ex.
 * « miner N blocs de chaque minerai »), elle s'ajoute ici en un provider dédié.
 */
public class BingoDataGenerator implements DataGeneratorEntrypoint {

	@Override
	public void onInitializeDataGenerator(FabricDataGenerator generator) {
		FabricDataGenerator.Pack pack = generator.createPack();
		pack.addProvider(BingoItemTagProvider::new);
		BingoConstants.LOGGER.info("{} : datagen — provider de tags enregistré (roll_decoys)",
				BingoConstants.MOD_NAME);
	}
}
