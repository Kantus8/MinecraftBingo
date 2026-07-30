package com.bingo.mod.command;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.board.BoardGenerator;
import com.bingo.mod.config.BingoServerConfig;
import com.bingo.mod.data.BingoData;
import com.bingo.mod.data.DifficultyProfile;
import com.bingo.mod.data.Ruleset;
import com.bingo.mod.data.loader.ObjectiveLoader;
import com.bingo.mod.game.BingoGame;
import com.bingo.mod.game.BingoScoring;
import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.game.team.TeamId;
import com.bingo.mod.game.team.TeamManager;
import com.bingo.mod.integration.voicechat.BingoVoiceManager;
import com.bingo.mod.network.handler.BingoServerNetworking;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.util.BingoConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Racine {@code /bingo} (`docs/05` §4).
 *
 * <p>Toutes les erreurs passent par un {@link SimpleCommandExceptionType} ou un
 * {@link DynamicCommandExceptionType} portant une clé de traduction (`docs/05` §4.4) : Brigadier
 * les affiche en rouge et <strong>n'exécute pas</strong> la commande. Un {@code sendError} suivi
 * d'un {@code return 0} aurait l'air identique pour l'opérateur tout en laissant croire au reste
 * du code que la commande a tourné.
 *
 * <p>Il n'existe qu'un paquet C2S dans ce mod (`docs/06` §3.2) : rejoindre une équipe, démarrer ou
 * mettre en pause passe par ces commandes, qui sont déjà un canal validé, permissionné et
 * journalisé. Les réimplémenter en paquets custom dupliquerait la validation de permission.
 */
public final class BingoCommand {

	/** Niveau opérateur (`docs/05` §4). */
	private static final int PERMISSION_OPERATOR = 2;

	private static final DynamicCommandExceptionType UNKNOWN_DIFFICULTY =
			new DynamicCommandExceptionType(id -> Text.translatable("bingo.command.error.unknown_difficulty", id));

	private static final DynamicCommandExceptionType UNKNOWN_TEAM =
			new DynamicCommandExceptionType(id -> Text.translatable("bingo.command.error.unknown_team", id));

	private static final DynamicCommandExceptionType INVALID_TEAM_ID =
			new DynamicCommandExceptionType(id -> Text.translatable("bingo.command.error.invalid_team_id", id));

	private static final DynamicCommandExceptionType TEAM_EXISTS =
			new DynamicCommandExceptionType(id -> Text.translatable("bingo.command.error.team_exists", id));

	private static final DynamicCommandExceptionType UNKNOWN_COLOR =
			new DynamicCommandExceptionType(name -> Text.translatable("bingo.command.error.unknown_color", name));

	private static final DynamicCommandExceptionType WRONG_PHASE =
			new DynamicCommandExceptionType(phase -> Text.translatable("bingo.command.error.wrong_phase", phase));

	private static final SimpleCommandExceptionType TEAM_FULL =
			new SimpleCommandExceptionType(Text.translatable("bingo.command.error.team_full"));

	private static final SimpleCommandExceptionType NOT_ENOUGH_TEAMS =
			new SimpleCommandExceptionType(Text.translatable("bingo.command.error.not_enough_teams"));

	private static final SimpleCommandExceptionType NO_TEAM =
			new SimpleCommandExceptionType(Text.translatable("bingo.command.error.no_team"));

	private static final SimpleCommandExceptionType LEAVE_IN_ROUND =
			new SimpleCommandExceptionType(Text.translatable("bingo.command.error.leave_in_round"));

	private static final SimpleCommandExceptionType MAX_TEAMS =
			new SimpleCommandExceptionType(Text.translatable("bingo.command.error.max_teams"));

	private static final SimpleCommandExceptionType EMPTY_BOARD =
			new SimpleCommandExceptionType(Text.translatable("bingo.command.error.empty_board"));

	private static final SimpleCommandExceptionType NO_CARD =
			new SimpleCommandExceptionType(Text.translatable("bingo.command.error.no_card"));

	private static final DynamicCommandExceptionType UNKNOWN_CONFIG_KEY =
			new DynamicCommandExceptionType(key -> Text.translatable("bingo.command.error.unknown_config_key", key));

	/** Suggestion dynamique depuis les profils chargés (`docs/05` §4.2). */
	private static final SuggestionProvider<ServerCommandSource> DIFFICULTY_SUGGESTIONS =
			(context, builder) -> CommandSource.suggestMatching(
					BingoData.DIFFICULTIES.keys().stream()
							.map(id -> BingoConstants.MOD_ID.equals(id.getNamespace()) ? id.getPath() : id.toString()),
					builder);

	private static final SuggestionProvider<ServerCommandSource> RULESET_SUGGESTIONS =
			(context, builder) -> CommandSource.suggestMatching(
					BingoData.RULESETS.keys().stream()
							.map(id -> BingoConstants.MOD_ID.equals(id.getNamespace()) ? id.getPath() : id.toString()),
					builder);

	/** Équipes existantes : suggérer autre chose reviendrait à proposer des erreurs. */
	private static final SuggestionProvider<ServerCommandSource> TEAM_SUGGESTIONS =
			(context, builder) -> CommandSource.suggestMatching(
					BingoGame.of(context.getSource().getServer()).teams().all().stream()
							.map(team -> team.id().value()),
					builder);

