package com.bingo.mod.client;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.board.WinLines;
import com.bingo.mod.client.config.BingoClientConfig;
import com.bingo.mod.client.roll.RollAnimationState;
import com.bingo.mod.client.roll.RollSparks;
import com.bingo.mod.data.Ruleset;
import com.bingo.mod.game.phase.GamePhase;
import com.bingo.mod.game.team.TeamId;
import com.bingo.mod.network.payload.BoardSyncPayload;
import com.bingo.mod.network.payload.GameEndPayload;
import com.bingo.mod.network.payload.ObjectiveProjection;
import com.bingo.mod.network.payload.ObjectiveSyncPayload;
import com.bingo.mod.network.payload.PhasePayload;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.network.payload.TeamSnapshot;
import com.bingo.mod.network.payload.TileUpdatePayload;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Miroir client de l'état de partie.
 *
 * <p><strong>Purement présentationnel</strong> (garde-fou 4 de `docs/06` §3.4). Aucune décision de
 * jeu ne s'appuie sur cet état : pas de validation d'objectif côté client, jamais. Il n'existe que
 * pour que le HUD et l'écran aient quelque chose à dessiner.
 *
 * <p>Le chrono est <strong>extrapolé</strong> depuis la dernière réception et non resynchronisé à
 * chaque tick (`docs/06` §4). Une dérive de quelques centaines de millisecondes sur l'affichage est
 * invisible ; 20 paquets par seconde et par joueur pour l'éviter ne le seraient pas.
 *
 * <p>Statique et non instancié : il n'existe qu'un client, et le passer en paramètre à travers le
 * rendu n'apporterait qu'un argument de plus à chaque signature.
 */
public final class BingoClientState {

	/** Délai minimal entre deux {@code request_sync}, pour ne pas boucler sur une désync. */
	private static final long RESYNC_COOLDOWN_MS = 2_000L;

	private static int catalogRevision = -1;
	private static Map<Identifier, ObjectiveProjection> catalog = Map.of();

	private static GamePhase phase = GamePhase.LOBBY;
	private static List<Identifier> tiles = List.of();
	private static final Map<TeamId, TeamSnapshot> teams = new LinkedHashMap<>();
	private static List<ScoreUpdatePayload.Entry> scores = List.of();
	private static boolean revealOpponentProgress = true;
	private static int pointsBase = 100;

	/**
	 * Formes de combinaison actives, reçues avec la carte (tâche 4.9).
	 *
	 * <p>Toutes par défaut : avant le premier {@code board_sync}, il n'y a pas de carte à mettre en
	 * avant, donc la valeur n'a aucune conséquence visible.
	 */
	private static List<Ruleset.WinCondition> winConditions = Ruleset.WinCondition.ALL;

	private static long elapsedMsAtSync;
	private static int remainingSecondsAtSync;
	private static int phaseEndsInMsAtSync = PhasePayload.NO_DEADLINE;
	private static long syncedAtMs;

	private static @Nullable GameEndPayload lastEnd;
	private static long finishedAtMs;

	private static long lastResyncRequestMs;

	private BingoClientState() {
	}

	// ── Réception ─────────────────────────────────────────────────────────────

	public static void onObjectiveSync(ObjectiveSyncPayload payload) {
		Map<Identifier, ObjectiveProjection> received = new LinkedHashMap<>();
		payload.objectives().forEach(projection -> received.put(projection.id(), projection));
		catalog = Map.copyOf(received);
		catalogRevision = payload.revision();
		BingoConstants.LOGGER.debug("Catalogue reçu : {} objectifs, révision {}",
				catalog.size(), catalogRevision);
	}

