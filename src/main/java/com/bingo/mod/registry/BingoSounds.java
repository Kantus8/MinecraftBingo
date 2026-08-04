package com.bingo.mod.registry;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
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

	/**
	 * Victoire de l'équipe du joueur : le son du succès rare.
	 *
	 * <p>Référencé directement, sans alias {@code bingo:}, pour la même raison que
	 * {@link #GAME_START} : {@code ui.toast.challenge_complete} est authoré à {@code "volume": 0.6},
	 * et l'alias élèverait ce 0,6 au carré — 0,36, soit la victoire comme son le plus discret de la
	 * manche. En direct, le gain reste 0,6, exactement la loudness à laquelle les joueurs entendent
	 * déjà ce son ailleurs dans le jeu.
	 *
	 * <p>La règle qui se dégage des deux exceptions : <strong>une cible vanilla authorée sous 1.0 ne
	 * passe jamais par un alias.</strong> Le prix payé ici est le sous-titre — vanilla n'en déclare
	 * aucun pour ce son, là où l'alias affichait « Bingo ! ».
	 */
	public static final SoundEvent BINGO = SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;

	/** Bip des 10 dernières secondes, et du décompte de départ. */
	public static final SoundEvent COUNTDOWN_TICK = of("ui.countdown_tick");

	/**
	 * Départ de la manche : le cor des pillards.
	 *
	 * <p><strong>Seul son du mod à ne pas passer par un alias {@code bingo:}</strong>, et il faut
	 * savoir pourquoi avant de « corriger » cette incohérence apparente. La résolution d'une entrée
	 * {@code "type": "event"} de {@code sounds.json} reconstruit le son avec
	 * {@code MultipliedFloatSupplier{sound.getVolume(), sound.getVolume()}}
	 * ({@code SoundManager.SoundList#register}) : elle <em>élève le volume de la cible au carré</em>.
	 * Sans conséquence pour les six autres, tous authorés à 1.0 — mais {@code event.raid.horn} est
	 * authoré à {@code "volume": 0.01}, car vanilla le diffuse à l'échelle d'un raid. Via l'alias, le
	 * cor tombe donc à 0,0001 : inaudible quel que soit le volume passé à l'émission.
	 *
	 * <p>Référencer l'événement vanilla directement évite ce carré. Reste à compenser le 0,01 du
	 * fichier, ce que fait {@code BingoAnnouncer.RAID_HORN_VOLUME}.
	 */
	public static final SoundEvent GAME_START = SoundEvents.EVENT_RAID_HORN.value();

	/**
	 * Fin de manche <em>sans victoire pour ce joueur</em> : une autre équipe l'emporte, ou personne.
	 *
	 * <p>Un seul son pour ces deux issues, et c'est un choix : le titre plein écran distingue déjà
	 * « telle équipe gagne » d'une égalité, alors que l'information que le son doit porter est
	 * binaire — « ce n'est pas toi ». Un troisième son n'ajouterait qu'une nuance à mémoriser.
	 */
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
