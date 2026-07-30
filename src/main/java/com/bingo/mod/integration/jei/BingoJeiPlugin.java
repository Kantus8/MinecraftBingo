package com.bingo.mod.integration.jei;

import com.bingo.mod.util.BingoConstants;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Plugin JEI — ne fait que détenir l'{@code IJeiRuntime} (`docs/03` §3.2, tâche 3.6).
 *
 * <p>Le runtime n'existe qu'entre {@link #onRuntimeAvailable} et {@link #onRuntimeUnavailable},
 * c'est-à-dire pendant la durée de vie d'un monde. Le mémoriser est la seule façon d'y accéder
 * depuis un écran : JEI ne l'expose nulle part ailleurs.
 *
 * <p><strong>Découverte du plugin</strong> : sur Fabric, JEI ne scanne pas les annotations. Il lit
 * l'entrypoint {@code jei_mod_plugin} de {@code fabric.mod.json}
 * ({@code mezz.jei.fabric.startup.FabricPluginFinder}). L'annotation {@link JeiPlugin} est
 * conservée parce que l'API en fait un contrat, mais c'est bien l'entrypoint qui charge la classe —
 * l'oublier donne un plugin silencieusement absent, sans le moindre message d'erreur.
 *
 * <p>Vit dans {@code src/main/java} (`docs/06` §5) : la classe ne touche à aucune classe client, et
 * l'API JEI est en {@code modCompileOnly} sur ce source set. Elle n'est jamais instanciée sur un
 * serveur dédié, qui n'interroge pas l'entrypoint {@code jei_mod_plugin}.
 */
@JeiPlugin
public class BingoJeiPlugin implements IModPlugin {

	private static final Identifier UID = BingoConstants.id("jei_plugin");

	/**
	 * {@code volatile} : JEI publie le runtime depuis son thread de démarrage, le pont le lit depuis
	 * le thread de rendu.
	 */
	private static volatile @Nullable IJeiRuntime runtime;

	@Override
	public Identifier getPluginUid() {
		return UID;
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
		BingoConstants.LOGGER.debug("Runtime JEI disponible");
	}

	/**
	 * Remise à {@code null} obligatoire : un runtime conservé après la fermeture du monde retient
	 * tout le graphe de recettes de la partie précédente, et le premier clic dans le monde suivant
	 * ouvrirait une GUI adossée à un état mort.
	 */
	@Override
	public void onRuntimeUnavailable() {
		runtime = null;
		BingoConstants.LOGGER.debug("Runtime JEI libéré");
	}

	/** Le runtime courant, {@code null} tant que JEI n'a pas fini de démarrer. */
	public static @Nullable IJeiRuntime getRuntime() {
		return runtime;
	}
}
