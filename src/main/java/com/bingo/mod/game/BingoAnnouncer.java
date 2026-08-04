package com.bingo.mod.game;

import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.game.team.TeamId;
import com.bingo.mod.network.payload.ScoreUpdatePayload;
import com.bingo.mod.registry.BingoSounds;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
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

	/**
	 * Volume du cor de départ, repris tel quel de {@code Raid#playRaidHorn}.
	 *
	 * <p>Les quatre variantes de {@code event.raid.horn} sont authorées à {@code "volume": 0.01} dans
	 * le {@code sounds.json} vanilla : ce cor est fait pour être diffusé à l'échelle d'un raid, pas
	 * joué à l'oreille d'un joueur. Le gain effectif étant le produit du volume d'émission et de celui
	 * du fichier, un appel à {@code 1.0f} — la valeur évidente, et celle qu'utilisent tous les autres
	 * sons d'ici — donne 1 % de gain, donc un silence. Vanilla compense avec {@code 64.0f} ;
	 * {@code 96.0f} est ce chiffre majoré de 50 %, soit un gain de 0,96.
	 *
	 * <p><strong>C'est à peu près le plafond utile</strong> : le client borne le gain à 1,0
	 * ({@code SoundSystem#getAdjustedVolume}), donc au-delà de {@code 100.0f} la valeur est absorbée
	 * sans rien changer. Pour un cor plus présent, il faudrait un autre son, pas un autre nombre.
	 *
	 * <p>Ce volume ne suffit que parce que {@link BingoSounds#GAME_START} référence l'événement vanilla
	 * sans passer par un alias {@code sounds.json} — lequel aurait élevé le 0,01 au carré. Voir le
	 * javadoc de cette constante avant de toucher à l'un des deux.
	 */
	private static final float RAID_HORN_VOLUME = 96.0f;

	private BingoAnnouncer() {
	}

	// ── Cycle de la manche ────────────────────────────────────────────────────

	/** Départ : titre plein écran et son pour tout le monde. */
	static void gameStarted(BingoGame game) {
		Text title = Text.translatable(BingoConstants.key("message.game_start")).formatted(Formatting.GOLD);
		forEachPlayer(game, player -> {
			player.networkHandler.sendPacket(new TitleS2CPacket(title));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.empty()));
			play(player, BingoSounds.GAME_START, RAID_HORN_VOLUME);
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
			play(player, endSound(game, payload, player), 1.0f);
		});

		broadcast(game, headline);
		broadcast(game, reason);
		announceRanking(game, payload.ranking());
	}

	/**
	 * Le son de fin dépend du joueur, pas de la manche.
	 *
	 * <p>Le titre plein écran annonce le vainqueur, ce qui laisse chacun déduire son propre sort en
	 * lisant un nom d'équipe. Le son, lui, le dit immédiatement — c'est la raison d'être de cette
	 * méthode.
	 *
	 * <p>Deux cas seulement, et l'égalité tombe dans le second sans test dédié : une liste de
	 * vainqueurs vide ne contient l'équipe de personne. Un joueur sans équipe — spectateur, arrivé en
	 * cours de manche — y tombe aussi, ce qui est correct : il n'a pas gagné.
	 */
	private static SoundEvent endSound(BingoGame game, com.bingo.mod.network.payload.GameEndPayload payload,
			ServerPlayerEntity player) {
		return game.teams().of(player.getUuid())
				.filter(team -> payload.winners().contains(team.id()))
				.map(team -> BingoSounds.BINGO)
				.orElse(BingoSounds.GAME_END);
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
	 * <p><strong>La surcharge à quatre arguments n'est pas décorative.</strong>
	 * {@code ServerPlayerEntity} ne redéfinit que celle-ci, qui envoie un {@code PlaySoundS2CPacket}
	 * au seul {@code networkHandler} du joueur. La forme à trois arguments — la plus courte, donc la
	 * tentante — n'est pas redéfinie : elle tombe sur {@code Entity#playSound}, qui appelle
	 * {@code getWorld().playSound(null, …)} et <em>diffuse à tous les joueurs proches</em>. Passer par
	 * elle ferait fuiter le son de « 4/5 » vers l'équipe adverse, exactement ce que cette classe
	 * cherche à éviter, et ferait entendre à chacun autant de copies du son qu'il y a de joueurs
	 * autour.
	 *
	 * <p>{@code PLAYERS} reproduit ce que {@code PlayerEntity#getSoundCategory} renvoyait par l'ancien
	 * chemin : le joueur garde la main via son curseur de volume.
	 */
	private static void play(ServerPlayerEntity player, SoundEvent sound, float volume, float pitch) {
		player.playSound(sound, SoundCategory.PLAYERS, volume, pitch);
	}
}
