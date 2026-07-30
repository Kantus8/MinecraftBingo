package com.bingo.mod.game;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.board.BoardGenerator;
import com.bingo.mod.board.WinLines;
import com.bingo.mod.config.BingoServerConfig;
import com.bingo.mod.data.BingoData;
import com.bingo.mod.data.DifficultyProfile;
import com.bingo.mod.data.Ruleset;
import com.bingo.mod.game.detect.ObjectiveValidator;
import com.bingo.mod.game.phase.GamePhase;
import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.game.team.TeamId;
import com.bingo.mod.game.team.TeamManager;
import com.bingo.mod.integration.voicechat.BingoVoiceManager;
import com.bingo.mod.network.handler.BingoServerNetworking;
import com.bingo.mod.network.payload.BoardSyncPayload;
import com.bingo.mod.network.payload.GameEndPayload;
import com.bingo.mod.network.payload.PhasePayload;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.network.payload.TeamSnapshot;
import com.bingo.mod.network.payload.TeamSyncPayload;
import com.bingo.mod.network.payload.TileUpdatePayload;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.util.BingoConstants;
import com.bingo.mod.world.BingoPersistentState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * État de partie, unique, attaché au {@link MinecraftServer} courant (`docs/06` §2).
 *
 * <p><strong>Rien n'est stocké en double.</strong> Le score se recalcule depuis
 * {@code completionMask} ({@link BingoScoring}), la victoire aussi ({@link WinLines}), et
 * l'avancement du chrono depuis {@link #startedAtMs}. Un état minimal est un état qui ne peut
 * pas devenir incohérent.
 *
 * <p>Le cycle de vie est calé sur celui du serveur : {@link #of(MinecraftServer)} au démarrage,
 * {@link #detach(MinecraftServer)} à l'arrêt. Attacher au serveur plutôt qu'un vrai singleton
 * statique évite l'état résiduel entre deux mondes solo ouverts dans la même session.
 */
public final class BingoGame {

	/**
	 * Transitions légales (`docs/06` §1).
	 *
	 * <p>Une table plutôt que des {@code if} dispersés : c'est la seule façon de relire la
	 * machine à états sans la reconstituer mentalement depuis six sites d'appel. {@code LOBBY}
	 * est atteignable depuis <em>toutes</em> les phases — {@code /bingo reset} est le filet de
	 * sécurité (`docs/06` §1).
	 */
	private static final Map<GamePhase, Set<GamePhase>> ALLOWED_TRANSITIONS = allowedTransitions();

	private static @Nullable BingoGame instance;

	private final MinecraftServer server;

	private GamePhase phase = GamePhase.LOBBY;

	// ── La carte : partagée, immuable après le tirage ──────────────────────────
	private List<Objective> tiles = List.of();
	private long rollSeed;
	private @Nullable Identifier difficultyId;
	private @Nullable Identifier rulesetId;

	// ── État par équipe ───────────────────────────────────────────────────────
	private final TeamManager teams = new TeamManager();

	// ── Chrono (`docs/06` §2) ─────────────────────────────────────────────────
	private long startedAtMs;
	private long pausedAccumulatedMs;

	/**
	 * Instant où le chrono s'est figé, {@code 0} s'il tourne.
	 *
	 * <p>Le même champ sert à {@code PAUSED} et à {@code FINISHED} : dans les deux cas le temps
	 * affiché doit cesser d'avancer, et {@code FINISHED} n'a pas de reprise. Deux champs
	 * distincts se seraient contredits le jour où un {@code /bingo stop} arrive pendant une pause.
	 */
	private long frozenAtMs;

	private int timeLimitSeconds = BingoServerConfig.timeLimitSeconds;

	// ── Transitoire : jamais persisté ─────────────────────────────────────────

	/** Échéance de {@code ROLLING} / {@code COUNTDOWN}, {@code 0} pour les phases sans échéance. */
	private long phaseDeadlineMs;

	/** Dernière seconde annoncée, pour n'émettre qu'un bip par seconde. */
	private int lastAnnouncedSecond = -1;

	private boolean lastMinuteAnnounced;

	/** Joueurs passés spectateurs par le mod, et eux seuls (voir {@link #restoreSpectators()}). */
	private final Set<UUID> forcedSpectators = new LinkedHashSet<>();

	private @Nullable BingoPersistentState persistence;

	private BingoGame(MinecraftServer server) {
		this.server = server;
	}

	private static Map<GamePhase, Set<GamePhase>> allowedTransitions() {
		Map<GamePhase, Set<GamePhase>> allowed = new EnumMap<>(GamePhase.class);
		allowed.put(GamePhase.LOBBY, EnumSet.of(GamePhase.ROLLING));
		allowed.put(GamePhase.ROLLING, EnumSet.of(GamePhase.COUNTDOWN, GamePhase.ROLLING, GamePhase.LOBBY));
		allowed.put(GamePhase.COUNTDOWN, EnumSet.of(GamePhase.RUNNING, GamePhase.ROLLING, GamePhase.LOBBY));
		allowed.put(GamePhase.RUNNING, EnumSet.of(GamePhase.PAUSED, GamePhase.FINISHED, GamePhase.ROLLING, GamePhase.LOBBY));
		allowed.put(GamePhase.PAUSED, EnumSet.of(GamePhase.RUNNING, GamePhase.FINISHED, GamePhase.ROLLING, GamePhase.LOBBY));
		allowed.put(GamePhase.FINISHED, EnumSet.of(GamePhase.ROLLING, GamePhase.LOBBY));
		return Map.copyOf(allowed);
	}

	// ── Cycle de vie ──────────────────────────────────────────────────────────

	/** Retourne l'état de partie du serveur donné, en le créant si besoin. */
	public static BingoGame of(MinecraftServer server) {
		BingoGame current = instance;
		if (current == null || current.server != server) {
			current = new BingoGame(server);
			instance = current;
			BingoConstants.LOGGER.debug("État de partie attaché au serveur");
		}
		return current;
	}

	/** Détache l'état à l'arrêt du serveur. Sans effet si un autre serveur est attaché. */
	public static void detach(MinecraftServer server) {
		if (instance != null && instance.server == server) {
			instance = null;
			BingoConstants.LOGGER.debug("État de partie détaché du serveur");
		}
	}

	/** Instance courante, ou {@code null} si aucun serveur n'est démarré. */
	public static @Nullable BingoGame getOrNull() {
		return instance;
	}

	/**
	 * Branche la persistance et relit l'état sauvegardé (`docs/06` §2, tâche 2.16).
	 *
	 * <p>À appeler sur {@code SERVER_STARTED} et non {@code SERVER_STARTING} : les mondes
	 * n'existent pas encore au second, et les datapacks ne sont pas chargés — donc aucun
	 * identifiant de case ne serait résoluble.
	 */
	public void attachPersistence() {
		persistence = BingoPersistentState.attach(this);
	}

	/** Marque l'état comme à sauvegarder. Sans effet avant {@link #attachPersistence()}. */
	public void markDirty() {
		if (persistence != null) {
			persistence.markDirty();
		}
	}

	public MinecraftServer server() {
		return server;
	}

	// ── Lecture ───────────────────────────────────────────────────────────────

	public GamePhase phase() {
		return phase;
	}

	/** Les 25 cases, vide hors manche. Immuable. */
	public List<Objective> tiles() {
		return tiles;
	}

	public boolean hasCard() {
		return !tiles.isEmpty();
	}

	public Optional<Objective> tile(int index) {
		return index >= 0 && index < tiles.size() ? Optional.of(tiles.get(index)) : Optional.empty();
	}

	public TeamManager teams() {
		return teams;
	}

	public long rollSeed() {
		return rollSeed;
	}

	public Optional<Identifier> difficultyId() {
		return Optional.ofNullable(difficultyId);
	}

	public Optional<Identifier> rulesetId() {
		return Optional.ofNullable(rulesetId);
	}

	/** Le ruleset de la manche, vide si aucun n'est chargé — ses défauts couvrent tout. */
	public Optional<Ruleset> ruleset() {
		return rulesetId == null ? Optional.empty() : BingoData.RULESETS.get(rulesetId);
	}

	public int pointsBase() {
		return ruleset().map(Ruleset::pointsBase).orElse(BingoServerConfig.pointsBase);
	}

	public int teamSize() {
		return ruleset().map(Ruleset::teamSize).orElse(BingoServerConfig.teamSize);
	}

	public int maxTeams() {
		return ruleset().map(Ruleset::maxTeams).orElse(BingoServerConfig.maxTeams);
	}

	public boolean revealOpponentProgress() {
		return ruleset().map(Ruleset::revealOpponentProgress)
				.orElse(BingoServerConfig.revealOpponentProgress);
	}

	/** {@code ruleset.roll_animation} (`docs/05` §4.3). Désactivée, la phase {@code ROLLING} ne dure qu'un tick. */
	public boolean rollAnimation() {
		return ruleset().map(Ruleset::rollAnimation).orElse(BingoServerConfig.rollAnimation);
	}

	/** {@code ruleset.freeze_during_roll} (`docs/04` §5). */
	public boolean freezeDuringRoll() {
		return ruleset().map(Ruleset::freezeDuringRoll).orElse(BingoServerConfig.freezeDuringRoll);
	}

	/** {@code ruleset.voice.enabled} (`docs/05` §4.3) — à faux, le mod ne touche à aucun groupe vocal. */
	public boolean voiceEnabled() {
		return ruleset().map(rules -> rules.voice().enabled()).orElse(BingoServerConfig.voiceEnabled);
	}

	public boolean announceCompletions() {
		return BingoServerConfig.announceCompletions;
	}

	/** Les formes de combinaison retenues (`docs/01` §8). */
	public List<Ruleset.WinCondition> winConditions() {
		return ruleset().map(Ruleset::winConditions).orElse(Ruleset.WinCondition.ALL);
	}

	public int timeLimitSeconds() {
		return timeLimitSeconds;
	}

	/**
	 * Temps de jeu écoulé, temps de pause déduit.
	 *
	 * <p>Dérivé de {@link #startedAtMs}, jamais accumulé tick par tick : un compteur incrémenté
	 * dériverait à chaque tick sauté, et le serveur en saute sous charge.
	 */
	public long elapsedMs() {
		if (startedAtMs == 0L) {
			return 0L;
		}
		long reference = frozenAtMs > 0L ? frozenAtMs : System.currentTimeMillis();
		return Math.max(0L, reference - startedAtMs - pausedAccumulatedMs);
	}

	public int remainingSeconds() {
		return Math.max(0, timeLimitSeconds - (int) (elapsedMs() / 1000L));
	}

	/** Durée restante de la phase courante, {@link PhasePayload#NO_DEADLINE} si elle n'en a pas. */
	public int phaseEndsInMs() {
		if (phaseDeadlineMs == 0L) {
			return PhasePayload.NO_DEADLINE;
		}
		return (int) Math.max(0L, phaseDeadlineMs - System.currentTimeMillis());
	}

	// ── Commandes de partie ───────────────────────────────────────────────────

	/** Issue d'un {@code /bingo start} (`docs/05` §4.2). */
	public enum StartResult {
		STARTED,
		/** Phase autre que {@code LOBBY} ou {@code FINISHED}. */
		WRONG_PHASE,
		UNKNOWN_DIFFICULTY,
		/** Moins de 2 équipes comptant au moins un membre. */
		NOT_ENOUGH_TEAMS,
		/** Le tirage n'a pas produit 25 cases — pool trop pauvre (`docs/01` §7). */
		EMPTY_BOARD
	}

	/** Ce que {@code /bingo start} a produit, pour que la commande le restitue à l'opérateur. */
	public record StartReport(StartResult result, List<String> warnings) {

		static StartReport failed(StartResult result) {
			return new StartReport(result, List.of());
		}
	}

	/**
	 * {@code /bingo start <difficulty> [ruleset]} (`docs/05` §4.2).
	 *
	 * <p>Enchaîne tirage → {@code ROLLING} → {@code COUNTDOWN} → {@code RUNNING}, les deux
	 * dernières transitions étant pilotées par {@link #tick()}.
	 */
	public StartReport start(Identifier difficulty, Optional<Identifier> rulesetOverride) {
		return start(difficulty, rulesetOverride, false);
	}

	/**
	 * Variante de {@link #start} pouvant lever la précondition des deux équipes pourvues.
	 *
	 * @param allowSingleTeam réservé au test solo ({@code /bingo debug solo}). La précondition de
	 *                        `docs/05` §4.2 existe pour empêcher un opérateur de lancer une manche
	 *                        sans adversaire par inadvertance — pas pour rendre le mod intestable à
	 *                        un joueur. La contourner reste donc possible, mais seulement par un
	 *                        chemin nommé, journalisé, et sous permission opérateur.
	 */
	public StartReport start(Identifier difficulty, Optional<Identifier> rulesetOverride, boolean allowSingleTeam) {
		if (phase != GamePhase.LOBBY && phase != GamePhase.FINISHED) {
			return StartReport.failed(StartResult.WRONG_PHASE);
		}
		if (teams.countStaffed() < 2) {
			if (!allowSingleTeam) {
				return StartReport.failed(StartResult.NOT_ENOUGH_TEAMS);
			}
			BingoConstants.LOGGER.warn(
					"Manche démarrée avec {} équipe(s) pourvue(s) : précondition levée pour un test solo",
					teams.countStaffed());
		}
		if (teams.count() < 1) {
			return StartReport.failed(StartResult.NOT_ENOUGH_TEAMS);
		}
		return draw(difficulty, rulesetOverride);
	}

	/**
	 * {@code /bingo reroll} — nouvelle carte, complétions remises à zéro (`docs/05` §4.2).
	 *
	 * <p>Rejoue le même profil et le même ruleset : rerouler est censé corriger une carte
	 * injouable, pas changer les règles en cours de manche.
	 */
	public StartReport reroll() {
		if (difficultyId == null) {
			return StartReport.failed(StartResult.UNKNOWN_DIFFICULTY);
		}
		if (!ALLOWED_TRANSITIONS.get(phase).contains(GamePhase.ROLLING)) {
			return StartReport.failed(StartResult.WRONG_PHASE);
		}
		return draw(difficultyId, Optional.ofNullable(rulesetId));
	}

	private StartReport draw(Identifier difficulty, Optional<Identifier> rulesetOverride) {
		Optional<DifficultyProfile> found = BingoData.DIFFICULTIES.get(difficulty);
		if (found.isEmpty()) {
			return StartReport.failed(StartResult.UNKNOWN_DIFFICULTY);
		}
		DifficultyProfile profile = found.get();

		Identifier resolvedRuleset = rulesetOverride.or(profile::ruleset).orElse(null);
		Optional<Ruleset> rules = resolvedRuleset == null
				? Optional.empty()
				: BingoData.RULESETS.get(resolvedRuleset);

		long seed = System.nanoTime();
		BoardGenerator.BoardDraw drawn = BoardGenerator.generate(profile, rules, seed);
		if (!drawn.isComplete()) {
			BingoConstants.LOGGER.error("Tirage refusé : {} case(s) sur {} — {}",
					drawn.tiles().size(), BingoBoard.TILE_COUNT, drawn.warnings());
			return new StartReport(StartResult.EMPTY_BOARD, drawn.warnings());
		}

		tiles = drawn.tiles();
		rollSeed = drawn.seed();
		difficultyId = difficulty;
		rulesetId = resolvedRuleset;
		timeLimitSeconds = profile.effectiveTimeLimitSeconds(rules, BingoServerConfig.timeLimitSeconds);

		startedAtMs = 0L;
		pausedAccumulatedMs = 0L;
		frozenAtMs = 0L;
		lastAnnouncedSecond = -1;
		lastMinuteAnnounced = false;

		teams.clearCompletions();
		teams.rebuildIndexes(tiles);

		List<String> warnings = new ArrayList<>(drawn.warnings());
		teams.all().stream()
				.filter(team -> !team.isEmpty() && team.size() < teamSize())
				.forEach(team -> warnings.add("Équipe '" + team.id() + "' incomplète : "
						+ team.size() + "/" + teamSize() + " joueur(s)"));

		if (phase == GamePhase.ROLLING) {
			// Reroll pendant le tirage : la phase ne change pas, mais l'animation doit repartir de
			// zéro — transitionTo court-circuite une transition vers la phase courante.
			phaseDeadlineMs = System.currentTimeMillis() + rollDurationMs();
			markDirty();
			BingoServerNetworking.broadcastPhase(this);
		} else {
			transitionTo(GamePhase.ROLLING);
		}

		BingoServerNetworking.broadcastBoardSync(this);

		// roll_start APRÈS board_sync : l'animation résout ses icônes dans le catalogue, et le HUD
		// doit déjà avoir une carte à dessiner au moment où elle démarre (`docs/06` §3.4, ordre des
		// paquets). Le paquet n'est pas émis quand l'animation est coupée — inutile de faire
		// calculer 3 s de défilement à un client qui n'affichera rien.
		if (rollAnimation()) {
			BingoServerNetworking.broadcastRollStart(this, rollDurationMs());
		}

		return new StartReport(StartResult.STARTED, List.copyOf(warnings));
	}

	/** {@code /bingo pause} — fige le chrono et suspend la validation (`docs/05` §4.2). */
	public boolean pause() {
		if (phase != GamePhase.RUNNING) {
			return false;
		}
		frozenAtMs = System.currentTimeMillis();
		transitionTo(GamePhase.PAUSED);
		return true;
	}

	/** {@code /bingo resume}. */
	public boolean resume() {
		if (phase != GamePhase.PAUSED) {
			return false;
		}
		if (frozenAtMs > 0L) {
			pausedAccumulatedMs += System.currentTimeMillis() - frozenAtMs;
			frozenAtMs = 0L;
		}
		transitionTo(GamePhase.RUNNING);
		return true;
	}

	/** {@code /bingo stop} — fin sans vainqueur. */
	public boolean stop() {
		if (!phase.isRoundActive()) {
			return false;
		}
		finish(GameEndReason.STOP, List.of(), Optional.empty());
		return true;
	}

	/**
	 * {@code /bingo reset} — remise à zéro complète, depuis n'importe quelle phase.
	 *
	 * <p>Contrairement à {@code stop}, détruit aussi les équipes (`docs/05` §3) et rend leur
	 * mode de jeu aux joueurs passés spectateurs.
	 */
	public void reset() {
		restoreSpectators();
		// Sans condition et avant tout le reste : un reset déclenché pendant ROLLING doit rendre la
		// mobilité même si la phase était déjà LOBBY et que transitionTo va court-circuiter.
		BingoFreeze.releaseAll(this);

		tiles = List.of();
		rollSeed = 0L;
		difficultyId = null;
		rulesetId = null;
		timeLimitSeconds = BingoServerConfig.timeLimitSeconds;
		startedAtMs = 0L;
		pausedAccumulatedMs = 0L;
		frozenAtMs = 0L;
		phaseDeadlineMs = 0L;
		lastAnnouncedSecond = -1;
		lastMinuteAnnounced = false;

		teams.removeAll();
		transitionTo(GamePhase.LOBBY);

		// Après transitionTo, qui a déjà pu remettre tout le monde dans le lobby : ce qui reste à
		// faire est de dissoudre les groupes d'équipe (cas limite n°9 de `docs/02` §4). L'appel est
		// aussi le filet de sécurité du cas où la phase était déjà LOBBY — transitionTo est alors un
		// no-op et n'aurait rien réassigné.
		BingoVoiceManager.get().onReset(this);

		BingoServerNetworking.broadcastBoardSync(this);
		BingoServerNetworking.broadcastTeamSync(this);
	}

	// ── Machine à états ───────────────────────────────────────────────────────

	/**
	 * Change de phase en refusant les transitions illégales.
	 *
	 * <p>Un refus est journalisé en ERROR et non silencieux : une transition interdite est
	 * toujours un bug d'appelant, et la garder muette la rendrait indétectable jusqu'à ce qu'un
	 * joueur signale un chrono figé.
	 */
	private void transitionTo(GamePhase next) {
		if (next == phase) {
			return;
		}
		if (!ALLOWED_TRANSITIONS.getOrDefault(phase, Set.of()).contains(next)) {
			BingoConstants.LOGGER.error("Transition de phase refusée : {} -> {}", phase, next);
			return;
		}

		GamePhase previous = phase;
		phase = next;
		BingoConstants.LOGGER.info("Phase : {} -> {}", previous, next);

		phaseDeadlineMs = switch (next) {
			case ROLLING -> System.currentTimeMillis() + rollDurationMs();
			case COUNTDOWN -> System.currentTimeMillis() + countdownSeconds() * 1000L;
			default -> 0L;
		};

		if (next == GamePhase.RUNNING && startedAtMs == 0L) {
			startedAtMs = System.currentTimeMillis();
			sendTeamlessToSpectator();
			BingoAnnouncer.gameStarted(this);
		}

		// Gel : un seul appel couvre l'entrée dans ROLLING comme toutes les sorties (`docs/04` §5).
		BingoFreeze.apply(this);

		// Le burst de fin d'animation est calé sur la sortie de ROLLING et non sur un minuteur
		// client : c'est le seul instant que le serveur connaisse exactement, et il coïncide avec le
		// t=3000 de la timeline (`docs/04` §2.3).
		if (previous == GamePhase.ROLLING && next == GamePhase.COUNTDOWN && rollAnimation()) {
			BingoVfx.rollFinale(this);
		}

		markDirty();
		BingoServerNetworking.broadcastPhase(this);

		// Vocal : la bascule suit la phase et rien d'autre (`docs/02` §2). Placée après la diffusion
		// pour que le HUD annonce le changement avant que l'oreille l'entende — dans l'autre ordre,
		// la coupure du groupe lobby précéderait l'affichage de RUNNING.
		BingoVoiceManager.get().apply(this);
	}

	/**
	 * Durée de la phase {@code ROLLING}, en millisecondes.
	 *
	 * <p>{@code 0} quand l'animation est coupée : {@link #tick()} enchaîne alors sur
	 * {@code COUNTDOWN} au tick suivant, plutôt que d'imposer 3 secondes de phase vide.
	 *
	 * <p>La durée part dans {@code roll_start} et le client recalcule ses seuils proportionnellement
	 * (`docs/04` §1) : {@code roll_ticks} n'est donc plus la clé décorative que redoutait
	 * `docs/01` §8.
	 */
	private long rollDurationMs() {
		if (!rollAnimation()) {
			return 0L;
		}
		int ticks = ruleset().map(rules -> rules.timings().rollTicks()).orElse(60);
		return ticks * 50L;
	}

	private int countdownSeconds() {
		return ruleset().map(rules -> rules.timings().countdownSeconds())
				.orElse(BingoServerConfig.countdownSeconds);
	}

	/**
	 * Tick serveur : c'est ici que vivent les seules échéances de la partie.
	 *
	 * <p>Aucun paquet n'est émis par tick (`docs/06` §4) — seules les transitions et les
	 * validations en produisent.
	 */
	public void tick() {
		// Réconciliation vocale : une passe par seconde en manche, sans effet ailleurs. Elle
		// s'auto-limite en cadence (`docs/02` §4, cas limites 4 et 8).
		BingoVoiceManager.get().tick(this);

		switch (phase) {
			case ROLLING -> {
				if (System.currentTimeMillis() >= phaseDeadlineMs) {
					transitionTo(GamePhase.COUNTDOWN);
				}
			}
			case COUNTDOWN -> tickCountdown();
			case RUNNING -> tickRunning();
			default -> {
			}
		}
	}

	private void tickCountdown() {
		long remainingMs = phaseDeadlineMs - System.currentTimeMillis();
		if (remainingMs <= 0L) {
			lastAnnouncedSecond = -1;
			transitionTo(GamePhase.RUNNING);
			return;
		}

		int second = (int) Math.ceil(remainingMs / 1000.0);
		if (second != lastAnnouncedSecond) {
			lastAnnouncedSecond = second;
			BingoAnnouncer.countdown(this, second);
		}
	}

	private void tickRunning() {
		int remaining = remainingSeconds();

		if (remaining <= 0) {
			endByTimeLimit();
			return;
		}

		if (!lastMinuteAnnounced && remaining <= 60) {
			lastMinuteAnnounced = true;
			BingoAnnouncer.lastMinute(this);
		}

		// Les 10 dernières secondes (`docs/05` §5). Le test sur lastAnnouncedSecond garantit un
		// bip par seconde et non un par tick.
		if (remaining <= 10 && remaining != lastAnnouncedSecond) {
			lastAnnouncedSecond = remaining;
			BingoAnnouncer.finalCountdown(this, remaining);
		}

		// Le scan FIND est le seul travail périodique du mod : 2 fois par seconde, et seulement
		// pour les équipes qui cherchent encore quelque chose (`docs/01` §4.2).
		if (server.getTicks() % 10 == 0) {
			ObjectiveValidator.scanInventories(this);
		}
		if (server.getTicks() % 20 == 0) {
			ObjectiveValidator.scanPositions(this);
		}
	}

	// ── Validation et fin de partie ───────────────────────────────────────────

	/**
	 * Enregistre l'avancement d'une case et en tire toutes les conséquences (étapes 5 à 8 de
	 * `docs/06` §6).
	 *
	 * <p>Point d'entrée unique de <em>toute</em> validation, y compris
	 * {@code /bingo debug complete} : la victoire, le score, les paquets et les annonces
	 * découlent d'ici et de nulle part ailleurs.
	 *
	 * @param newProgress avancement absolu, jamais un delta — le scan {@code FIND} recompte
	 *                    l'inventaire à chaque passage et n'a pas de delta à offrir
	 * @return {@code true} si quelque chose a changé
	 */
	public boolean applyProgress(BingoTeam team, int index, int newProgress) {
		Optional<Objective> found = tile(index);
		if (found.isEmpty() || team.isCompleted(index)) {
			return false;
		}
		Objective objective = found.get();

		int clamped = Math.min(newProgress, objective.count());
		boolean progressed = team.setProgress(index, clamped);
		boolean completed = clamped >= objective.count();

		if (!progressed && !completed) {
			return false;
		}

		long now = System.currentTimeMillis();
		int maskBefore = team.completionMask();
		if (completed) {
			team.complete(index, now);
			team.rebuildIndex(tiles);
		}
		int oneAwayCount = WinLines.oneAway(team.completionMask(), winConditions()).size();

		markDirty();
		BingoServerNetworking.broadcastTileUpdate(this, new TileUpdatePayload(
				team.id(), index, clamped, completed, completed ? now : 0L));
		BingoServerNetworking.broadcastScoreUpdate(this);

		if (!completed) {
			return true;
		}

		if (objective.announce()) {
			BingoAnnouncer.objectiveCompleted(this, team, objective);
		}

		Optional<WinLines.Line> winning = WinLines.firstCompleted(team.completionMask(), winConditions());
		if (winning.isPresent()) {
			finish(GameEndReason.LINE, resolveWinners(winning.get()), winning);
		} else if (oneAwayCount > WinLines.oneAway(maskBefore, winConditions()).size()) {
			// Uniquement quand cette case vient de <em>créer</em> un 4/5 : sinon le son sonnerait à
			// chaque validation ultérieure tant qu'une combinaison reste à une case.
			//
			// Son local à l'équipe concernée uniquement : le « 4/5 » est l'information la plus
			// précieuse de la partie, l'annoncer publiquement retirerait tout l'intérêt de lire le
			// HUD adverse (`docs/05` §5).
			BingoAnnouncer.oneAway(this, team);
		}
		return true;
	}

	/**
	 * Les équipes déclarées gagnantes, égalités de `docs/05` §1.4 comprises.
	 *
	 * <p>Deux équipes peuvent compléter leur 5ᵉ case dans le même tick. L'ordre est : timestamp
	 * serveur, puis score, puis victoire partagée.
	 */
	private List<TeamId> resolveWinners(WinLines.Line line) {
		List<BingoTeam> completed = teams.all().stream()
				.filter(team -> (team.completionMask() & line.mask()) == line.mask())
				.toList();

		if (completed.size() <= 1) {
			return completed.stream().map(BingoTeam::id).toList();
		}

		long earliest = completed.stream().mapToLong(team -> team.completedAtMs(line)).min().orElse(0L);
		List<BingoTeam> firstWave = completed.stream()
				.filter(team -> team.completedAtMs(line) == earliest)
				.toList();

		if (firstWave.size() <= 1) {
			return firstWave.stream().map(BingoTeam::id).toList();
		}

		int bestScore = firstWave.stream()
				.mapToInt(team -> BingoScoring.teamScore(team, tiles, pointsBase()))
				.max().orElse(0);
		// Toujours à égalité après le timestamp et le score : victoire partagée (cas 3).
		return firstWave.stream()
				.filter(team -> BingoScoring.teamScore(team, tiles, pointsBase()) == bestScore)
				.map(BingoTeam::id)
				.toList();
	}

	/** Fin par temps écoulé : le classement tranche (`docs/05` §1.3). */
	private void endByTimeLimit() {
		List<BingoScoring.Standing> ranking = ranking();
		int tied = BingoScoring.tiedWithFirst(ranking);

		// Match nul : aucune équipe déclarée gagnante, plutôt qu'une raison GameEndReason dédiée.
		List<TeamId> winners = BingoScoring.isDraw(ranking)
				? List.of()
				: ranking.stream().limit(tied).map(standing -> standing.team().id()).toList();

		finish(GameEndReason.TIME, winners, Optional.empty());
	}

	private void finish(GameEndReason reason, List<TeamId> winners, Optional<WinLines.Line> line) {
		if (frozenAtMs == 0L) {
			frozenAtMs = System.currentTimeMillis();
		}
		transitionTo(GamePhase.FINISHED);

		GameEndPayload payload = new GameEndPayload(
				reason,
				winners,
				line.map(WinLines.Line::indices).orElse(List.of()),
				ranking().stream().map(ScoreUpdatePayload.Entry::of).toList());

		BingoServerNetworking.broadcastGameEnd(this, payload);
		BingoAnnouncer.gameEnded(this, payload);
		markDirty();
	}

	public List<BingoScoring.Standing> ranking() {
		return BingoScoring.ranking(teams.all(), tiles, pointsBase(), winConditions());
	}

	// ── Rechargement de datapack (`docs/06` §2, §3.4) ──────────────────────────

	/**
	 * Réaligne la carte en cours sur le catalogue rechargé.
	 *
	 * <p>`docs/06` §3.4 fait de {@code /bingo reload} l'outil qu'un admin utilise entre deux
	 * manches — mais rien n'interdit de le lancer <em>pendant</em> une manche, et les cases
	 * tenaient alors des instances d'{@link Objective} devenues orphelines : le serveur validait
	 * l'ancienne définition pendant que le client, qui vient de recevoir le nouveau catalogue,
	 * dessinait le nouveau {@code count} — ou une case placeholder si l'objectif avait disparu.
	 *
	 * <p>Deux sorties, selon ce que le nouveau catalogue contient :
	 * <ul>
	 *   <li><strong>les 25 identifiants répondent</strong> — les cases reprennent les définitions
	 *       fraîches, les index inversés sont reconstruits, et l'avancement est réappliqué contre
	 *       le nouveau {@code count} (un {@code count} abaissé doit pouvoir valider la case
	 *       séance tenante, sans quoi elle afficherait {@code 3/2} jusqu'à la fin) ;</li>
	 *   <li><strong>un identifiant a disparu</strong> — même dégradation qu'au chargement du monde
	 *       ({@link #readNbt}) : la carte est abandonnée avec un WARN. Une manche interrompue est
	 *       un moindre mal comparé à une case qui ne pourra jamais être validée.</li>
	 * </ul>
	 */
	public void onDataReload() {
		if (tiles.isEmpty()) {
			return;
		}

		List<Objective> refreshed = new ArrayList<>(tiles.size());
		List<String> missing = new ArrayList<>();
		for (Objective previous : tiles) {
			Optional<Objective> found = BingoData.OBJECTIVES.get(previous.id());
			if (found.isEmpty()) {
				missing.add(previous.id().toString());
			} else {
				refreshed.add(found.get());
			}
		}

		if (!missing.isEmpty()) {
			BingoConstants.LOGGER.warn(
					"Rechargement : {} case(s) référencent un objectif disparu ({}) — carte abandonnée",
					missing.size(), missing);
			abandonBoard();
			return;
		}

		tiles = List.copyOf(refreshed);
		teams.rebuildIndexes(tiles);
		markDirty();

		// Réapplication de l'avancement : applyProgress clampe sur le nouveau count et coche la
		// case si elle est désormais atteinte, avec les paquets et la détection de victoire qui
		// vont avec. Sans changement de définition, c'est un no-op — il ne part aucun paquet.
		//
		// Uniquement en RUNNING, comme toute validation (étape 1 de `docs/06` §6 et table des
		// phases de §1) : en PAUSED la validation est suspendue, et en FINISHED cocher une case
		// rejouerait un game_end qui réécrirait le vainqueur d'une manche déjà jouée.
		if (phase != GamePhase.RUNNING) {
			return;
		}
		for (BingoTeam team : List.copyOf(teams.all())) {
			for (int index = 0; index < tiles.size(); index++) {
				// La boucle s'arrête dès qu'une case validée ici déclenche la victoire : la manche
				// est finie, plus rien ne doit être coché.
				if (phase != GamePhase.RUNNING) {
					return;
				}
				if (!team.isCompleted(index)) {
					applyProgress(team, index, team.progress(index));
				}
			}
		}
	}

	/**
	 * Abandonne la carte courante en respectant la machine à états de `docs/06` §1.
	 *
	 * <p>La phase d'arrivée dépend de celle de départ, parce qu'il n'existe pas d'arête
	 * {@code ROLLING → FINISHED} ni {@code COUNTDOWN → FINISHED} : avant le départ effectif, rien
	 * n'a été joué et le salon est la seule fin cohérente ; après, la manche a une histoire et un
	 * classement, donc elle se termine.
	 */
	private void abandonBoard() {
		if (phase == GamePhase.RUNNING || phase == GamePhase.PAUSED) {
			// finish() AVANT de vider les cases : son classement final se dérive de la carte.
			finish(GameEndReason.STOP, List.of(), Optional.empty());
		}

		tiles = List.of();
		teams.rebuildIndexes(tiles);
		phaseDeadlineMs = 0L;

		if (phase != GamePhase.FINISHED) {
			transitionTo(GamePhase.LOBBY);
		}
		markDirty();
	}

	// ── Spectateurs (`docs/05` §3) ─────────────────────────────────────────────

	/**
	 * Passe les joueurs sans équipe en spectateur au départ de la manche (`docs/05` §3).
	 *
	 * <p>Les joueurs concernés sont mémorisés, et ce sont les <strong>seuls</strong> que
	 * {@link #reset()} ramènera au mode par défaut. Rendre son mode à tout spectateur trouvé
	 * remettrait en jeu un modérateur qui observait la partie de son plein gré — un effet de bord
	 * qu'un {@code /bingo reset} n'a aucune raison d'avoir.
	 *
	 * <p>Le mode d'origine n'est pas mémorisé, faute de quoi il faudrait le persister : le mode par
	 * défaut du serveur est le repli, et `docs/05` §3 impose le passage spectateur sans dire comment
	 * en sortir.
	 */
	private void sendTeamlessToSpectator() {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (teams.of(player.getUuid()).isEmpty()
					&& player.interactionManager.getGameMode() != GameMode.SPECTATOR) {
				forcedSpectators.add(player.getUuid());
				player.changeGameMode(GameMode.SPECTATOR);
			}
		}
	}

	private void restoreSpectators() {
		for (UUID uuid : forcedSpectators) {
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
			if (player != null && player.interactionManager.getGameMode() == GameMode.SPECTATOR) {
				player.changeGameMode(server.getDefaultGameMode());
			}
		}
		forcedSpectators.clear();
	}

	// ── Projections réseau (`docs/06` §3.1) ────────────────────────────────────

	public BoardSyncPayload boardSync() {
		return new BoardSyncPayload(
				BingoData.revision(),
				phase,
				tiles.stream().map(Objective::id).toList(),
				rollSeed,
				difficultyId(),
				rulesetId(),
				timeLimitSeconds,
				remainingSeconds(),
				elapsedMs(),
				revealOpponentProgress(),
				pointsBase(),
				winConditions(),
				teamSnapshots());
	}

	public PhasePayload phasePayload() {
		return new PhasePayload(phase, elapsedMs(), remainingSeconds(), phaseEndsInMs());
	}

	public TeamSyncPayload teamSync() {
		return new TeamSyncPayload(teamSnapshots());
	}

	public ScoreUpdatePayload scoreUpdate() {
		return ScoreUpdatePayload.of(ranking());
	}

	private List<TeamSnapshot> teamSnapshots() {
		return teams.all().stream().map(TeamSnapshot::of).toList();
	}

	// ── Persistance (`docs/06` §2) ─────────────────────────────────────────────

	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putString("phase", phase.name());
		NbtList tileIds = new NbtList();
		tiles.forEach(objective -> tileIds.add(NbtString.of(objective.id().toString())));
		nbt.put("tiles", tileIds);
		nbt.putLong("rollSeed", rollSeed);
		if (difficultyId != null) {
			nbt.putString("difficulty", difficultyId.toString());
		}
		if (rulesetId != null) {
			nbt.putString("ruleset", rulesetId.toString());
		}
		nbt.putLong("startedAtMs", startedAtMs);
		nbt.putLong("pausedMs", pausedAccumulatedMs);
		nbt.putLong("frozenAtMs", frozenAtMs);
		nbt.putInt("timeLimitSeconds", timeLimitSeconds);
		// Horodatage de la sauvegarde : c'est ce qui permet de déduire le temps d'arrêt du serveur
		// à la relecture (voir readNbt).
		nbt.putLong("savedAtMs", System.currentTimeMillis());
		nbt.put("teams", teams.writeNbt());
		return nbt;
	}

	/**
	 * Relit l'état sauvegardé.
	 *
	 * <p><strong>Dégradation exigée par `docs/06` §2</strong> : si une case référence un objectif
	 * disparu du datapack, la partie bascule en {@code FINISHED} avec un WARN. Ni crash, ni case
	 * fantôme — une manche interrompue est un moindre mal comparé à une grille dont une case ne
	 * peut jamais être validée.
	 */
	public void readNbt(NbtCompound nbt) {
		phase = GamePhase.byName(nbt.getString("phase"));
		rollSeed = nbt.getLong("rollSeed");
		difficultyId = nbt.contains("difficulty") ? Identifier.tryParse(nbt.getString("difficulty")) : null;
		rulesetId = nbt.contains("ruleset") ? Identifier.tryParse(nbt.getString("ruleset")) : null;
		startedAtMs = nbt.getLong("startedAtMs");
		pausedAccumulatedMs = nbt.getLong("pausedMs");
		frozenAtMs = nbt.getLong("frozenAtMs");
		timeLimitSeconds = nbt.contains("timeLimitSeconds")
				? nbt.getInt("timeLimitSeconds")
				: BingoServerConfig.timeLimitSeconds;

		teams.readNbt(nbt.getList("teams", TeamManager.nbtEntryType()));

		List<Objective> restored = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		NbtList tileIds = nbt.getList("tiles", NbtElement.STRING_TYPE);
		for (int i = 0; i < tileIds.size(); i++) {
			String raw = tileIds.getString(i);
			Identifier id = Identifier.tryParse(raw);
			Optional<Objective> objective = id == null ? Optional.empty() : BingoData.OBJECTIVES.get(id);
			if (objective.isEmpty()) {
				missing.add(raw);
			} else {
				restored.add(objective.get());
			}
		}

		if (!missing.isEmpty()) {
			BingoConstants.LOGGER.warn(
					"{} case(s) référencent un objectif disparu du datapack ({}) — manche basculée en FINISHED",
					missing.size(), missing);
			tiles = List.of();
			phase = GamePhase.FINISHED;
			// Sans échéance : la partie ne doit surtout pas repartir sur une carte incomplète.
			phaseDeadlineMs = 0L;
			if (frozenAtMs == 0L) {
				frozenAtMs = System.currentTimeMillis();
			}
			return;
		}

		tiles = List.copyOf(restored);
		teams.rebuildIndexes(tiles);

		// Le chrono est dérivé d'horloges murales : sans correction, une heure de serveur éteint
		// serait comptée comme une heure de jeu, et une manche reprise se terminerait aussitôt par
		// temps écoulé. Le temps d'arrêt est du temps de pause — c'est exactement ce que
		// pausedAccumulatedMs représente.
		long savedAtMs = nbt.getLong("savedAtMs");
		if (phase.isTimerTicking() && savedAtMs > 0L) {
			long downtime = Math.max(0L, System.currentTimeMillis() - savedAtMs);
			pausedAccumulatedMs += downtime;
			BingoConstants.LOGGER.info("Reprise de manche : {} s d'arrêt serveur déduites du chrono",
					downtime / 1000L);
		}

		// ROLLING et COUNTDOWN sont des phases de quelques secondes dont l'échéance est
		// transitoire : les relire telles quelles laisserait la partie figée pour toujours, faute
		// de phaseDeadlineMs. On les réarme au redémarrage.
		if (phase == GamePhase.ROLLING || phase == GamePhase.COUNTDOWN) {
			BingoConstants.LOGGER.info("Reprise en {} : échéance de phase réarmée", phase);
			phaseDeadlineMs = System.currentTimeMillis()
					+ (phase == GamePhase.ROLLING ? rollDurationMs() : countdownSeconds() * 1000L);
		}
	}

	/** Les UUID des joueurs connectés, pour {@code autobalance} et le passage spectateur. */
	public List<UUID> connectedPlayers() {
		return server.getPlayerManager().getPlayerList().stream()
				.map(ServerPlayerEntity::getUuid)
				.toList();
	}
}