	public static void onBoardSync(BoardSyncPayload payload) {
		phase = payload.phase();
		tiles = payload.tiles();
		revealOpponentProgress = payload.revealOpponentProgress();
		pointsBase = payload.pointsBase();
		// Une liste vide viendrait d'un serveur dont toutes les formes sont inconnues du client :
		// mieux vaut les 12 combinaisons que zéro mise en avant.
		winConditions = payload.winConditions().isEmpty()
				? Ruleset.WinCondition.ALL
				: payload.winConditions();
		remainingSecondsAtSync = payload.remainingSeconds();
		elapsedMsAtSync = payload.elapsedMs();
		syncedAtMs = System.currentTimeMillis();

		teams.clear();
		payload.teams().forEach(team -> teams.put(team.id(), team));

		if (!phase.isRoundActive()) {
			lastEnd = null;
		}

		// Garde-fou 2 de `docs/06` §3.4 : un écart de révision veut dire que le catalogue n'est pas
		// celui de cette carte. Un entier ferme le trou de désynchronisation.
		if (payload.revision() != catalogRevision) {
			BingoConstants.LOGGER.info("Révision divergente (carte {} / catalogue {}) — resynchronisation",
					payload.revision(), catalogRevision);
			requestResync();
		}
	}

	public static void onPhase(PhasePayload payload) {
		boolean entersFinished = payload.phase() == GamePhase.FINISHED && phase != GamePhase.FINISHED;
		phase = payload.phase();
		elapsedMsAtSync = payload.elapsedMs();
		remainingSecondsAtSync = payload.remainingSeconds();
		phaseEndsInMsAtSync = payload.phaseEndsInMs();
		syncedAtMs = System.currentTimeMillis();

		if (entersFinished) {
			finishedAtMs = System.currentTimeMillis();
		}
		if (phase == GamePhase.LOBBY) {
			tiles = List.of();
			lastEnd = null;
		}

		// L'animation n'est PAS coupée en passant à COUNTDOWN : le serveur y transite à t=3000, soit
		// exactement l'instant du son final et des étincelles, et couper ici les avalerait dès que
		// l'estimation de décalage est légèrement en avance. Elle s'éteint d'elle-même sur sa propre
		// durée. En revanche un retour au salon ou une fin de manche l'interrompt : il n'y a plus de
		// carte à faire défiler.
		if (phase == GamePhase.LOBBY || phase == GamePhase.FINISHED) {
			RollAnimationState.stop();
			RollSparks.stop();
		}
	}

	public static void onTileUpdate(TileUpdatePayload payload) {
		// Borne avant toute chose : l'index sert de décalage de bit dans withTile, et en Java un
		// décalage est pris modulo 32. Un index hors grille ne produirait donc pas une erreur mais
		// cocherait silencieusement une autre case — 1 << 32 vaut 1, soit la case 0.
		if (payload.index() < 0 || payload.index() >= BingoBoard.TILE_COUNT) {
			BingoConstants.LOGGER.warn("tile_update à l'index hors grille ({}) — ignoré", payload.index());
			return;
		}

		TeamSnapshot team = teams.get(payload.teamId());
		if (team == null) {
			// Une case pour une équipe inconnue signale un team_sync manqué : on redemande plutôt
			// que d'ignorer, sinon le HUD resterait faux jusqu'à la fin de la manche.
			requestResync();
			return;
		}
		teams.put(payload.teamId(), withTile(team, payload));
	}

	/**
	 * Applique une mise à jour de case à un instantané d'équipe.
	 *
	 * <p>Reconstruit l'instantané plutôt que de muter ses tableaux : {@link TeamSnapshot} est un
	 * record de transport, et le rendu peut le lire depuis le thread principal pendant qu'un paquet
	 * arrive sur le thread réseau. Remplacer une référence est atomique, muter un tableau ne l'est
	 * pas.
	 */
	private static TeamSnapshot withTile(TeamSnapshot team, TileUpdatePayload payload) {
		byte[] progress = team.progress().clone();
		if (payload.index() >= 0 && payload.index() < progress.length) {
			progress[payload.index()] = (byte) Math.min(payload.progress(), Byte.MAX_VALUE);
		}
		int mask = payload.completed()
				? team.completionMask() | (1 << payload.index())
				: team.completionMask() & ~(1 << payload.index());
		return new TeamSnapshot(team.id(), team.color(), team.name(), team.members(), mask, progress);
	}

	public static void onScoreUpdate(ScoreUpdatePayload payload) {
		scores = payload.entries();
	}

