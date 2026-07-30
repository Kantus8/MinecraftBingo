package com.bingo.mod.client.config;

import com.bingo.mod.util.BingoConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Réglages client, persistés dans {@code config/bingo-client.json} (`docs/05` §4.3, tâche 4.13).
 *
 * <p>Fichier séparé de la config serveur, et volontairement hors de {@code /bingo config} : ces
 * quatre clés décrivent la fenêtre d'<em>un</em> joueur. Les faire transiter par une commande
 * serveur reviendrait à laisser un opérateur décider de l'échelle du HUD des autres.
 *
 * <p>Écriture paresseuse : le fichier n'est réécrit que quand une valeur change réellement, ce qui
 * évite un accès disque à chaque bascule de touche répétée.
 */
public final class BingoClientConfig {

	private static final String FILE_NAME = "bingo-client.json";

	/** Bornes de `docs/03` §1 : en dessous le HUD est illisible, au-dessus il mange l'écran. */
	private static final float MIN_SCALE = 0.75f;
	private static final float MAX_SCALE = 1.5f;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Values values = new Values();

	private BingoClientConfig() {
	}

	/**
	 * Le contenu sérialisé du fichier.
	 *
	 * <p>Une classe à champs publics et non un {@code record} : Gson instancie par réflexion sans
	 * appeler le constructeur canonique, donc un champ absent du JSON garde la valeur d'initialisation
	 * — c'est ce qui rend un fichier partiel valide, et un fichier écrit par une version plus
	 * ancienne lisible.
	 */
	private static final class Values {
		int hud_margin_x = 8;
		int hud_margin_y = 8;
		float hud_scale = 1.0f;
		boolean hud_visible = true;

		/**
		 * Tableau des équipes, ancré en haut à <em>droite</em> : ses marges se comptent depuis le bord
		 * droit, contrairement à {@code hud_margin_x}. Il partage {@code hud_scale} — deux échelles
		 * indépendantes pour deux panneaux du même HUD n'aideraient personne.
		 */
		int team_panel_margin_x = 8;
		int team_panel_margin_y = 8;
		boolean team_panel_visible = true;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/**
	 * Charge le fichier, en écrivant les défauts s'il n'existe pas.
	 *
	 * <p>Un fichier illisible n'est <strong>pas</strong> écrasé : on repart des défauts en mémoire et
	 * on journalise. Réécrire effacerait la personnalisation d'un joueur à cause d'une virgule en
	 * trop, ce qui est bien pire que de l'ignorer le temps qu'il la corrige.
	 */
	public static void load() {
		Path file = path();
		if (!Files.exists(file)) {
			values = new Values();
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			Values loaded = GSON.fromJson(reader, Values.class);
			values = loaded == null ? new Values() : loaded;
			values.hud_scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, values.hud_scale));
			BingoConstants.LOGGER.debug("Config client chargée : {}", file);
		} catch (IOException | JsonSyntaxException exception) {
			BingoConstants.LOGGER.warn("Config client illisible ({}) — défauts appliqués, fichier conservé",
					exception.getMessage());
			values = new Values();
		}
	}

	public static void save() {
		try {
			Path file = path();
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(values, writer);
			}
		} catch (IOException exception) {
			BingoConstants.LOGGER.warn("Config client non sauvegardée : {}", exception.getMessage());
		}
	}

	// ── Lecture ───────────────────────────────────────────────────────────────

	public static int hudMarginX() {
		return values.hud_margin_x;
	}

	public static int hudMarginY() {
		return values.hud_margin_y;
	}

	public static float hudScale() {
		return values.hud_scale;
	}

	public static boolean hudVisible() {
		return values.hud_visible;
	}

	/** Marge <strong>droite</strong> du tableau des équipes. */
	public static int teamPanelMarginX() {
		return values.team_panel_margin_x;
	}

	public static int teamPanelMarginY() {
		return values.team_panel_margin_y;
	}

	public static boolean teamPanelVisible() {
		return values.team_panel_visible;
	}

	// ── Écriture ──────────────────────────────────────────────────────────────

	/** @return la nouvelle visibilité, persistée. */
	public static boolean toggleHudVisible() {
		values.hud_visible = !values.hud_visible;
		save();
		return values.hud_visible;
	}

	/** @return la nouvelle visibilité du tableau des équipes, persistée. */
	public static boolean toggleTeamPanelVisible() {
		values.team_panel_visible = !values.team_panel_visible;
		save();
		return values.team_panel_visible;
	}
}
