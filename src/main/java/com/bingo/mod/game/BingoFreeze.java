package com.bingo.mod.game;

import com.bingo.mod.game.phase.GamePhase;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Gel des joueurs pendant {@code ROLLING} (`docs/04` §5, tâche 4.7).
 *
 * <p><strong>Un modificateur d'attribut, pas un effet de potion.</strong> Slowness 255 marcherait
 * aussi, mais il s'affiche dans l'inventaire, occupe une case du HUD des effets et se retire au
 * lait — trois façons pour le joueur de croire à un bug. Le modificateur est invisible et se retire
 * proprement.
 *
 * <p><strong>Limite connue et assumée</strong> : la vitesse de déplacement bloque la marche, pas le
 * saut ni la chute. Sur 3 secondes c'est suffisant ; un gel total demanderait un mixin sur
 * {@code Entity#travel}, disproportionné pour l'effet obtenu.
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

	private BingoFreeze() {
	}

	/**
	 * Aligne le gel de tous les joueurs sur la phase courante.
	 *
	 * <p>Appelée à chaque transition : c'est le même appel qui gèle en entrant dans {@code ROLLING}
	 * et libère en en sortant. Deux méthodes distinctes laisseraient un chemin de sortie non couvert
	 * le jour où une transition est ajoutée.
	 */
	public static void apply(BingoGame game) {
		boolean freeze = game.phase() == GamePhase.ROLLING && game.freezeDuringRoll();
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			set(player, freeze);
		}
	}

	/**
	 * Garde-fou de `docs/04` §5, côté connexion.
	 *
	 * <p>À l'entrée en jeu comme à la déconnexion : un joueur qui se déconnecte pendant les 3 s de
	 * {@code ROLLING} et revient hors manche ne doit pas rester immobile. Le modificateur est
	 * temporaire, donc non persisté en NBT — mais s'appuyer sur ce détail d'implémentation pour un
	 * bug aussi pénible à diagnostiquer qu'un joueur figé serait un mauvais calcul.
	 */
	public static void reapply(@Nullable BingoGame game, ServerPlayerEntity player) {
		set(player, game != null && game.phase() == GamePhase.ROLLING && game.freezeDuringRoll());
	}

	/** Libère tout le monde, sans condition — {@code /bingo reset} et arrêt du serveur. */
	public static void releaseAll(BingoGame game) {
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			set(player, false);
		}
	}

	/**
	 * Pose ou retire le modificateur, sans jamais le poser deux fois.
	 *
	 * <p>Le retrait précède systématiquement la pose : {@code addTemporaryModifier} lève une
	 * {@code IllegalArgumentException} si un modificateur du même UUID est déjà présent, et une
	 * exception au milieu d'une transition de phase laisserait la partie à moitié démarrée.
	 */
	private static void set(ServerPlayerEntity player, boolean frozen) {
		EntityAttributeInstance speed =
				player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
		if (speed == null) {
			BingoConstants.LOGGER.warn("Joueur '{}' sans attribut de vitesse — gel ignoré",
					player.getGameProfile().getName());
			return;
		}
		speed.removeModifier(FREEZE_UUID);
		if (frozen) {
			speed.addTemporaryModifier(FREEZE);
		}
	}
}
