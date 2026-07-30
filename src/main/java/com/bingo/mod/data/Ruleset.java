package com.bingo.mod.data;

import com.bingo.mod.board.BingoBoard;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.dynamic.Codecs;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Préréglage de partie (`docs/01` §8).
 *
 * <p>Tous les champs ont un défaut : un ruleset réduit à {@code {}} reste valide et donne le
 * comportement classique. Ça permet à un datapack de ne surcharger que ce qui l'intéresse.
 */
public record Ruleset(
		Optional<Text> displayName,
		BoardSpec board,
		List<WinCondition> winConditions,
		int pointsBase,
		int lineBonus,
		int teamSize,
		int maxTeams,
		boolean tileLock,
		boolean eliminationOnDeath,
		boolean revealOpponentProgress,
		boolean rollAnimation,
		boolean freezeDuringRoll,
		VoiceSpec voice,
		Timings timings
) {

	public static final Codec<Ruleset> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codecs.TEXT.optionalFieldOf("display_name").forGetter(Ruleset::displayName),
			BoardSpec.CODEC.optionalFieldOf("board", BoardSpec.DEFAULT).forGetter(Ruleset::board),
			WinCondition.CODEC.listOf().optionalFieldOf("win_conditions", WinCondition.ALL).forGetter(Ruleset::winConditions),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("points_base", 100).forGetter(Ruleset::pointsBase),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("line_bonus", 0).forGetter(Ruleset::lineBonus),
			Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("team_size", 2).forGetter(Ruleset::teamSize),
			Codec.intRange(2, Integer.MAX_VALUE).optionalFieldOf("max_teams", 4).forGetter(Ruleset::maxTeams),
			Codec.BOOL.optionalFieldOf("tile_lock", false).forGetter(Ruleset::tileLock),
			Codec.BOOL.optionalFieldOf("elimination_on_death", false).forGetter(Ruleset::eliminationOnDeath),
			Codec.BOOL.optionalFieldOf("reveal_opponent_progress", true).forGetter(Ruleset::revealOpponentProgress),
			Codec.BOOL.optionalFieldOf("roll_animation", true).forGetter(Ruleset::rollAnimation),
			Codec.BOOL.optionalFieldOf("freeze_during_roll", true).forGetter(Ruleset::freezeDuringRoll),
			VoiceSpec.CODEC.optionalFieldOf("voice", VoiceSpec.DEFAULT).forGetter(Ruleset::voice),
			Timings.CODEC.optionalFieldOf("timings", Timings.DEFAULT).forGetter(Ruleset::timings)
	).apply(instance, Ruleset::new));

	/** Dimensions de la grille. Voir {@link BingoBoard} : le 5×5 est de fait câblé. */
	public record BoardSpec(int width, int height, boolean sharedCard) {

		public static final BoardSpec DEFAULT = new BoardSpec(BingoBoard.SIZE, BingoBoard.SIZE, true);

		public static final Codec<BoardSpec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.intRange(1, 9).optionalFieldOf("width", BingoBoard.SIZE).forGetter(BoardSpec::width),
				Codec.intRange(1, 9).optionalFieldOf("height", BingoBoard.SIZE).forGetter(BoardSpec::height),
				Codec.BOOL.optionalFieldOf("shared_card", true).forGetter(BoardSpec::sharedCard)
		).apply(instance, BoardSpec::new));
	}

	/** Réglages vocaux (`docs/02`). Consommés au lot 3. */
	public record VoiceSpec(boolean enabled, LobbyMode lobbyMode, RoundMode roundMode) {

		public static final VoiceSpec DEFAULT = new VoiceSpec(true, LobbyMode.GLOBAL, RoundMode.TEAM_OPEN);

		public static final Codec<VoiceSpec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("enabled", true).forGetter(VoiceSpec::enabled),
				LobbyMode.CODEC.optionalFieldOf("lobby_mode", LobbyMode.GLOBAL).forGetter(VoiceSpec::lobbyMode),
				RoundMode.CODEC.optionalFieldOf("round_mode", RoundMode.TEAM_OPEN).forGetter(VoiceSpec::roundMode)
		).apply(instance, VoiceSpec::new));
	}

	/**
	 * Minutages.
	 *
	 * <p>{@code roll_ticks} est exposé mais la timeline de `docs/04` est calibrée en dur pour
	 * 60 ticks : le traiter comme une constante tant que les 5 instants de verrouillage ne sont
	 * pas recalculés proportionnellement (`docs/01` §8).
	 */
	public record Timings(int countdownSeconds, int rollTicks, Optional<Integer> timeLimitSeconds) {

		public static final Timings DEFAULT = new Timings(5, 60, Optional.of(3600));

		public static final Codec<Timings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.intRange(0, 60).optionalFieldOf("countdown_seconds", 5).forGetter(Timings::countdownSeconds),
				Codec.intRange(1, 1200).optionalFieldOf("roll_ticks", 60).forGetter(Timings::rollTicks),
				Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("time_limit_seconds").forGetter(Timings::timeLimitSeconds)
		).apply(instance, Timings::new));
	}

	/** Formes de combinaison gagnante retenues (`docs/05` §1.1). */
	public enum WinCondition {

		LINE("line"),
		COLUMN("column"),
		DIAGONAL("diagonal");

		public static final List<WinCondition> ALL = List.of(values());

		public static final Codec<WinCondition> CODEC = Codec.STRING.flatXmap(
				name -> Arrays.stream(values()).filter(value -> value.name.equals(name)).findFirst()
						.map(DataResult::success)
						.orElseGet(() -> DataResult.error(() -> "Condition de victoire inconnue : '" + name + "'")),
				condition -> DataResult.success(condition.name));

		private final String name;

		WinCondition(String name) {
			this.name = name;
		}
	}

	/** Mode vocal hors manche (`docs/02` §2). */
	public enum LobbyMode {

		GLOBAL("global"),
		PROXIMITY("proximity");

		public static final Codec<LobbyMode> CODEC = Codec.STRING.flatXmap(
				name -> Arrays.stream(values()).filter(value -> value.name.equals(name)).findFirst()
						.map(DataResult::success)
						.orElseGet(() -> DataResult.error(() -> "Mode vocal de salon inconnu : '" + name + "'")),
				mode -> DataResult.success(mode.name));

		private final String name;

		LobbyMode(String name) {
			this.name = name;
		}
	}

	/** Mode vocal en manche (`docs/02` §2). */
	public enum RoundMode {

		TEAM_OPEN("team_open"),
		TEAM_ISOLATED("team_isolated"),
		PROXIMITY("proximity");

		public static final Codec<RoundMode> CODEC = Codec.STRING.flatXmap(
				name -> Arrays.stream(values()).filter(value -> value.name.equals(name)).findFirst()
						.map(DataResult::success)
						.orElseGet(() -> DataResult.error(() -> "Mode vocal de manche inconnu : '" + name + "'")),
				mode -> DataResult.success(mode.name));

		private final String name;

		RoundMode(String name) {
			this.name = name;
		}
	}
}