	public static void onTeamSync(List<TeamSnapshot> received) {
		teams.clear();
		received.forEach(team -> teams.put(team.id(), team));
	}

	public static void onGameEnd(GameEndPayload payload) {
		lastEnd = payload;
		scores = payload.ranking();
		finishedAtMs = System.currentTimeMillis();
	}

	/** Vide l'état à la déconnexion : sans cela, le HUD du monde précédent survit au menu. */
	public static void clear() {
		catalog = Map.of();
		catalogRevision = -1;
		phase = GamePhase.LOBBY;
		tiles = List.of();
		teams.clear();
		scores = List.of();
		lastEnd = null;
		finishedAtMs = 0L;
		syncedAtMs = 0L;
		winConditions = Ruleset.WinCondition.ALL;
		RollAnimationState.stop();
		RollSparks.stop();
	}

	// ── Lecture ───────────────────────────────────────────────────────────────

	public static GamePhase phase() {
		return phase;
	}

	public static boolean hasCard() {
		return !tiles.isEmpty();
	}

	public static List<Identifier> tiles() {
		return tiles;
	}

	/**
	 * L'objectif d'une case, résolu dans le catalogue.
	 *
	 * <p>Vide si l'identifiant est inconnu : le rendu affiche alors une case placeholder
	 * ({@code minecraft:barrier} et l'ID brut en tooltip), jamais un crash — garde-fou 3 de
	 * `docs/06` §3.4.
	 */
	public static Optional<ObjectiveProjection> objectiveAt(int index) {
		if (index < 0 || index >= tiles.size()) {
			return Optional.empty();
		}
		return Optional.ofNullable(catalog.get(tiles.get(index)));
	}

	/** Un objectif du catalogue par son identifiant — l'animation de tirage y pioche ses icônes. */
	public static Optional<ObjectiveProjection> objective(Identifier id) {
		return Optional.ofNullable(catalog.get(id));
	}

	/** L'identifiant brut d'une case, même s'il est absent du catalogue. */
	public static Optional<Identifier> tileId(int index) {
		return index >= 0 && index < tiles.size() ? Optional.of(tiles.get(index)) : Optional.empty();
	}

	public static List<TeamSnapshot> teams() {
		return new ArrayList<>(teams.values());
	}

	public static Optional<TeamSnapshot> team(TeamId id) {
		return Optional.ofNullable(teams.get(id));
	}

	/** L'équipe du joueur local, vide s'il est spectateur. */
	public static Optional<TeamSnapshot> myTeam() {
		UUID self = MinecraftClient.getInstance().player == null
				? null
				: MinecraftClient.getInstance().player.getUuid();
		if (self == null) {
			return Optional.empty();
		}
		return teams.values().stream().filter(team -> team.members().contains(self)).findFirst();
	}

	public static List<ScoreUpdatePayload.Entry> scores() {
		return scores;
	}

	public static boolean revealOpponentProgress() {
		return revealOpponentProgress;
	}

	/** Base de points du ruleset, pour la ligne de points du tooltip (`docs/03` §3.3). */
	public static int pointsBase() {
		return pointsBase;
	}

	/** Formes de combinaison actives, pour la bordure dorée du 4/5 (`docs/03` §2). */
	public static List<Ruleset.WinCondition> winConditions() {
		return winConditions;
	}

	/**
	 * Les cases que le HUD doit border d'or pour l'équipe du joueur (`docs/03` §2, tâche 4.9).
	 *
	 * <p>Quand une combinaison est à <strong>une case</strong> de la victoire, ses 4 cases déjà
	 * validées prennent la bordure dorée. C'est l'information « je suis proche » donnée sans une
	 * ligne de texte — et sans la donner à l'adversaire, qui lit son propre masque, pas le nôtre.
	 *
	 * @return un masque de 25 bits, {@code 0} s'il n'y a rien à mettre en avant
	 */
	public static int highlightMask() {
		if (phase == GamePhase.FINISHED) {
			// En fin de manche, c'est la combinaison gagnante qui compte, pas les presque-gagnantes.
			GameEndPayload end = lastEnd;
			if (end == null) {
				return 0;
			}
			int mask = 0;
			for (int index : end.winningLine()) {
				mask |= 1 << index;
			}
			return mask;
		}

		Optional<TeamSnapshot> mine = myTeam();
		if (mine.isEmpty()) {
			return 0;
		}
		int completion = mine.get().completionMask();
		int mask = 0;
		for (WinLines.Line line : WinLines.oneAway(completion, winConditions)) {
			// Seules les cases déjà validées se parent d'or : border la 5ᵉ, encore vide, désignerait
			// la case à faire — une aide que `docs/03` §2 ne demande pas.
			mask |= line.mask() & completion;
		}
		return mask;
	}

