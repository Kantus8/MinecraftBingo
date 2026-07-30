package com.bingo.mod.game.phase;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * Les six phases d'une partie de Bingo (docs/06 §1).
 *
 * <p><strong>L'ordre de cet enum est contractuel.</strong> La phase est encodée sur le
 * réseau par {@code buf.writeByte(phase.ordinal())} (docs/06 §3.3) et persistée par son
 * nom en NBT (docs/06 §2). Ne jamais réordonner, insérer au milieu, ni supprimer une
 * constante : seulement ajouter à la fin.
 *
 * <p>Les quatre drapeaux reproduisent exactement la table des phases de docs/06 §1 —
 * ils sont la source unique de ces règles, pour éviter les {@code switch} dispersés.
 */
public enum GamePhase {

	/** Hors manche. Vocal global, aucune validation. */
	LOBBY(false, false, false, false),

	/** Animation Slot Machine, 3 s. Joueurs gelés (docs/04 §5). */
	ROLLING(false, false, false, true),

	/** Décompte avant le départ, 5 s. Le vocal reste global (docs/02 §2). */
	COUNTDOWN(false, false, false, false),

	/** Manche en cours : chrono actif, objectifs validables, groupes vocaux d'équipe. */
	RUNNING(true, true, true, false),

	/** Arbitrage : chrono figé, validation suspendue, retour au vocal global. */
	PAUSED(false, false, false, false),

	/** Manche terminée (victoire, temps écoulé ou {@code /bingo stop}). */
	FINISHED(false, false, false, false);

	private static final GamePhase[] BY_ORDINAL = values();

	private final boolean timerTicking;
	private final boolean objectivesValidated;
	private final boolean teamVoiceGroups;
	private final boolean playersFrozen;

	GamePhase(boolean timerTicking, boolean objectivesValidated, boolean teamVoiceGroups, boolean playersFrozen) {
		this.timerTicking = timerTicking;
		this.objectivesValidated = objectivesValidated;
		this.teamVoiceGroups = teamVoiceGroups;
		this.playersFrozen = playersFrozen;
	}

	/** Le chrono avance-t-il dans cette phase ? */
	public boolean isTimerTicking() {
		return timerTicking;
	}

	/** Les événements de jeu peuvent-ils cocher une case ? (étape 1 de docs/06 §6) */
	public boolean areObjectivesValidated() {
		return objectivesValidated;
	}

	/** Les joueurs doivent-ils être dans leur groupe vocal d'équipe plutôt que dans le lobby ? */
	public boolean usesTeamVoiceGroups() {
		return teamVoiceGroups;
	}

	/** Les joueurs doivent-ils être immobilisés ? */
	public boolean arePlayersFrozen() {
		return playersFrozen;
	}

	/** Une manche est-elle engagée (du tirage à la fin, pause incluse) ? */
	public boolean isRoundActive() {
		return this != LOBBY && this != FINISHED;
	}

	/**
	 * Décode une phase reçue sur le réseau ou lue en NBT.
	 *
	 * @return la phase correspondante, ou {@link #LOBBY} si l'ordinal est hors bornes
	 *         (client plus ancien que le serveur) — jamais d'exception sur le thread réseau.
	 */
	public static GamePhase byOrdinal(int ordinal) {
		if (ordinal < 0 || ordinal >= BY_ORDINAL.length) {
			BingoConstants.LOGGER.warn("Ordinal de phase inconnu ({}), repli sur LOBBY", ordinal);
			return LOBBY;
		}
		return BY_ORDINAL[ordinal];
	}

	/**
	 * Décode une phase persistée par son nom.
	 *
	 * @return la phase correspondante, ou {@link #LOBBY} si le nom est inconnu.
	 */
	public static GamePhase byName(String name) {
		for (GamePhase phase : BY_ORDINAL) {
			if (phase.name().equalsIgnoreCase(name)) {
				return phase;
			}
		}
		BingoConstants.LOGGER.warn("Nom de phase inconnu ('{}'), repli sur LOBBY", name);
		return LOBBY;
	}

	/**
	 * Clé de traduction du libellé neutre de la phase.
	 *
	 * <p>Cas particulier de {@code COUNTDOWN} : la clé {@code bingo.phase.countdown} porte
	 * un {@code %s} (les secondes restantes) parce qu'elle est destinée au HUD. Le libellé
	 * sans argument vit donc sous une clé distincte.
	 */
	public String translationKey() {
		String base = BingoConstants.key("phase." + name().toLowerCase());
		return this == COUNTDOWN ? base + ".label" : base;
	}

	/** Libellé traduisible de la phase, sans argument. Toujours une instance neuve, donc formatable. */
	public MutableText displayName() {
		return Text.translatable(translationKey());
	}
}
