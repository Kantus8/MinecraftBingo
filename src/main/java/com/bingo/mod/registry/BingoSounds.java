package com.bingo.mod.registry;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Les événements sonores du mod, tels que déclarés dans {@code assets/bingo/sounds.json}.
 *
 * <p><strong>Piège de nommage</strong> (`docs/00` §6, point 8) : l'identifiant d'un événement
 * sonore est la <em>clé</em> de {@code sounds.json}, à points — {@code bingo:ui.objective_complete}.
 * Le chemin à slash ({@code bingo:ui/objective_complete}) désigne le fichier {@code .ogg} et ne
 * résout <em>aucun</em> son s'il est utilisé dans le code, sans erreur au démarrage.
 *
 * <p>Les {@code .ogg} eux-mêmes arrivent au lot 4 (tâche 4.11). D'ici là, jouer ces sons est un
 * no-op côté client, avec un avertissement au chargement des ressources : c'est sans conséquence
 * fonctionnelle, et enregistrer les événements dès maintenant évite de disperser des appels
 * conditionnels dans la logique de partie.
 */
public final class BingoSounds {

	/** Une case validée par l'équipe du joueur (`docs/05` §5). */
	public static final SoundEvent OBJECTIVE_COMPLETE = of("ui.objective_complete");

	/** L'équipe est à une case de compléter une combinaison — son local uniquement. */
	public static final SoundEvent LINE_COMPLETE = of("ui.line_complete");

	/** Victoire. */
	public static final SoundEvent BINGO = of("ui.bingo");

	/** Bip des 10 dernières secondes, et du décompte de départ. */
	public static final SoundEvent COUNTDOWN_TICK = of("ui.countdown_tick");

	/** Départ de la manche. */
	public static final SoundEvent GAME_START = of("ui.game_start");

	/** Fin de manche sans vainqueur (temps écoulé, arrêt). */
	public static final SoundEvent GAME_END = of("ui.game_end");

	/** Ouverture de l'écran de carte. */
	public static final SoundEvent CARD_FLIP = of("ui.card_flip");

	private BingoSounds() {
	}

	private static SoundEvent of(String path) {
		Identifier id = BingoConstants.id(path);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
	}

	/**
	 * Force le chargement de la classe, donc l'enregistrement des constantes.
	 *
	 * <p>Sans cet appel explicite depuis l'entrypoint, les {@code static final} ne seraient
	 * initialisés qu'au premier usage — c'est-à-dire après le gel des registres, ce qui lève une
	 * exception au premier son joué plutôt qu'au démarrage.
	 */
	public static void register() {
		BingoConstants.LOGGER.debug("Événements sonores enregistrés");
	}
}