	private static final SuggestionProvider<ServerCommandSource> COLOR_SUGGESTIONS =
			(context, builder) -> CommandSource.suggestMatching(
					java.util.Arrays.stream(Formatting.values())
							.filter(Formatting::isColor)
							.map(Formatting::getName),
					builder);

	/** Clés de config exposées (`docs/05` §4.3), pour {@code /bingo config get|set}. */
	private static final SuggestionProvider<ServerCommandSource> CONFIG_KEY_SUGGESTIONS =
			(context, builder) -> CommandSource.suggestMatching(BingoServerConfig.keys(), builder);

	/**
	 * Valeurs plausibles de la clé déjà saisie : {@code true|false} pour un booléen, défaut et
	 * valeur courante pour un entier (`docs/05` §4.3). Aucune suggestion si la clé est inconnue —
	 * mieux vaut un champ vide qu'une liste trompeuse.
	 */
	private static final SuggestionProvider<ServerCommandSource> CONFIG_VALUE_SUGGESTIONS =
			(context, builder) -> {
				BingoServerConfig.Setting setting = BingoServerConfig.setting(
						StringArgumentType.getString(context, "key"));
				return setting == null
						? builder.buildFuture()
						: CommandSource.suggestMatching(setting.suggestions(), builder);
			};

	private BingoCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
	                           CommandRegistryAccess registryAccess,
	                           CommandManager.RegistrationEnvironment environment) {
		dispatcher.register(CommandManager.literal("bingo")
				.then(CommandManager.literal("status").executes(BingoCommand::status))
				.then(CommandManager.literal("score").executes(BingoCommand::score))
				.then(CommandManager.literal("card").executes(BingoCommand::card))
				.then(teamNode())
				.then(CommandManager.literal("start")
						.requires(operator())
						.then(CommandManager.argument("difficulty", StringArgumentType.word())
								.suggests(DIFFICULTY_SUGGESTIONS)
								.executes(context -> start(context, Optional.empty()))
								.then(CommandManager.argument("ruleset", StringArgumentType.word())
										.suggests(RULESET_SUGGESTIONS)
										.executes(context -> start(context, Optional.of(
												resolveDataId(StringArgumentType.getString(context, "ruleset"))))))))
				.then(CommandManager.literal("stop").requires(operator()).executes(BingoCommand::stop))
				.then(CommandManager.literal("pause").requires(operator()).executes(BingoCommand::pause))
				.then(CommandManager.literal("resume").requires(operator()).executes(BingoCommand::resume))
				.then(CommandManager.literal("reset").requires(operator()).executes(BingoCommand::reset))
				.then(CommandManager.literal("reroll").requires(operator()).executes(BingoCommand::reroll))
				.then(CommandManager.literal("reload").requires(operator()).executes(BingoCommand::reload))
				.then(configNode())
				.then(debugNode()));
	}

	private static java.util.function.Predicate<ServerCommandSource> operator() {
		return source -> source.hasPermissionLevel(PERMISSION_OPERATOR);
	}

	// ── /bingo team ───────────────────────────────────────────────────────────

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> teamNode() {
		return CommandManager.literal("team")
				.then(CommandManager.literal("list").executes(BingoCommand::teamList))
				.then(CommandManager.literal("join")
						.then(CommandManager.argument("team", StringArgumentType.word())
								.suggests(TEAM_SUGGESTIONS)
								.executes(BingoCommand::teamJoin)))
				.then(CommandManager.literal("leave").executes(BingoCommand::teamLeave))
				.then(CommandManager.literal("create")
						.requires(operator())
						.then(CommandManager.argument("id", StringArgumentType.word())
								.then(CommandManager.argument("color", StringArgumentType.word())
										.suggests(COLOR_SUGGESTIONS)
										.executes(BingoCommand::teamCreate))))
				.then(CommandManager.literal("remove")
						.requires(operator())
						.then(CommandManager.argument("team", StringArgumentType.word())
								.suggests(TEAM_SUGGESTIONS)
								.executes(BingoCommand::teamRemove)))
				.then(CommandManager.literal("set")
						.requires(operator())
						.then(CommandManager.argument("players", EntityArgumentType.players())
								.then(CommandManager.argument("team", StringArgumentType.word())
										.suggests(TEAM_SUGGESTIONS)
										.executes(BingoCommand::teamSet))))
				.then(CommandManager.literal("clear").requires(operator()).executes(BingoCommand::teamClear))
				.then(CommandManager.literal("autobalance")
						.requires(operator())
						.executes(BingoCommand::teamAutobalance));
	}

	private static int teamList(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		Collection<BingoTeam> teams = game.teams().all();

		if (teams.isEmpty()) {
			source.sendFeedback(() -> Text.translatable("bingo.command.team.list.empty")
					.formatted(Formatting.GRAY), false);
			return 0;
		}

		source.sendFeedback(() -> Text.translatable("bingo.command.team.list.header",
				teams.size()).formatted(Formatting.GOLD), false);

		for (BingoTeam team : teams) {
			String members = team.members().stream()
					.map(uuid -> playerName(source.getServer(), uuid))
					.collect(Collectors.joining(", "));
			source.sendFeedback(() -> Text.translatable("bingo.command.team.list.entry",
					team.coloredName(),
					team.size() + "/" + game.teamSize(),
					members.isEmpty() ? "—" : members), false);
		}
		return teams.size();
	}

	private static int teamJoin(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		BingoGame game = BingoGame.of(source.getServer());
		TeamId teamId = requireExistingTeam(game, StringArgumentType.getString(context, "team"));

		// « Changement d'équipe en manche : interdit » (`docs/05` §3). Le test porte sur la manche
		// entière et pas seulement sur RUNNING : basculer pendant le countdown reviendrait au même.
		if (game.phase().isRoundActive() && game.teams().of(player.getUuid()).isPresent()) {
			throw LEAVE_IN_ROUND.create();
		}

		TeamManager.JoinResult result = game.teams().join(player.getUuid(), teamId, game.teamSize());
		if (result == TeamManager.JoinResult.TEAM_FULL) {
			throw TEAM_FULL.create();
		}
		if (result == TeamManager.JoinResult.UNKNOWN_TEAM) {
			throw UNKNOWN_TEAM.create(teamId.value());
		}

		BingoTeam team = game.teams().get(teamId).orElseThrow();
		afterTeamChange(game);
		source.sendFeedback(() -> Text.translatable("bingo.command.team.joined", team.coloredName()), true);
		return 1;
	}

	private static int teamLeave(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		BingoGame game = BingoGame.of(source.getServer());

		if (game.phase().isRoundActive()) {
			throw LEAVE_IN_ROUND.create();
		}

		BingoTeam left = game.teams().leave(player.getUuid()).orElseThrow(NO_TEAM::create);
		afterTeamChange(game);
		source.sendFeedback(() -> Text.translatable("bingo.command.team.left", left.coloredName()), true);
		return 1;
	}

	private static int teamCreate(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());

		String raw = StringArgumentType.getString(context, "id");
		TeamId teamId = TeamId.parse(raw);
		if (teamId == null) {
			throw INVALID_TEAM_ID.create(raw);
		}
		if (game.teams().exists(teamId)) {
			throw TEAM_EXISTS.create(teamId.value());
		}
		if (game.teams().count() >= game.maxTeams()) {
			throw MAX_TEAMS.create();
		}

		String colorName = StringArgumentType.getString(context, "color");
		Formatting color = Formatting.byName(colorName);
		if (color == null || !color.isColor()) {
			throw UNKNOWN_COLOR.create(colorName);
		}

		BingoTeam team = game.teams().create(teamId, color).orElseThrow();
		team.rebuildIndex(game.tiles());
		afterTeamChange(game);
		source.sendFeedback(() -> Text.translatable("bingo.command.team.created", team.coloredName()), true);
		return 1;
	}

	private static int teamRemove(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		TeamId teamId = requireExistingTeam(game, StringArgumentType.getString(context, "team"));

		game.teams().remove(teamId);
		afterTeamChange(game);
		source.sendFeedback(() -> Text.translatable("bingo.command.team.removed", teamId.value()), true);
		return 1;
	}

	private static int teamSet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		TeamId teamId = requireExistingTeam(game, StringArgumentType.getString(context, "team"));
		Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(context, "players");

		// Integer.MAX_VALUE : l'affectation d'autorité ignore team_size. La limite protège le
		// joueur qui rejoint de lui-même, pas l'arbitre qui compose les équipes.
		players.forEach(player -> game.teams().join(player.getUuid(), teamId, Integer.MAX_VALUE));

		BingoTeam team = game.teams().get(teamId).orElseThrow();
		afterTeamChange(game);
		source.sendFeedback(() -> Text.translatable("bingo.command.team.set",
				players.size(), team.coloredName()), true);
		return players.size();
	}

	private static int teamClear(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		game.teams().clearMembers();
		afterTeamChange(game);
		source.sendFeedback(() -> Text.translatable("bingo.command.team.cleared"), true);
		return 1;
	}

	private static int teamAutobalance(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());

		List<UUID> unassigned = game.teams().unassigned(game.connectedPlayers());
		int assigned = game.teams().autobalance(unassigned, game.teamSize(), game.maxTeams());
		game.teams().rebuildIndexes(game.tiles());
		afterTeamChange(game);

		source.sendFeedback(() -> Text.translatable("bingo.command.team.autobalance",
				assigned, unassigned.size() - assigned), true);
		return assigned;
	}

	/**
	 * Les trois effets de bord de toute mutation d'équipe : persistance, {@code team_sync}, vocal.
	 *
	 * <p>Le vocal en fait partie parce qu'une équipe est aussi un groupe vocal (`docs/02` §3.4). En
	 * manche, seul un opérateur peut recomposer les équipes ({@code /bingo team set|remove|clear})
	 * et l'ancien groupe doit être quitté dans le même tick que le nouveau est rejoint — c'est
	 * exactement ce que fait une réassignation complète.
	 */
	private static void afterTeamChange(BingoGame game) {
		game.markDirty();
		BingoServerNetworking.broadcastTeamSync(game);
		BingoVoiceManager.get().apply(game);
	}

	// ── Cycle de partie ───────────────────────────────────────────────────────

	private static int start(CommandContext<ServerCommandSource> context, Optional<Identifier> ruleset)
			throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		Identifier difficulty = resolveDataId(StringArgumentType.getString(context, "difficulty"));

		BingoGame.StartReport report = game.start(difficulty, ruleset);
		switch (report.result()) {
			case WRONG_PHASE -> throw WRONG_PHASE.create(game.phase().name());
			case NOT_ENOUGH_TEAMS -> throw NOT_ENOUGH_TEAMS.create();
			case UNKNOWN_DIFFICULTY -> throw UNKNOWN_DIFFICULTY.create(difficulty.toString());
			case EMPTY_BOARD -> throw EMPTY_BOARD.create();
			case STARTED -> {
				source.sendFeedback(() -> Text.translatable("bingo.command.start.success",
						difficultyName(difficulty)).formatted(Formatting.GREEN), true);
				reportWarnings(source, report.warnings());
			}
		}
		return 1;
	}

	private static int reroll(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());

		BingoGame.StartReport report = game.reroll();
		switch (report.result()) {
			case WRONG_PHASE -> throw WRONG_PHASE.create(game.phase().name());
			case UNKNOWN_DIFFICULTY -> throw NO_CARD.create();
			case EMPTY_BOARD -> throw EMPTY_BOARD.create();
			case NOT_ENOUGH_TEAMS -> throw NOT_ENOUGH_TEAMS.create();
			case STARTED -> {
				source.sendFeedback(() -> Text.translatable("bingo.command.reroll.success")
						.formatted(Formatting.GREEN), true);
				reportWarnings(source, report.warnings());
			}
		}
		return 1;
	}

	private static void reportWarnings(ServerCommandSource source, List<String> warnings) {
		warnings.forEach(warning ->
				source.sendFeedback(() -> Text.translatable("bingo.command.start.warning", warning)
						.formatted(Formatting.YELLOW), false));
	}

	private static int stop(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		if (!game.stop()) {
			throw WRONG_PHASE.create(game.phase().name());
		}
		source.sendFeedback(() -> Text.translatable("bingo.command.stop.success"), true);
		return 1;
	}

	private static int pause(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		if (!game.pause()) {
			throw WRONG_PHASE.create(game.phase().name());
		}
		source.sendFeedback(() -> Text.translatable("bingo.command.pause.success"), true);
		return 1;
	}

	private static int resume(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		if (!game.resume()) {
			throw WRONG_PHASE.create(game.phase().name());
		}
		source.sendFeedback(() -> Text.translatable("bingo.command.resume.success"), true);
		return 1;
	}

	/** {@code /bingo reset} — joignable depuis toutes les phases, c'est le filet de sécurité. */
	private static int reset(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		BingoGame.of(source.getServer()).reset();
		source.sendFeedback(() -> Text.translatable("bingo.command.reset.success"), true);
		return 1;
	}

	// ── Lecture ───────────────────────────────────────────────────────────────

	/** {@code /bingo status} [0] — phase, chrono, scores (`docs/05` §4.1). */
	private static int status(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());

		source.sendFeedback(() -> Text.translatable("bingo.command.status.header")
				.formatted(Formatting.GOLD), false);
		source.sendFeedback(() -> Text.translatable("bingo.command.status.phase",
				game.phase().displayName().formatted(Formatting.AQUA)), false);

		game.difficultyId().ifPresent(id -> source.sendFeedback(
				() -> Text.translatable("bingo.command.status.difficulty", difficultyName(id)), false));

		if (game.hasCard()) {
			source.sendFeedback(() -> Text.translatable("bingo.command.status.time",
					formatDuration(game.elapsedMs() / 1000L),
					formatDuration(game.remainingSeconds())), false);
		}

		source.sendFeedback(() -> Text.translatable("bingo.command.status.teams",
				game.teams().countStaffed(), game.teams().count()), false);
		return 1;
	}

	/** {@code /bingo score} [0] — détail par équipe (`docs/05` §4.1). */
	private static int score(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());

		if (!game.hasCard()) {
			throw NO_CARD.create();
		}

		source.sendFeedback(() -> Text.translatable("bingo.command.score.header")
				.formatted(Formatting.GOLD), false);

		int position = 1;
		for (BingoScoring.Standing standing : game.ranking()) {
			int rank = position++;
			source.sendFeedback(() -> Text.translatable("bingo.command.score.entry",
					rank,
					standing.team().coloredName(),
					standing.score(),
					standing.tileCount(),
					standing.bestLineProgress()), false);
		}
		return 1;
	}

	/**
	 * {@code /bingo card} [0] — ouvre l'écran cliquable (`docs/05` §4.2).
	 *
	 * <p>Doublon volontaire du keybind {@code B}, pour les joueurs qui ne connaissent pas le bind.
	 */
	private static int card(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoServerNetworking.sendOpenBoard(source.getPlayerOrThrow());
		return 1;
	}

	// ── /bingo reload ─────────────────────────────────────────────────────────

	/**
	 * {@code /bingo reload} [2] — recharge les datapacks (`docs/05` §4.1).
	 *
	 * <p>Passe par {@code reloadResources}, comme le {@code /reload} vanilla : c'est ce qui
	 * réexécute tous les reload listeners, dont {@link ObjectiveLoader}. La rediffusion du
	 * catalogue et de la carte est branchée sur {@code END_DATA_PACK_RELOAD} dans l'entrypoint,
	 * donc elle couvre aussi le {@code /reload} vanilla.
	 */
	private static int reload(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		MinecraftServer server = source.getServer();

		source.sendFeedback(() -> Text.translatable("bingo.command.reload.started"), true);

		server.reloadResources(server.getDataPackManager().getEnabledNames())
				// Le rechargement est asynchrone : le retour doit repasser sur le thread
				// serveur avant de toucher au ServerCommandSource.
				.thenRunAsync(() -> source.sendFeedback(() -> Text.translatable(
						"bingo.command.reload.success",
						ObjectiveLoader.INSTANCE.size(),
						BingoData.POOLS.size(),
						BingoData.DIFFICULTIES.size(),
						BingoData.RULESETS.size(),
						BingoData.revision()), true), server)
				.exceptionally(throwable -> {
					BingoConstants.LOGGER.error("Échec du rechargement des datapacks", throwable);
					source.sendError(Text.translatable("bingo.command.reload.failed"));
					return null;
				});

		return 1;
	}

	// ── /bingo config ─────────────────────────────────────────────────────────

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> configNode() {
		return CommandManager.literal("config")
				.requires(operator())
				.then(CommandManager.literal("list").executes(BingoCommand::configList))
				.then(CommandManager.literal("get")
						.then(CommandManager.argument("key", StringArgumentType.word())
								.suggests(CONFIG_KEY_SUGGESTIONS)
								.executes(BingoCommand::configGet)))
				.then(CommandManager.literal("set")
						.then(CommandManager.argument("key", StringArgumentType.word())
								.suggests(CONFIG_KEY_SUGGESTIONS)
								.then(CommandManager.argument("value", StringArgumentType.greedyString())
										.suggests(CONFIG_VALUE_SUGGESTIONS)
										.executes(BingoCommand::configSet))));
	}

	/**
	 * {@code /bingo config list} [2] — toutes les clés, valeur courante et défaut (`docs/05` §4.1).
	 *
	 * <p>L'en-tête rappelle que ces valeurs sont un repli : le profil de difficulté puis le ruleset
	 * les précèdent (`docs/01` §8). Sans ce rappel, un opérateur qui pose {@code points_base 200} et
	 * voit les scores inchangés conclurait à un bug alors que le ruleset {@code classic} fixe déjà 100.
	 */
	private static int configList(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();

		source.sendFeedback(() -> Text.translatable("bingo.command.config.header")
				.formatted(Formatting.GOLD), false);
		source.sendFeedback(() -> Text.translatable("bingo.command.config.note")
				.formatted(Formatting.GRAY), false);

		for (BingoServerConfig.Setting setting : BingoServerConfig.settings()) {
			source.sendFeedback(() -> configLine(setting), false);
		}
		return BingoServerConfig.settings().size();
	}

	/** {@code /bingo config get <key>} [2]. */
	private static int configGet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoServerConfig.Setting setting = requireConfigKey(StringArgumentType.getString(context, "key"));
		source.sendFeedback(() -> configLine(setting), false);
		return 1;
	}

	/**
	 * {@code /bingo config set <key> <value>} [2].
	 *
	 * <p>La valeur est validée par {@link BingoServerConfig.Setting#parseAndSet} — mêmes bornes que
	 * les codecs du ruleset. Un refus lève une erreur Brigadier, jamais un repli silencieux. La
	 * persistance n'a lieu qu'en cas de succès.
	 *
	 * <p>Le changement s'applique à la <strong>prochaine</strong> manche : les valeurs déjà résolues
	 * d'une partie en cours ne sont pas relues, ce que le message de retour rappelle quand une manche
	 * tourne.
	 */
	private static int configSet(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		String key = StringArgumentType.getString(context, "key");
		BingoServerConfig.Setting setting = requireConfigKey(key);
		String rawValue = StringArgumentType.getString(context, "value");

		if (!setting.parseAndSet(rawValue)) {
			throw new SimpleCommandExceptionType(Text.translatable(
					"bingo.command.error.invalid_config_value", rawValue, setting.domain())).create();
		}
		BingoServerConfig.save();

		source.sendFeedback(() -> Text.translatable("bingo.command.config.set",
				Text.literal(key).formatted(Formatting.AQUA),
				Text.literal(setting.value()).formatted(Formatting.GREEN)), true);

		if (BingoGame.of(source.getServer()).phase().isRoundActive()) {
			source.sendFeedback(() -> Text.translatable("bingo.command.config.set.next_round")
					.formatted(Formatting.YELLOW), false);
		}
		return 1;
	}

	/** Une ligne {@code clé = valeur (défaut …)}, la valeur en vert si elle diffère du défaut. */
	private static Text configLine(BingoServerConfig.Setting setting) {
		Text value = Text.literal(setting.value())
				.formatted(setting.isDefault() ? Formatting.GRAY : Formatting.GREEN);
		MutableText line = Text.translatable("bingo.command.config.entry",
				Text.literal(setting.name()).formatted(Formatting.AQUA),
				value,
				setting.defaultValue());
		// La clé tile_lock est exposée pour la complétude du schéma mais inerte (`docs/01` §9) : le
		// dire sur la ligne évite de faire croire à une fonctionnalité activable.
		if (setting.inert()) {
			line.append(Text.literal(" ").append(Text.translatable("bingo.command.config.inert"))
					.formatted(Formatting.DARK_GRAY));
		}
		return line;
	}

	private static BingoServerConfig.Setting requireConfigKey(String key) throws CommandSyntaxException {
		BingoServerConfig.Setting setting = BingoServerConfig.setting(key);
		if (setting == null) {
			throw UNKNOWN_CONFIG_KEY.create(key);
		}
		return setting;
	}

	// ── /bingo debug ──────────────────────────────────────────────────────────

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> debugNode() {
		return CommandManager.literal("debug")
				.requires(operator())
				.then(CommandManager.literal("objectives").executes(BingoCommand::debugObjectives))
				.then(CommandManager.literal("dump")
						.then(CommandManager.argument("difficulty", StringArgumentType.word())
								.suggests(DIFFICULTY_SUGGESTIONS)
								.executes(context -> debugDump(context, System.nanoTime()))
								.then(CommandManager.argument("seed", LongArgumentType.longArg())
										.executes(context -> debugDump(context,
												LongArgumentType.getLong(context, "seed"))))))
				.then(CommandManager.literal("complete")
						.then(CommandManager.argument("team", StringArgumentType.word())
								.suggests(TEAM_SUGGESTIONS)
								.then(CommandManager.argument("index",
												IntegerArgumentType.integer(0, BingoBoard.TILE_COUNT - 1))
										.executes(context -> debugTile(context, true)))))
				.then(CommandManager.literal("uncomplete")
						.then(CommandManager.argument("team", StringArgumentType.word())
								.suggests(TEAM_SUGGESTIONS)
								.then(CommandManager.argument("index",
												IntegerArgumentType.integer(0, BingoBoard.TILE_COUNT - 1))
										.executes(context -> debugTile(context, false)))))
				.then(CommandManager.literal("solo")
						.executes(context -> debugSolo(context, Optional.empty()))
						.then(CommandManager.argument("difficulty", StringArgumentType.word())
								.suggests(DIFFICULTY_SUGGESTIONS)
								.executes(context -> debugSolo(context,
										Optional.of(StringArgumentType.getString(context, "difficulty"))))))
				.then(CommandManager.literal("state").executes(BingoCommand::debugState));
	}

	/** Profil utilisé par {@code /bingo debug solo} sans argument. */
	private static final String SOLO_DEFAULT_DIFFICULTY = "normal";

	private static final TeamId SOLO_TEAM = new TeamId("red");
	private static final TeamId SOLO_OPPONENT = new TeamId("blue");

	/**
	 * {@code /bingo debug solo [difficulty]} [2] — monte une manche jouable à un seul joueur.
	 *
	 * <p>La recette du lot 2 demande 4 joueurs et 2 équipes, ce qui rend la boucle de jeu
	 * intestable pour un développeur seul : {@code /bingo start} refuse de démarrer sous deux
	 * équipes pourvues (`docs/05` §4.2). Cette commande fait en un geste ce qu'il faudrait faire en
	 * cinq — remise à zéro, création des deux équipes, adhésion, démarrage — et lève cette seule
	 * précondition.
	 *
	 * <p>L'équipe adverse est créée <strong>vide</strong> et non peuplée d'un membre fantôme : un
	 * UUID inexistant dans {@code members} ressortirait dans {@code /bingo team list}, dans
	 * {@code team_sync} et dans la persistance. Vide, elle reste une équipe de plein droit — elle
	 * a son masque, son score, sa place au pied du HUD — et
	 * {@code /bingo debug complete blue <index>} suffit à simuler sa progression, pastilles
	 * adverses comprises.
	 *
	 * <p>Rejouable telle quelle, y compris en pleine manche : elle commence par un
	 * {@link BingoGame#reset()}.
	 */
	private static int debugSolo(CommandContext<ServerCommandSource> context, Optional<String> difficultyArgument)
			throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayerOrThrow();
		BingoGame game = BingoGame.of(source.getServer());
		Identifier difficulty = resolveDataId(difficultyArgument.orElse(SOLO_DEFAULT_DIFFICULTY));

		game.reset();
		game.teams().create(SOLO_TEAM, Formatting.RED);
		game.teams().create(SOLO_OPPONENT, Formatting.BLUE);
		game.teams().join(player.getUuid(), SOLO_TEAM, game.teamSize());
		afterTeamChange(game);

		BingoGame.StartReport report = game.start(difficulty, Optional.empty(), true);
		switch (report.result()) {
			case WRONG_PHASE -> throw WRONG_PHASE.create(game.phase().name());
			case UNKNOWN_DIFFICULTY -> throw UNKNOWN_DIFFICULTY.create(difficulty.toString());
			case EMPTY_BOARD -> throw EMPTY_BOARD.create();
			case NOT_ENOUGH_TEAMS -> throw NOT_ENOUGH_TEAMS.create();
			case STARTED -> {
				BingoTeam mine = game.teams().get(SOLO_TEAM).orElseThrow();
				source.sendFeedback(() -> Text.translatable("bingo.command.debug.solo.success",
						difficultyName(difficulty), mine.coloredName()).formatted(Formatting.GREEN), true);
				source.sendFeedback(() -> Text.translatable("bingo.command.debug.solo.hint",
						SOLO_OPPONENT.value(), BingoBoard.TILE_COUNT - 1).formatted(Formatting.GRAY), false);
				reportWarnings(source, report.warnings());
			}
		}
		return 1;
	}

	/** {@code /bingo debug complete|uncomplete <team> <index>} (`docs/05` §4.1). */
	private static int debugTile(CommandContext<ServerCommandSource> context, boolean complete)
			throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());
		TeamId teamId = requireExistingTeam(game, StringArgumentType.getString(context, "team"));
		int index = IntegerArgumentType.getInteger(context, "index");

		if (!game.hasCard()) {
			throw NO_CARD.create();
		}
		BingoTeam team = game.teams().get(teamId).orElseThrow();

		if (complete) {
			// Passe par applyProgress et non par team.complete : c'est le seul chemin qui déclenche
			// la détection de victoire, les paquets et les annonces (`docs/06` §6).
			game.applyProgress(team, index, game.tile(index).map(Objective::count).orElse(1));
			source.sendFeedback(() -> Text.translatable("bingo.command.debug.tile.completed",
					index, team.coloredName()), true);
		} else {
			team.uncomplete(index);
			team.rebuildIndex(game.tiles());
			game.markDirty();
			BingoServerNetworking.broadcastBoardSync(game);
			BingoServerNetworking.broadcastScoreUpdate(game);
			source.sendFeedback(() -> Text.translatable("bingo.command.debug.tile.uncompleted",
					index, team.coloredName()), true);
		}
		return 1;
	}

	/** {@code /bingo debug state} — dump de l'état de partie dans les logs (`docs/05` §4.1). */
	private static int debugState(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		BingoGame game = BingoGame.of(source.getServer());

		BingoConstants.LOGGER.info("── État de partie ──");
		BingoConstants.LOGGER.info("phase={} difficulté={} ruleset={} graine={}",
				game.phase(), game.difficultyId().orElse(null), game.rulesetId().orElse(null), game.rollSeed());
		BingoConstants.LOGGER.info("chrono : {} ms écoulés, {} s restantes sur {} s",
				game.elapsedMs(), game.remainingSeconds(), game.timeLimitSeconds());

		for (int index = 0; index < game.tiles().size(); index++) {
			Objective objective = game.tiles().get(index);
			BingoConstants.LOGGER.info("  [{}] N{} {} ({}) count={}",
					index, objective.level(), objective.id(), objective.type(), objective.count());
		}

		for (BingoTeam team : game.teams().all()) {
			BingoConstants.LOGGER.info("  équipe {} ({}) : masque={} cases={} score={} membres={}",
					team.id(), team.color().getName(), Integer.toBinaryString(team.completionMask()),
					team.tileCount(), BingoScoring.teamScore(team, game.tiles(), game.pointsBase()),
					team.members().size());
		}

		source.sendFeedback(() -> Text.translatable("bingo.command.debug.state.logged")
				.formatted(Formatting.GRAY), false);
		return 1;
	}

	/** {@code /bingo debug objectives} [2] — état du registre chargé. */
	private static int debugObjectives(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();
		ObjectiveLoader loader = ObjectiveLoader.INSTANCE;

		source.sendFeedback(() -> Text.translatable("bingo.command.debug.objectives.header",
				loader.revision()).formatted(Formatting.GOLD), false);

		if (loader.size() == 0) {
			source.sendFeedback(() -> Text.translatable("bingo.command.debug.objectives.empty")
					.formatted(Formatting.RED), false);
			return 0;
		}

		source.sendFeedback(() -> Text.translatable("bingo.command.debug.objectives.total",
				Text.literal(String.valueOf(loader.size())).formatted(Formatting.AQUA)), false);

		String levels = IntStream.rangeClosed(Objective.MIN_LEVEL, Objective.MAX_LEVEL)
				.mapToObj(level -> "N" + level + " : " + loader.countByLevel(level))
				.collect(Collectors.joining("  ·  "));
		source.sendFeedback(() -> Text.translatable("bingo.command.debug.objectives.levels",
				Text.literal(levels).formatted(Formatting.AQUA)), false);

		String types = loader.countByType().entrySet().stream()
				.map(entry -> entry.getKey().id().getPath() + " : " + entry.getValue())
				.collect(Collectors.joining("  ·  "));
		source.sendFeedback(() -> Text.translatable("bingo.command.debug.objectives.types",
				Text.literal(types).formatted(Formatting.AQUA)), false);

		// Le détail complet part dans les logs : 45 lignes en chat noieraient la console de jeu.
		BingoConstants.LOGGER.info("Objectifs chargés (révision {}) : {}",
				loader.revision(), loader.sortedIds());
		source.sendFeedback(() -> Text.translatable("bingo.command.debug.objectives.logged")
				.formatted(Formatting.GRAY), false);

		return loader.size();
	}

	/**
	 * {@code /bingo debug dump <difficulty> [seed]} [2] — tire une carte d'essai.
	 *
	 * <p>Ne touche à aucun état de partie : c'est un banc d'essai du {@link BoardGenerator}, ce qui
	 * permet de vérifier la distribution sur les 4 profils sans lancer de manche. La graine est
	 * exposée pour pouvoir reproduire un tirage à l'identique.
	 */
	private static int debugDump(CommandContext<ServerCommandSource> context, long seed) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		Identifier id = resolveDataId(StringArgumentType.getString(context, "difficulty"));

		DifficultyProfile profile = BingoData.DIFFICULTIES.get(id)
				.orElseThrow(() -> UNKNOWN_DIFFICULTY.create(id));
		Optional<Ruleset> ruleset = BingoData.rulesetFor(profile);

		BoardGenerator.BoardDraw draw = BoardGenerator.generate(profile, ruleset, seed);

		source.sendFeedback(() -> Text.translatable("bingo.command.debug.dump.header",
				id.toString(), String.valueOf(seed)).formatted(Formatting.GOLD), false);
		source.sendFeedback(() -> Text.translatable("bingo.command.debug.dump.requested",
				formatDistribution(profile.distribution())), false);
		source.sendFeedback(() -> Text.translatable("bingo.command.debug.dump.obtained",
				formatDistribution(draw.actualDistribution())).formatted(
						draw.actualDistribution().equals(normalize(profile.distribution()))
								? Formatting.GREEN : Formatting.YELLOW), false);

		// La grille en chat ne montre que les niveaux : 25 noms d'objectifs y seraient illisibles.
		// Le détail complet part dans le log, où il est consultable ligne par ligne.
		for (int row = 0; row < BingoBoard.SIZE; row++) {
			StringBuilder line = new StringBuilder();
			for (int col = 0; col < BingoBoard.SIZE; col++) {
				int index = BingoBoard.index(row, col);
				line.append(index < draw.tiles().size() ? draw.tiles().get(index).level() : "·").append(' ');
			}
			String rendered = line.toString();
			source.sendFeedback(() -> Text.literal("  " + rendered).formatted(Formatting.AQUA), false);
		}

		draw.warnings().forEach(warning ->
				source.sendFeedback(() -> Text.literal("⚠ " + warning).formatted(Formatting.YELLOW), false));

		BingoConstants.LOGGER.info("Tirage d'essai '{}' (graine {}) — distribution {} :", id, seed,
				draw.actualDistribution());
		for (int index = 0; index < draw.tiles().size(); index++) {
			Objective objective = draw.tiles().get(index);
			BingoConstants.LOGGER.info("  [{},{}] N{} {} ({})",
					BingoBoard.row(index), BingoBoard.col(index), objective.level(),
					objective.id(), objective.type());
		}

		source.sendFeedback(() -> Text.translatable("bingo.command.debug.dump.logged")
				.formatted(Formatting.GRAY), false);

		return draw.isComplete() ? 1 : 0;
	}

	// ── Utilitaires ───────────────────────────────────────────────────────────

	private static TeamId requireExistingTeam(BingoGame game, String raw) throws CommandSyntaxException {
		TeamId teamId = TeamId.parse(raw);
		if (teamId == null) {
			throw INVALID_TEAM_ID.create(raw);
		}
		if (!game.teams().exists(teamId)) {
			throw UNKNOWN_TEAM.create(teamId.value());
		}
		return teamId;
	}

	private static String playerName(MinecraftServer server, UUID uuid) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
		// Hors ligne : l'UUID abrégé vaut mieux qu'un trou dans la liste, un joueur déconnecté
		// restant membre de son équipe.
		return player != null ? player.getGameProfile().getName() : uuid.toString().substring(0, 8);
	}

	private static Text difficultyName(Identifier id) {
		return BingoData.DIFFICULTIES.get(id)
				.flatMap(DifficultyProfile::displayName)
				.orElseGet(() -> Text.literal(id.toString()));
	}

	/** {@code mm:ss}, ou {@code h:mm:ss} au-delà de l'heure. */
	private static String formatDuration(long seconds) {
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long remaining = seconds % 60;
		return hours > 0
				? String.format("%d:%02d:%02d", hours, minutes, remaining)
				: String.format("%d:%02d", minutes, remaining);
	}

	/** Accepte {@code easy} comme {@code bingo:easy}, et un ID complet pour un datapack tiers. */
	private static Identifier resolveDataId(String raw) throws CommandSyntaxException {
		Identifier id = raw.indexOf(':') >= 0 ? Identifier.tryParse(raw) : BingoConstants.id(raw);
		if (id == null) {
			throw UNKNOWN_DIFFICULTY.create(raw);
		}
		return id;
	}

	private static Map<Integer, Integer> normalize(Map<Integer, Integer> distribution) {
		Map<Integer, Integer> normalized = new LinkedHashMap<>();
		for (int level = Objective.MIN_LEVEL; level <= Objective.MAX_LEVEL; level++) {
			normalized.put(level, distribution.getOrDefault(level, 0));
		}
		return normalized;
	}

	private static String formatDistribution(Map<Integer, Integer> distribution) {
		Map<Integer, Integer> normalized = normalize(distribution);
		return normalized.entrySet().stream()
				.map(entry -> "N" + entry.getKey() + " : " + entry.getValue())
				.collect(Collectors.joining("  ·  "));
	}
}
