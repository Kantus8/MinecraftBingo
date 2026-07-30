package com.bingo.mod.game;

import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.game.team.TeamId;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.registry.BingoSounds;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Annonces en chat, titres et sons de partie (`docs/05` §5).
 *
 * <p>Séparé de {@link BingoGame} pour une raison de fond : la logique de partie décide
 * <em>quoi</em> s'est produit, cette classe décide <em>qui l'apprend</em>. Le second point est
 * une règle de game design, pas une règle de jeu — et c'est là qu'on peut se tromper sans casser
 * la partie.
 *
 * <p>La décision la plus importante du fichier : le « 4/5 » n'est <strong>jamais</strong> annoncé
 * publiquement (`docs/05` §5). C'est l'information la plus précieuse de la partie, et la donner
 * gratuitement retirerait tout l'intérêt de lire le HUD adverse.
 */
public final class BingoAnnouncer {

	private BingoAnnouncer() {
	}

	// ── Cycle de la manche ────────────────────────────────────────────────────

	/** Départ : titre plein écran et son pour tout le monde. */
	static void gameStarted(BingoGame game) {
		Text title = Text.translatable(BingoConstants.key("message.game_start")).formatted(Formatting.GOLD);
		forEachPlayer(game, player -> {
			player.networkHandler.sendPacket(new TitleS2CPacket(title));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.empty()));
			play(player, BingoSounds.GAME_START, 1.0f);
		});
		broadcast(game, title);
	}

	/**
	 * Décompte avant le départ.
	 *
	 * <p>Barre d'action et non chat : cinq lignes de chat en cinq secondes noieraient tout ce qui
	 * précède, alors que la barre d'action est faite pour l'éphémère.
	 */
	static void countdown(BingoGame game, int secondsLeft) {
		Text message = Text.translatable(BingoConstants.key("phase.countdown"), secondsLeft)
				.formatted(Formatting.YELLOW);
		forEachPlayer(game, player -> {
			player.sendMessage(message, true);
			// Le pitch monte avec le décompte : l'oreille suit la progression sans lire.
			play(player, BingoSounds.COUNTDOWN_TICK, 1.0f, secondsLeft <= 1 ? 1.5f : 1.0f);
		});
	}

	/** Dernière minute (`docs/05` §5). */
	static void lastMinute(BingoGame game) {
		broadcast(game, Text.translatable(BingoConstants.key("message.time_running_out"))
				.formatted(Formatting.RED));
	}

	/** Les 10 dernières secondes : bip seul, sans chat. */
	static void finalCountdown(BingoGame game, int secondsLeft) {
		forEachPlayer(game, player -> {
			player.sendMessage(Text.translatable(BingoConstants.key("phase.countdown"), secondsLeft)
					.formatted(Formatting.RED), true);
			play(player, BingoSounds.COUNTDOWN_TICK, 1.0f);
		});
	}

	// ── Validations ───────────────────────────────────────────────────────────

	/**
	 * Une case validée (`docs/05` §5).
	 *
	 * <p>Deux portées distinctes : l'équipe concernée reçoit le message <em>et</em> le son, les
	 * autres le message seul, et seulement si {@code reveal_opponent_progress} est actif. Le son
	 * est ce qui fait la différence entre « nous avons marqué » et « quelqu'un a marqué ».
	 */
	static void objectiveCompleted(BingoGame game, BingoTeam team, com.bingo.mod.objective.Objective objective) {
		if (!game.announceCompletions()) {
			return;
		}

		Text message = Text.translatable(BingoConstants.key("message.objective_completed"),
				team.coloredName(), objective.displayName().copy().formatted(Formatting.WHITE));

		forEachPlayer(game, player -> {
			boolean isTeammate = team.contains(player.getUuid());
			if (isTeammate) {
				player.sendMessage(message, false);
				play(player, BingoSounds.OBJECTIVE_COMPLETE, 1.0f);
			} else if (game.revealOpponentProgress()) {
				player.sendMessage(message, false);
			}
		});
	}

	/**
	 * L'équipe est à une case d'une combinaison.
	 *
	 * <p><strong>Son local uniquement</strong>, aux membres de l'équipe : aucun message, aucun
	 * destinataire hors équipe (`docs/05` §5). Le HUD le révèle déjà à qui prend la peine de
	 * regarder — c'est le bon niveau de friction.
	 */
	static void oneAway(BingoGame game, BingoTeam team) {
		forEachMember(game, team, player -> play(player, BingoSounds.LINE_COMPLETE, 1.0f));
	}

	// ── Fin de manche ─────────────────────────────────────────────────────────

	static void gameEnded(BingoGame game, com.bingo.mod.network.payload.GameEndPayload payload) {
		boolean victory = !payload.winners().isEmpty();

		Text headline = victory
				? Text.translatable(BingoConstants.key("message.game_end"), winnerNames(game, payload.winners()))
						.formatted(Formatting.GOLD)
				: Text.translatable(BingoConstants.key("message.draw")).formatted(Formatting.GRAY);

		Text reason = Text.translatable(payload.reason().translationKey()).formatted(Formatting.GRAY);

		forEachPlayer(game, player -> {
			player.networkHandler.sendPacket(new TitleS2CPacket(headline));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(reason));
			play(player, victory ? BingoSounds.BINGO : BingoSounds.GAME_END, 1.0f);
		});

		broadcast(game, headline);
		broadcast(game, reason);
		announceRanking(game, payload.ranking());
	}

	/** Classement complet en chat — la fin par temps écoulé n'a de sens qu'avec les scores. */
	private static void announceRanking(BingoGame game, List<ScoreUpdatePayload.Entry> ranking) {
		if (ranking.isEmpty()) {
			return;
		}
		broadcast(game, Text.translatable(BingoConstants.key("message.ranking_header"))
				.formatted(Formatting.GOLD));

		int position = 1;
		for (ScoreUpdatePayload.Entry entry : ranking) {
			Text name = game.teams().get(entry.teamId())
					.map(BingoTeam::coloredName)
					.map(Text.class::cast)
					.orElseGet(() -> Text.literal(entry.teamId().value()));
			broadcast(game, Text.translatable(BingoConstants.key("message.ranking_entry"),
					position++, name, entry.score(), entry.tileCount()));
		}
	}

	private static Text winnerNames(BingoGame game, List<TeamId> winners) {
		Text joined = Text.empty();
		for (int i = 0; i < winners.size(); i++) {
			if (i > 0) {
				joined = joined.copy().append(Text.literal(", ").formatted(Formatting.GRAY));
			}
			TeamId id = winners.get(i);
			Text name = game.teams().get(id)
					.map(BingoTeam::coloredName)
					.map(Text.class::cast)
					.orElseGet(() -> Text.literal(id.value()));
			joined = joined.copy().append(name);
		}
		return joined;
	}

	// ── Envoi ─────────────────────────────────────────────────────────────────

	private static void broadcast(BingoGame game, Text message) {
		game.server().getPlayerManager().broadcast(message, false);
	}

	private static void forEachPlayer(BingoGame game, Consumer<ServerPlayerEntity> action) {
		game.server().getPlayerManager().getPlayerList().forEach(action);
	}

	private static void forEachMember(BingoGame game, BingoTeam team, Consumer<ServerPlayerEntity> action) {
		for (UUID uuid : team.members()) {
			ServerPlayerEntity player = game.server().getPlayerManager().getPlayer(uuid);
			if (player != null) {
				action.accept(player);
			}
		}
	}

	private static void play(ServerPlayerEntity player, SoundEvent sound, float volume) {
		play(player, sound, volume, 1.0f);
	}

	/**
	 * Son privé, entendu du seul destinataire.
	 *
	 * <p>{@code ServerPlayerEntity#playSound} est surchargé pour n'envoyer le paquet qu'à ce
	 * joueur — à la différence de {@code World#playSound}, qui le diffuse aux joueurs proches et
	 * ferait fuiter le son de « 4/5 » vers l'équipe adverse.
	 */
	private static void play(ServerPlayerEntity player, SoundEvent sound, float volume, float pitch) {
		player.playSound(sound, volume, pitch);
	}
}
