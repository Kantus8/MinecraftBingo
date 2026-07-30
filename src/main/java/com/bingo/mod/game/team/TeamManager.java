package com.bingo.mod.game.team;

import com.bingo.mod.objective.Objective;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Composition des équipes (`docs/05` §3, `docs/05` §4.1 sous-arbre {@code team}).
 *
 * <p>Un joueur appartient à <strong>au plus une</strong> équipe : c'est l'invariant que tout
 * passe par cette classe sert à tenir. {@link #join} retire donc systématiquement le joueur de
 * son équipe précédente — laisser l'appelant s'en charger reviendrait à confier l'invariant à
 * huit sites de commande différents.
 *
 * <p>Les équipes survivent à un {@code /bingo stop} mais pas à un {@code /bingo reset}
 * (`docs/05` §3) : d'où {@link #clearMembers()}, qui vide sans détruire, et
 * {@link #removeAll()}, qui détruit.
 */
public final class TeamManager {

	/**
	 * Palette d'attribution automatique, dans l'ordre.
	 *
	 * <p>Les 4 couleurs qui disposent d'une clé de traduction livrée, et exactement
	 * {@code max_teams} par défaut (`docs/01` §8).
	 */
	public static final List<Formatting> DEFAULT_COLORS =
			List.of(Formatting.RED, Formatting.BLUE, Formatting.GREEN, Formatting.YELLOW);

	/** Ordre d'insertion préservé : c'est celui du pied de score du HUD et de {@code team_sync}. */
	private final Map<TeamId, BingoTeam> teams = new LinkedHashMap<>();

	/** Les équipes, dans leur ordre de création. */
	public Collection<BingoTeam> all() {
		return Collections.unmodifiableCollection(teams.values());
	}

	public Optional<BingoTeam> get(TeamId id) {
		return Optional.ofNullable(teams.get(id));
	}

	public boolean exists(TeamId id) {
		return teams.containsKey(id);
	}

	public int count() {
		return teams.size();
	}

	/** Équipes comptant au moins un membre — la précondition de {@code /bingo start}. */
	public int countStaffed() {
		return (int) teams.values().stream().filter(team -> !team.isEmpty()).count();
	}

	/** L'équipe d'un joueur, vide s'il est spectateur (`docs/05` §3). */
	public Optional<BingoTeam> of(UUID player) {
		return teams.values().stream().filter(team -> team.contains(player)).findFirst();
	}

	// ── Création et suppression ───────────────────────────────────────────────

	/** @return l'équipe créée, ou vide si l'identifiant est déjà pris. */
	public Optional<BingoTeam> create(TeamId id, Formatting color) {
		if (teams.containsKey(id)) {
			return Optional.empty();
		}
		BingoTeam team = new BingoTeam(id, color);
		teams.put(id, team);
		return Optional.of(team);
	}

	public boolean remove(TeamId id) {
		return teams.remove(id) != null;
	}

	/** Vide les équipes de leurs membres sans les supprimer ({@code /bingo team clear}). */
	public void clearMembers() {
		teams.values().forEach(BingoTeam::clearMembers);
	}

	/** Supprime toutes les équipes ({@code /bingo reset}). */
	public void removeAll() {
		teams.clear();
	}

	/** Remet à zéro les grilles sans toucher aux compositions ({@code /bingo reroll}). */
	public void clearCompletions() {
		teams.values().forEach(BingoTeam::clearCompletion);
	}

	/** Reconstruit les index inversés des équipes après un tirage (`docs/06` §6). */
	public void rebuildIndexes(List<Objective> tiles) {
		teams.values().forEach(team -> team.rebuildIndex(tiles));
	}

	// ── Appartenance ──────────────────────────────────────────────────────────

	/** Issue d'une tentative de {@code /bingo team join} (`docs/05` §4.1). */
	public enum JoinResult {
		JOINED,
		/** Déjà dans cette équipe — succès muet, pas une erreur. */
		ALREADY_MEMBER,
		UNKNOWN_TEAM,
		TEAM_FULL
	}

	/**
	 * Affecte un joueur à une équipe, en le retirant de la précédente.
	 *
	 * @param teamSize taille maximale, {@code ruleset.team_size} (`docs/05` §3). L'affectation
	 *                 d'autorité ({@code /bingo team set}, niveau opérateur) passe
	 *                 {@link Integer#MAX_VALUE} : un opérateur doit pouvoir déséquilibrer
	 *                 volontairement, la limite protège le joueur, pas l'arbitre.
	 */
	public JoinResult join(UUID player, TeamId teamId, int teamSize) {
		BingoTeam target = teams.get(teamId);
		if (target == null) {
			return JoinResult.UNKNOWN_TEAM;
		}
		if (target.contains(player)) {
			return JoinResult.ALREADY_MEMBER;
		}
		if (target.size() >= teamSize) {
			return JoinResult.TEAM_FULL;
		}
		leave(player);
		target.addMember(player);
		return JoinResult.JOINED;
	}

	/** @return l'équipe quittée, vide si le joueur n'en avait pas. */
	public Optional<BingoTeam> leave(UUID player) {
		Optional<BingoTeam> current = of(player);
		current.ifPresent(team -> team.removeMember(player));
		return current;
	}

	/**
	 * Répartit les joueurs sans équipe par binômes ({@code /bingo team autobalance}).
	 *
	 * <p>Complète d'abord les équipes existantes incomplètes — un binôme orphelin doit être
	 * comblé avant d'ouvrir une équipe de plus — puis crée de nouvelles équipes dans l'ordre de
	 * {@link #DEFAULT_COLORS} tant que {@code maxTeams} le permet.
	 *
	 * @return le nombre de joueurs effectivement affectés ; le reliquat passera spectateur au
	 *         démarrage (`docs/05` §3).
	 */
	public int autobalance(List<UUID> unassigned, int teamSize, int maxTeams) {
		int assigned = 0;

		for (UUID player : unassigned) {
			BingoTeam target = teams.values().stream()
					.filter(team -> team.size() < teamSize)
					.findFirst()
					.orElseGet(() -> openNextTeam(maxTeams).orElse(null));

			if (target == null) {
				break;
			}
			leave(player);
			target.addMember(player);
			assigned++;
		}
		return assigned;
	}

	/** Crée l'équipe suivante de la palette, vide si la palette ou {@code maxTeams} est épuisé. */
	private Optional<BingoTeam> openNextTeam(int maxTeams) {
		if (teams.size() >= maxTeams) {
			return Optional.empty();
		}
		for (Formatting color : DEFAULT_COLORS) {
			TeamId id = TeamId.parse(color.getName());
			if (id != null && !teams.containsKey(id)) {
				return create(id, color);
			}
		}
		return Optional.empty();
	}

	// ── Persistance (`docs/06` §2) ─────────────────────────────────────────────

	public NbtList writeNbt() {
		NbtList list = new NbtList();
		teams.values().forEach(team -> list.add(team.writeNbt()));
		return list;
	}

	public void readNbt(NbtList list) {
		teams.clear();
		for (int i = 0; i < list.size(); i++) {
			NbtCompound entry = list.getCompound(i);
			BingoTeam team = BingoTeam.fromNbt(entry);
			if (team == null) {
				continue;
			}
			// Un doublon d'identifiant ne peut venir que d'un NBT édité à la main. On garde la
			// première occurrence : écraser ferait disparaître des membres sans le dire.
			if (teams.putIfAbsent(team.id(), team) != null) {
				BingoConstants.LOGGER.warn("Équipe '{}' en double dans la sauvegarde — seconde ignorée",
						team.id());
			}
		}
	}

	/** Les joueurs connectés sans équipe, pour {@code autobalance} et le passage spectateur. */
	public List<UUID> unassigned(Collection<UUID> connected) {
		List<UUID> result = new ArrayList<>();
		for (UUID player : connected) {
			if (of(player).isEmpty()) {
				result.add(player);
			}
		}
		return result;
	}

	/** Le type NBT d'une entrée de la liste écrite par {@link #writeNbt()}. */
	public static int nbtEntryType() {
		return NbtElement.COMPOUND_TYPE;
	}
}
