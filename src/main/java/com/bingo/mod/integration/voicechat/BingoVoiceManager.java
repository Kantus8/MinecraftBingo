package com.bingo.mod.integration.voicechat;

import com.bingo.mod.game.BingoGame;
import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.game.team.TeamId;
import com.bingo.mod.util.BingoConstants;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seul propriétaire des groupes Simple Voice Chat (`docs/02` §3.3, tâche 3.3).
 *
 * <p>Aucune autre classe du mod ne touche à l'API vocale : les points d'appel de `docs/02` §3.4
 * passent tous par {@link #apply(BingoGame)}, {@link #applyTo(BingoGame, UUID)} ou
 * {@link #onReset(BingoGame)}. C'est ce qui rend l'intégration testable — un seul endroit décide
 * dans quel groupe un joueur doit être.
 *
 * <p><strong>La logique tient dans {@link #desiredGroup}</strong> : la table de `docs/02` §2 se lit
 * là, et nulle part ailleurs. Le reste de la classe n'est que de la plomberie pour l'y amener —
 * transitions de phase, connexions vocales, réconciliation périodique. Écrire l'assignation par
 * effets de bord dispersés (« au départ de la manche, pousser tout le monde dans son groupe »)
 * marche jusqu'au premier joueur qui se déconnecte pendant le countdown.
 *
 * <p><strong>Fil d'exécution</strong> : les événements de Simple Voice Chat n'arrivent pas sur le
 * thread serveur. Tout ce qui lit l'état de partie est donc reposté via
 * {@link net.minecraft.server.MinecraftServer#execute(Runnable)}, ce qui a l'effet de bord
 * bienvenu de sérialiser aussi toutes les mutations de groupes.
 */
public final class BingoVoiceManager {

	private static final BingoVoiceManager INSTANCE = new BingoVoiceManager();

	/** Groupe global hors manche (`docs/02` §2). */
	private static final String LOBBY_GROUP_NAME = "Bingo Lobby";

	private static final String TEAM_GROUP_PREFIX = "Bingo · ";

	/**
	 * Cadence de la réconciliation en manche, en ticks serveur.
	 *
	 * <p>Une seconde : assez court pour qu'un joueur qui rejoint un groupe à la main soit ramené
	 * avant d'avoir entendu quoi que ce soit d'utile, assez long pour que le coût — un lookup de
	 * connexion par joueur — reste invisible.
	 */
	public static final int RECONCILE_INTERVAL_TICKS = 20;

	/**
	 * {@code volatile} : écrit depuis le thread vocal, lu depuis le thread serveur.
	 *
	 * <p>{@code null} tant que le serveur vocal n'a pas démarré — cas limite n°2 de `docs/02` §4,
	 * toutes les méthodes sortent alors silencieusement.
	 */
	private volatile @Nullable VoicechatServerApi api;

	/**
	 * {@code volatile} pour la même raison qu'{@link #api} : {@link #unbind()} le remet à
	 * {@code null} depuis le thread vocal, tout le reste le lit depuis le thread serveur. Pas de
	 * course sur la création : {@link #lobbyGroup} n'est construit que par le thread serveur.
	 */
	private volatile @Nullable Group lobbyGroup;

	/**
	 * Un groupe par équipe, créé à la demande et conservé jusqu'à {@code /bingo reset}.
	 *
	 * <p>Concurrente et non {@link LinkedHashMap} : {@link #unbind()} la vide depuis le thread
	 * vocal. L'ordre d'insertion ne sert à rien ici — la table n'est parcourue que pour dissoudre.
	 */
	private final Map<TeamId, Group> teamGroups = new ConcurrentHashMap<>();

	private BingoVoiceManager() {
	}

	public static BingoVoiceManager get() {
		return INSTANCE;
	}

	// ── Cycle de vie du serveur vocal ─────────────────────────────────────────

	/**
	 * Appelé sur {@code VoicechatServerStartedEvent} (`docs/02` §3.4).
	 *
	 * <p>Le groupe lobby n'est <em>pas</em> créé ici mais à la première assignation : le serveur
	 * vocal peut démarrer avant que l'état de partie soit attaché, et un groupe créé dans le vide
	 * serait à recréer de toute façon.
	 */
	public void bind(VoicechatServerApi api) {
		this.api = api;
		BingoConstants.LOGGER.info("Serveur vocal démarré — les groupes Bingo prennent la main");
		onServerThread(this::apply);
	}

	/**
	 * Appelé à l'arrêt du serveur vocal comme à celui du serveur Minecraft.
	 *
	 * <p>Aucun {@code removeGroup} : les groupes meurent avec le serveur vocal, et l'API n'est plus
	 * garantie utilisable au moment où l'on est prévenu. Seules les références sont lâchées, faute
	 * de quoi un second monde ouvert dans la même session hériterait des groupes du premier.
	 */
	public void unbind() {
		if (api == null) {
			return;
		}
		api = null;
		lobbyGroup = null;
		teamGroups.clear();
		BingoConstants.LOGGER.debug("Serveur vocal arrêté — groupes Bingo oubliés");
	}

	// ── Points d'appel (`docs/02` §3.4) ────────────────────────────────────────

	/**
	 * Réassigne tous les joueurs connectés. Appelé à chaque transition de phase, à chaque mutation
	 * d'équipe, et une fois par seconde en manche.
	 *
	 * <p>À appeler depuis le thread serveur.
	 */
	public void apply(BingoGame game) {
		VoicechatServerApi voice = api;
		if (voice == null || !game.voiceEnabled()) {
			return;
		}
		for (UUID uuid : game.connectedPlayers()) {
			moveTo(voice, uuid, desiredGroup(voice, game, uuid));
		}
	}

	/** Réassigne un joueur unique — connexion vocale, changement d'équipe. Thread serveur. */
	public void applyTo(BingoGame game, UUID player) {
		VoicechatServerApi voice = api;
		if (voice == null || !game.voiceEnabled()) {
			return;
		}
		moveTo(voice, player, desiredGroup(voice, game, player));
	}

	/**
	 * Appelé sur {@code PlayerConnectedEvent} : un joueur qui rejoint en cours de partie doit
	 * atterrir dans le bon groupe (cas limite n°5 de `docs/02` §4).
	 *
	 * <p>Le thread appelant est celui du serveur vocal, d'où le repostage.
	 */
	public void reapply(VoicechatConnection connection) {
		UUID uuid = playerUuid(connection);
		if (uuid == null) {
			return;
		}
		onServerThread(game -> applyTo(game, uuid));
	}

	/**
	 * Réconciliation périodique, une fois par seconde tant qu'une manche est engagée.
	 *
	 * <p>C'est elle qui fait tenir trois cas limites de `docs/02` §4 sans événement dédié :
	 * l'écrasement d'un groupe rejoint à la main pendant {@code RUNNING} (n°4), le passage en
	 * spectateur (n°8, dont Fabric 1.20.1 n'expose aucun événement), et toute connexion vocale
	 * qu'un {@code PlayerConnectedEvent} manqué aurait laissée dans le mauvais groupe.
	 *
	 * <p>Volontairement inactive hors manche : le lobby est un point de passage, pas une prison —
	 * un joueur qui se crée un groupe entre deux parties a le droit d'y rester.
	 */
	public void tick(BingoGame game) {
		if (api == null || !game.phase().isRoundActive()) {
			return;
		}
		if (game.server().getTicks() % RECONCILE_INTERVAL_TICKS != 0) {
			return;
		}
		apply(game);
	}

	/**
	 * {@code /bingo reset} : dissoudre les groupes d'équipe et remettre tout le monde dans le lobby
	 * (cas limite n°9 de `docs/02` §4).
	 *
	 * <p>Dissolution <em>avant</em> réassignation : l'ordre inverse laisserait un joueur dans un
	 * groupe qu'on supprime juste après, et donc sans groupe du tout.
	 */
	public void onReset(BingoGame game) {
		VoicechatServerApi voice = api;
		if (voice == null) {
			return;
		}
		dissolveTeamGroups(voice);
		apply(game);
	}

	// ── Décision ──────────────────────────────────────────────────────────────

	/**
	 * Le groupe dans lequel un joueur doit être, d'après la table de `docs/02` §2.
	 *
	 * <p>Trois façons d'atterrir dans le lobby en pleine manche : ne pas être dans une équipe, être
	 * spectateur, ou être dans une phase qui n'utilise pas les groupes d'équipe. Les trois sont des
	 * lignes de la table, pas des cas particuliers.
	 */
	private @Nullable Group desiredGroup(VoicechatServerApi voice, BingoGame game, UUID player) {
		Group lobby = lobbyGroup(voice);
		if (!game.phase().usesTeamVoiceGroups()) {
			return lobby;
		}
		Optional<BingoTeam> team = game.teams().of(player);
		if (team.isEmpty() || isSpectator(game, player)) {
			return lobby;
		}
		return teamGroup(voice, team.get());
	}

	private static boolean isSpectator(BingoGame game, UUID player) {
		ServerPlayerEntity entity = game.server().getPlayerManager().getPlayer(player);
		return entity != null && entity.interactionManager.getGameMode() == GameMode.SPECTATOR;
	}

	// ── Groupes ───────────────────────────────────────────────────────────────

	/**
	 * Le groupe lobby, créé à la première demande.
	 *
	 * <p>{@code ISOLATED} et non {@code OPEN} : le groupe contient déjà tout le monde, un canal de
	 * proximité par-dessus rejouerait le même audio deux fois (`docs/02` §2).
	 */
	private Group lobbyGroup(VoicechatServerApi voice) {
		if (lobbyGroup == null) {
			lobbyGroup = voice.groupBuilder()
					.setName(LOBBY_GROUP_NAME)
					// Persistant : il doit survivre au départ de son dernier membre, sans quoi le
					// premier joueur à revenir trouverait un groupe disparu.
					.setPersistent(true)
					.setType(Group.Type.ISOLATED)
					.build();
			BingoConstants.LOGGER.debug("Groupe vocal '{}' créé", LOBBY_GROUP_NAME);
		}
		return lobbyGroup;
	}

	/**
	 * Le groupe d'une équipe, créé à la première demande.
	 *
	 * <p>{@code OPEN} est <strong>le</strong> point de la spec (`docs/02` §1) : le binôme s'entend
	 * sans distance <em>et</em> la proximité reste active dans les deux sens avec les autres
	 * équipes. {@code NORMAL} produirait une asymétrie — on entend l'adversaire, il ne nous entend
	 * pas — que les joueurs signaleraient comme un bug.
	 *
	 * <p>Le groupe est <strong>caché</strong> : écart assumé avec `docs/02`, qui ne se prononce pas.
	 * Un groupe d'équipe listé dans l'interface de Simple Voice Chat est un groupe qu'un adversaire
	 * peut rejoindre d'un clic, ce qui annulerait tout l'intérêt de la séparation. La
	 * réconciliation le ramènerait dans la seconde, mais une seconde suffit à entendre une
	 * coordination. Le lobby, lui, reste visible : il contient déjà tout le monde.
	 */
	private Group teamGroup(VoicechatServerApi voice, BingoTeam team) {
		return teamGroups.computeIfAbsent(team.id(), id -> {
			// getString() résout la clé de traduction avec la langue du serveur : l'API vocale ne
			// transporte qu'une String, il n'y a pas de nom de groupe traduisible par client.
			Group group = voice.groupBuilder()
					.setName(TEAM_GROUP_PREFIX + team.displayName().getString())
					.setPersistent(true)
					.setHidden(true)
					.setType(Group.Type.OPEN)
					.build();
			BingoConstants.LOGGER.debug("Groupe vocal d'équipe '{}' créé", group.getName());
			return group;
		});
	}

	private void dissolveTeamGroups(VoicechatServerApi voice) {
		teamGroups.values().forEach(group -> voice.removeGroup(group.getId()));
		if (!teamGroups.isEmpty()) {
			BingoConstants.LOGGER.debug("{} groupe(s) vocal(aux) d'équipe dissous", teamGroups.size());
		}
		teamGroups.clear();
	}

	/**
	 * Déplace un joueur, sans rien faire s'il est déjà au bon endroit.
	 *
	 * <p>Le test d'égalité n'est pas une optimisation : {@code setGroup} appelé à chaque
	 * réconciliation rejouerait le son d'entrée dans le groupe une fois par seconde. La comparaison
	 * porte sur l'UUID du groupe, l'API ne garantissant pas l'{@code equals} de ses implémentations.
	 */
	private void moveTo(VoicechatServerApi voice, UUID player, @Nullable Group target) {
		// null = le joueur n'a pas le mod client, ou est déjà parti (cas limite n°3 de `docs/02` §4).
		VoicechatConnection connection = voice.getConnectionOf(player);
		if (connection == null) {
			return;
		}
		Group current = connection.getGroup();
		if (sameGroup(current, target)) {
			return;
		}
		connection.setGroup(target);
	}

	private static boolean sameGroup(@Nullable Group left, @Nullable Group right) {
		if (left == null || right == null) {
			return left == right;
		}
		return left.getId().equals(right.getId());
	}

	// ── Plomberie ─────────────────────────────────────────────────────────────

	private static @Nullable UUID playerUuid(VoicechatConnection connection) {
		return connection.getPlayer() == null ? null : connection.getPlayer().getUuid();
	}

	/**
	 * Reposte une action sur le thread serveur, en lui passant l'état de partie courant.
	 *
	 * <p>Sans effet s'il n'y a pas de serveur attaché : le serveur vocal peut survivre quelques
	 * millisecondes à l'arrêt du serveur Minecraft, et il n'y a alors plus rien à assigner.
	 */
	private void onServerThread(java.util.function.Consumer<BingoGame> action) {
		BingoGame game = BingoGame.getOrNull();
		if (game == null) {
			return;
		}
		game.server().execute(() -> {
			BingoGame current = BingoGame.getOrNull();
			if (current != null) {
				action.accept(current);
			}
		});
	}
}