	public static Optional<GameEndPayload> lastEnd() {
		return Optional.ofNullable(lastEnd);
	}

	/** Temps restant, extrapolé depuis la dernière réception. */
	public static int remainingSeconds() {
		if (!phase.isTimerTicking()) {
			return remainingSecondsAtSync;
		}
		long sinceSync = (System.currentTimeMillis() - syncedAtMs) / 1000L;
		return (int) Math.max(0L, remainingSecondsAtSync - sinceSync);
	}

	public static long elapsedMs() {
		if (!phase.isTimerTicking()) {
			return elapsedMsAtSync;
		}
		return elapsedMsAtSync + (System.currentTimeMillis() - syncedAtMs);
	}

	/** Secondes restantes de la phase courante, {@code -1} si elle n'a pas d'échéance. */
	public static int phaseSecondsLeft() {
		if (phaseEndsInMsAtSync == PhasePayload.NO_DEADLINE) {
			return -1;
		}
		long left = phaseEndsInMsAtSync - (System.currentTimeMillis() - syncedAtMs);
		return (int) Math.max(0L, (left + 999L) / 1000L);
	}

	// ── Visibilité du HUD (`docs/03` §4) ───────────────────────────────────────

	/**
	 * Bascule du keybind « Afficher/masquer le HUD » (`docs/03` §5).
	 *
	 * <p>Déléguée à la config client depuis le lot 4 : le réglage est persisté dans
	 * {@code bingo-client.json}, donc un joueur qui masque le HUD ne le retrouve pas au prochain
	 * lancement.
	 */
	public static boolean hudVisible() {
		return BingoClientConfig.hudVisible();
	}

	public static boolean toggleHud() {
		return BingoClientConfig.toggleHudVisible();
	}

	/** Durée d'affichage du HUD après la fin d'une manche (`docs/03` §4). */
	private static final long FINISHED_LINGER_MS = 10_000L;

	/**
	 * Le HUD doit-il être dessiné ?
	 *
	 * <p>Reproduit la table de `docs/03` §4, y compris le masquage dès qu'un écran est ouvert : le
	 * HUD est dessiné <em>sous</em> les écrans par Minecraft, et le laisser visible le ferait
	 * chevaucher l'inventaire. C'est aussi ce qui rend l'illusion de
	 * {@code BingoBoardScreen} propre — l'écran redessine la grille au lieu de se superposer à elle.
	 */
	public static boolean shouldRenderHud(MinecraftClient client) {
		if (!hudVisible() || !hasCard()) {
			return false;
		}
		if (client.options.hudHidden || client.currentScreen != null) {
			return false;
		}
		if (phase == GamePhase.FINISHED) {
			return System.currentTimeMillis() - finishedAtMs < FINISHED_LINGER_MS;
		}
		return true;
	}

	// ── Resynchronisation ─────────────────────────────────────────────────────

	/**
	 * Demande un {@code board_sync} au serveur (`docs/06` §3.2).
	 *
	 * <p>Étranglée : une désync provoque des paquets qui provoqueraient une désync, et sans ce
	 * délai le client pourrait demander une resynchronisation à chaque paquet reçu.
	 */
	public static void requestResync() {
		long now = System.currentTimeMillis();
		if (now - lastResyncRequestMs < RESYNC_COOLDOWN_MS) {
			return;
		}
		lastResyncRequestMs = now;
		com.bingo.mod.client.network.BingoClientNetworking.sendRequestSync();
	}
}
