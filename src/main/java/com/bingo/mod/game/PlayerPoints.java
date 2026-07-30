package com.bingo.mod.game;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Points individuels des joueurs, cumulés d'une manche à l'autre (tableau des équipes du HUD).
 *
 * <p><strong>Le seul accumulateur du mod</strong>, et il l'est par nécessité. Tout le reste se
 * dérive de {@code completionMask} ({@link BingoScoring}, {@code BingoTeam}) parce qu'un état
 * dérivé ne peut pas devenir incohérent — mais un total qui traverse les manches n'a rien dont se
 * dériver : la carte de la manche précédente n'existe plus, et {@code /bingo reset} détruit les
 * équipes. C'est un <em>historique</em>, pas une vue.
 *
 * <p>Corollaire assumé : {@code /bingo debug uncomplete} ne rend pas les points déjà crédités, de
 * la même façon qu'annuler une case ne rend pas le temps passé à la valider. La correction se fait
 * à la main, par {@code /bingo points reset}.
 *
 * <p>Le nom de connexion est mémorisé avec le total. Sans lui, le tableau afficherait des UUID pour
 * tout membre déconnecté — un joueur absent reste dans son équipe (`docs/05` §3), donc le cas est la
 * règle et non l'exception.
 */
public final class PlayerPoints {

	/** Repli d'affichage pour un joueur dont le nom n'a jamais été vu. */
	public static final String UNKNOWN_NAME = "?";

	/**
	 * Total et dernier nom connu d'un joueur.
	 *
	 * <p>Une classe mutable et non un {@code record} : le total est incrémenté à chaque case validée,
	 * et remplacer l'entrée de la table à chaque fois n'apporterait rien qu'une allocation.
	 */
	private static final class Tally {

		private String name;
		private int points;

		private Tally(String name) {
			this.name = name;
		}
	}

	/** Un joueur tel que le tableau des équipes et {@code /bingo points} le lisent. */
	public record Entry(UUID player, String name, int points) {
	}

	/** Ordre d'insertion préservé : c'est celui de la première connexion, et il est reproductible. */
	private final Map<UUID, Tally> tallies = new LinkedHashMap<>();

	/**
	 * Mémorise ou rafraîchit le nom d'un joueur, sans toucher à son total.
	 *
	 * <p>Appelé à chaque connexion : un joueur qui change de pseudo doit apparaître sous le nouveau,
	 * et un joueur qui n'a encore rien marqué doit tout de même figurer au tableau — sinon les membres
	 * d'une équipe fraîchement composée n'auraient pas de ligne.
	 *
	 * @return {@code true} si la table a changé, seule condition qui justifie un {@code player_stats}
	 */
	public boolean remember(ServerPlayerEntity player) {
		return remember(player.getUuid(), player.getGameProfile().getName());
	}

	public boolean remember(UUID player, String name) {
		Tally tally = tallies.get(player);
		if (tally == null) {
			tallies.put(player, new Tally(name));
			return true;
		}
		if (tally.name.equals(name)) {
			return false;
		}
		tally.name = name;
		return true;
	}

	/**
	 * Crédite un joueur des points d'une case qu'il vient de valider (`docs/05` §2.1 pour le barème).
	 *
	 * <p>L'addition sature au lieu de déborder : {@code int} suffit à des centaines de milliers de
	 * manches, mais un total qui repasserait négatif serait un affichage absurde impossible à
	 * expliquer au joueur.
	 *
	 * @return {@code true} si le total a changé
	 */
	public boolean award(ServerPlayerEntity player, int points) {
		if (points == 0) {
			return false;
		}
		remember(player);
		Tally tally = tallies.get(player.getUuid());
		int updated = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) tally.points + points));
		if (updated == tally.points) {
			return false;
		}
		tally.points = updated;
		return true;
	}

	public int points(UUID player) {
		Tally tally = tallies.get(player);
		return tally == null ? 0 : tally.points;
	}

	public String name(UUID player) {
		Tally tally = tallies.get(player);
		return tally == null ? UNKNOWN_NAME : tally.name;
	}

	public int count() {
		return tallies.size();
	}

	/** Toutes les entrées, meilleur total en tête puis par nom — l'ordre de {@code /bingo points}. */
	public List<Entry> ranking() {
		return entries().stream()
				.sorted(Comparator.comparingInt(Entry::points).reversed()
						.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	/** Toutes les entrées, dans l'ordre d'insertion. */
	public List<Entry> entries() {
		List<Entry> result = new ArrayList<>(tallies.size());
		tallies.forEach((uuid, tally) -> result.add(new Entry(uuid, tally.name, tally.points)));
		return result;
	}

	/**
	 * Remet un joueur à zéro sans l'oublier : son nom reste au tableau, avec 0 point.
	 *
	 * @return {@code true} si le joueur avait des points
	 */
	public boolean reset(UUID player) {
		Tally tally = tallies.get(player);
		if (tally == null || tally.points == 0) {
			return false;
		}
		tally.points = 0;
		return true;
	}

	/** @return le nombre de joueurs qui avaient des points */
	public int resetAll() {
		int affected = 0;
		for (Tally tally : tallies.values()) {
			if (tally.points != 0) {
				tally.points = 0;
				affected++;
			}
		}
		return affected;
	}

	// ── Persistance (`docs/06` §2) ─────────────────────────────────────────────

	public NbtList writeNbt() {
		NbtList list = new NbtList();
		tallies.forEach((uuid, tally) -> {
			NbtCompound entry = new NbtCompound();
			entry.put("id", NbtHelper.fromUuid(uuid));
			entry.putString("name", tally.name);
			entry.putInt("points", tally.points);
			list.add(entry);
		});
		return list;
	}

	/**
	 * Relit les totaux persistés.
	 *
	 * <p>Une entrée sans UUID exploitable est ignorée avec un WARN : perdre le total d'un joueur est
	 * regrettable, refuser de charger le monde le serait bien davantage.
	 */
	public void readNbt(NbtList list) {
		tallies.clear();
		for (int i = 0; i < list.size(); i++) {
			NbtCompound entry = list.getCompound(i);
			if (!entry.contains("id", NbtElement.INT_ARRAY_TYPE)) {
				BingoConstants.LOGGER.warn("Entrée de points sans UUID — ignorée");
				continue;
			}
			UUID uuid = NbtHelper.toUuid(entry.get("id"));
			String name = entry.contains("name") ? entry.getString("name") : UNKNOWN_NAME;
			Tally tally = new Tally(name);
			// Un total négatif ne peut venir que d'un NBT édité à la main : on le ramène à zéro
			// plutôt que de laisser le HUD afficher « -400 pts ».
			tally.points = Math.max(0, entry.getInt("points"));
			tallies.put(uuid, tally);
		}
	}

	/** Le type NBT d'une entrée de la liste écrite par {@link #writeNbt()}. */
	public static int nbtEntryType() {
		return NbtElement.COMPOUND_TYPE;
	}
}
