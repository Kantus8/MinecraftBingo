package com.bingo.mod.game;

import com.bingo.mod.game.phase.GamePhase;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Mise en attente des joueurs avant le départ : immobilisation et écran noir (`docs/04` §5, tâche
 * 4.7).
 *
 * <p><strong>Fenêtre : {@code ROLLING} <em>et</em> {@code COUNTDOWN}</strong>, alors que `docs/04` §5
 * ne parlait que du tirage. Libérer à la sortie de {@code ROLLING} rendait la mobilité pendant les
 * cinq secondes de décompte, c'est-à-dire juste après la téléportation : les joueurs partaient
 * explorer avant le « GO ». L'écart est assumé — la manche commence à {@code RUNNING}, donc c'est là
 * que tout est rendu.
 *
 * <p><strong>Trois leviers pour deux effets</strong>, et chacun couvre un trou des autres :
 * <ul>
 *   <li>le modificateur de vitesse est invisible et increvable — ni lait, ni mort, ni
 *       {@code /effect clear} ne le retirent ;</li>
 *   <li>la lenteur de potion bloque ce que l'attribut laisse passer (l'attribut ne touche pas la
 *       nage ni l'élytre) et donne au joueur un retour visible : il comprend qu'il est retenu, pas
 *       que le serveur rame ;</li>
 *   <li>l'aveuglement masque la téléportation et le décompte — sans lui, le paysage d'arrivée est
 *       lisible cinq secondes avant que quiconque puisse bouger, ce qui offre un repérage gratuit.</li>
 * </ul>
 *
 * <p>Les deux effets sont posés sans particules ni icône : le HUD des effets est déjà chargé par le
 * plateau, et une icône de lenteur au départ se lit comme un malus subi plutôt que comme une règle.
 *
 * <p><strong>Limite connue et assumée</strong> : vitesse et aveuglement bloquent la marche, pas le
 * saut ni la chute. Sur la poignée de secondes concernées c'est suffisant ; un gel total demanderait
 * un mixin sur {@code Entity#travel}, disproportionné pour l'effet obtenu.
 */
public final class BingoFreeze {

	/**
	 * UUID fixe et arbitraire : c'est la clé de retrait du modificateur. Le tirer au hasard au
	 * démarrage rendrait impossible de nettoyer un modificateur posé par une session précédente.
	 */
	private static final UUID FREEZE_UUID = UUID.fromString("5b1e5f8c-6c2a-4f4d-9c3e-0a7d2b6f41d3");

	/**
	 * {@code MULTIPLY_TOTAL} avec {@code -1.0} : la vitesse finale est multipliée par
	 * {@code 1 + (-1) = 0}. Un {@code ADDITION} de {@code -0.1} laisserait passer les joueurs sous
	 * effet de vitesse.
	 */
	private static final EntityAttributeModifier FREEZE = new EntityAttributeModifier(
			FREEZE_UUID, "bingo_roll_freeze", -1.0, EntityAttributeModifier.Operation.MULTIPLY_TOTAL);

	/**
	 * Amplificateur de la lenteur.
	 *
	 * <p>Vanilla plafonne déjà le déplacement à zéro dès l'amplificateur 5 ({@code -0.15} par niveau
	 * sur une vitesse de base de {@code 0.1}) ; monter à 250 ne fait pas « plus lent », il met la
	 * valeur hors d'atteinte de tout effet de vitesse ou d'attribut cumulé.
	 */
	private static final int SLOWNESS_AMPLIFIER = 250;

	/**
	 * Marge ajoutée à la durée calculée des effets, en millisecondes.
	 *
	 * <p>Les effets sont retirés explicitement à l'entrée dans {@code RUNNING} : cette durée n'est
	 * qu'un filet. Elle est bornée plutôt qu'infinie pour qu'un serveur tué en plein décompte ne
	 * laisse pas huit joueurs aveugles au rechargement — les effets de potion, eux, sont persistés.
	 */
	private static final long HOLD_MARGIN_MS = 1_000L;

	private BingoFreeze() {
	}

	/**
	 * Aligne l'attente de tous les joueurs sur la phase courante.
	 *
	 * <p>Appelée à chaque transition : c'est le même appel qui retient en entrant dans
	 * {@code ROLLING} et libère en entrant dans {@code RUNNING}. Deux méthodes distinctes
	 * laisseraient un chemin de sortie non couvert le jour où une transition est ajoutée.
	 */
	public static void apply(BingoGame game) {
		boolean hold = shouldHold(game);
		int duration = holdDurationTicks(game);
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			set(player, hold, duration);
		}
	}

	/**
	 * Garde-fou de `docs/04` §5, côté connexion.
	 *
	 * <p>À l'entrée en jeu comme à la déconnexion : un joueur qui se déconnecte pendant l'attente et
	 * revient hors manche ne doit rester ni immobile ni aveugle. Le modificateur d'attribut est
	 * temporaire donc non persisté — mais les effets de potion le sont, et s'appuyer sur ce détail
	 * d'implémentation pour un bug aussi pénible à diagnostiquer qu'un joueur figé serait un mauvais
	 * calcul.
	 */
	public static void reapply(@Nullable BingoGame game, ServerPlayerEntity player) {
		boolean hold = game != null && shouldHold(game);
		set(player, hold, game == null ? 0 : holdDurationTicks(game));
	}

	/** Libère tout le monde, sans condition — {@code /bingo reset} et arrêt du serveur. */
	public static void releaseAll(BingoGame game) {
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			set(player, false, 0);
		}
	}

	/**
	 * La fenêtre d'attente : du tirage jusqu'au « GO », sous réserve de la clé de règles.
	 *
	 * <p>{@code freeze_during_roll} pilote les deux effets et pas seulement l'immobilisation : un
	 * opérateur qui coupe cette clé demande que le mod ne retienne pas ses joueurs avant le départ,
	 * et lui rendre la mobilité en le laissant aveugle serait la pire des deux lectures.
	 */
	private static boolean shouldHold(BingoGame game) {
		GamePhase phase = game.phase();
		return (phase == GamePhase.ROLLING || phase == GamePhase.COUNTDOWN) && game.freezeDuringRoll();
	}

	/**
	 * Ce qu'il reste d'attente à couvrir, en ticks.
	 *
	 * <p>Calculée et non forfaitaire parce que la durée du tirage comme celle du décompte sont
	 * réglables ({@code roll_ticks}, {@code countdown_seconds}) : une constante généreuse survivrait
	 * au départ sur un décompte court, et une constante juste expirerait sur un décompte long.
	 */
	private static int holdDurationTicks(BingoGame game) {
		long remainingMs = Math.max(0L, game.phaseEndsInMs());
		if (game.phase() == GamePhase.ROLLING) {
			// Le décompte suit le tirage sans interruption : ses secondes font partie de l'attente,
			// mais son échéance n'existe pas encore au moment où la phase ROLLING est posée.
			remainingMs += game.countdownSeconds() * 1000L;
		}
		return (int) ((remainingMs + HOLD_MARGIN_MS) / 50L);
	}

	/**
	 * Pose ou retire l'immobilisation et l'aveuglement, sans jamais poser le modificateur deux fois.
	 *
	 * <p>Le retrait précède systématiquement la pose : {@code addTemporaryModifier} lève une
	 * {@code IllegalArgumentException} si un modificateur du même UUID est déjà présent, et une
	 * exception au milieu d'une transition de phase laisserait la partie à moitié démarrée.
	 */
	private static void set(ServerPlayerEntity player, boolean held, int durationTicks) {
		EntityAttributeInstance speed =
				player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
		if (speed == null) {
			BingoConstants.LOGGER.warn("Joueur '{}' sans attribut de vitesse — gel ignoré",
					player.getGameProfile().getName());
		} else {
			speed.removeModifier(FREEZE_UUID);
			if (held) {
				speed.addTemporaryModifier(FREEZE);
			}
		}

		if (!held) {
			// Retrait inconditionnel plutôt que « seulement si posé par nous » : un effet résiduel
			// d'une session précédente ou d'un serveur tué en plein décompte doit disparaître au même
			// endroit que le nôtre.
			player.removeStatusEffect(StatusEffects.SLOWNESS);
			player.removeStatusEffect(StatusEffects.BLINDNESS);
			return;
		}

		player.addStatusEffect(hidden(StatusEffects.SLOWNESS, durationTicks, SLOWNESS_AMPLIFIER));
		player.addStatusEffect(hidden(StatusEffects.BLINDNESS, durationTicks, 0));
	}

	/** Effet sans ambiance, sans particules et sans icône — voir le javadoc de classe. */
	private static StatusEffectInstance hidden(StatusEffect effect, int durationTicks, int amplifier) {
		return new StatusEffectInstance(effect, durationTicks, amplifier, false, false, false);
	}
}
