package com.bingo.mod.game.team;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.board.WinLines;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Une équipe et son état de complétion (`docs/06` §2, `docs/05` §3).
 *
 * <p>Ce que cette classe ne contient <strong>pas</strong> est aussi important que ce qu'elle
 * contient : pas de score, pas de compteur de cases, pas de drapeau « a gagné ». Tout cela se
 * dérive de {@code completionMask} ({@link com.bingo.mod.game.BingoScoring},
 * {@link WinLines}). Un état qui ne peut pas devenir incohérent est un état minimal.
 *
 * <p>La carte étant partagée et sans verrouillage (`docs/01` §9), c'est bien le couple
 * (équipe, index de case) qui porte la complétion — d'où ces tableaux de 25 entrées par équipe
 * plutôt qu'un état sur la case.
 */
public final class BingoTeam {

	/**
	 * Les 4 couleurs qui disposent d'une clé de traduction livrée ({@code bingo.team.red}, …).
	 *
	 * <p>Une équipe créée avec une autre couleur reste valide : elle prend son identifiant comme
	 * nom affiché plutôt qu'une clé de traduction qui n'existerait pas.
	 */
	private static final List<Formatting> NAMED_COLORS =
			List.of(Formatting.RED, Formatting.BLUE, Formatting.GREEN, Formatting.YELLOW);

	private final TeamId id;
	private final Text displayName;
	private final Formatting color;
	private final Set<UUID> members = new LinkedHashSet<>();

	private int completionMask;
	private final int[] progress = new int[BingoBoard.TILE_COUNT];
	private final long[] completedAtMs = new long[BingoBoard.TILE_COUNT];

	/** Cache dérivé de {@code (tiles, completionMask)}, jamais persisté. */
	private TeamPendingIndex pending = TeamPendingIndex.EMPTY;

	public BingoTeam(TeamId id, Formatting color) {
		this.id = id;
		this.color = color;
		this.displayName = defaultName(id, color);
	}

	/**
	 * Nom affiché : la clé traduisible de la couleur si elle en a une, sinon l'identifiant brut.
	 *
	 * <p>Un {@link Text} et non un {@code String} : `docs/06` §3.3 impose de transporter des
	 * {@code Text} sur le réseau, faute de quoi le client d'un joueur anglophone verrait
	 * « Rouge ».
	 */
	private static Text defaultName(TeamId id, Formatting color) {
		if (NAMED_COLORS.contains(color)) {
			return Text.translatable(BingoConstants.key("team." + color.getName()));
		}
		return Text.literal(id.value());
	}

	public TeamId id() {
		return id;
	}

	public Text displayName() {
		return displayName;
	}

	public Formatting color() {
		return color;
	}

	/** Nom affiché coloré. Instance neuve à chaque appel, donc librement re-formatable. */
	public MutableText coloredName() {
		return displayName.copy().formatted(color);
	}

	// ── Composition ───────────────────────────────────────────────────────────

	/** Les membres, dans leur ordre d'arrivée. Vue non modifiable. */
	public Set<UUID> members() {
		return java.util.Collections.unmodifiableSet(members);
	}

	public boolean contains(UUID player) {
		return members.contains(player);
	}

	public boolean addMember(UUID player) {
		return members.add(player);
	}

	public boolean removeMember(UUID player) {
		return members.remove(player);
	}

	public void clearMembers() {
		members.clear();
	}

	public int size() {
		return members.size();
	}

	public boolean isEmpty() {
		return members.isEmpty();
	}

	// ── Complétion ────────────────────────────────────────────────────────────

	public int completionMask() {
		return completionMask;
	}

	public boolean isCompleted(int index) {
		return WinLines.isCompleted(completionMask, index);
	}

	public int progress(int index) {
		return progress[index];
	}

	public long completedAtMs(int index) {
		return completedAtMs[index];
	}

	/** Nombre de cases validées — dérivé, jamais stocké (`docs/06` §2). */
	public int tileCount() {
		return WinLines.tileCount(completionMask);
	}

	/**
	 * Fixe l'avancement partiel d'une case.
	 *
	 * @return {@code true} si la valeur a changé, ce qui est la condition d'envoi d'un
	 *         {@code tile_update} — sans ce test, un scan {@code FIND} toutes les 10 ticks
	 *         inonderait le réseau de paquets identiques (`docs/06` §4 : aucun paquet par tick).
	 */
	public boolean setProgress(int index, int value) {
		if (progress[index] == value) {
			return false;
		}
		progress[index] = value;
		return true;
	}

	/** Coche une case. Sans effet si elle l'était déjà. */
	public boolean complete(int index, long nowMs) {
		if (isCompleted(index)) {
			return false;
		}
		completionMask |= WinLines.bit(index);
		completedAtMs[index] = nowMs;
		return true;
	}

	/** Décoche une case — {@code /bingo debug uncomplete} (`docs/05` §4.1). */
	public boolean uncomplete(int index) {
		if (!isCompleted(index)) {
			return false;
		}
		completionMask &= ~WinLines.bit(index);
		completedAtMs[index] = 0L;
		progress[index] = 0;
		return true;
	}

	/** Remet la grille de l'équipe à zéro, sans toucher à sa composition (`/bingo reroll`). */
	public void clearCompletion() {
		completionMask = 0;
		java.util.Arrays.fill(progress, 0);
		java.util.Arrays.fill(completedAtMs, 0L);
		pending = TeamPendingIndex.EMPTY;
	}

	/**
	 * Instant de complétion de la dernière case d'une combinaison — l'arbitre des égalités de
	 * `docs/05` §1.4.
	 */
	public long completedAtMs(WinLines.Line line) {
		long latest = 0L;
		for (int index : line.indices()) {
			latest = Math.max(latest, completedAtMs[index]);
		}
		return latest;
	}

	/**
	 * Avancement des 25 cases, clampé à 127 pour l'encodage réseau (`docs/06` §3.3).
	 *
	 * <p>Le clamp est sans perte utile : un {@code count} au-delà de 127 rendrait la case
	 * illisible dans le badge du HUD bien avant de gêner l'affichage de l'avancement.
	 */
	public byte[] progressBytes() {
		byte[] bytes = new byte[BingoBoard.TILE_COUNT];
		for (int index = 0; index < bytes.length; index++) {
			bytes[index] = (byte) Math.min(progress[index], Byte.MAX_VALUE);
		}
		return bytes;
	}

	// ── Index inversés ────────────────────────────────────────────────────────

	public TeamPendingIndex pending() {
		return pending;
	}

	/** À rappeler après chaque tirage et chaque complétion (`docs/06` §6). */
	public void rebuildIndex(List<Objective> tiles) {
		pending = TeamPendingIndex.build(tiles, completionMask);
	}

	// ── Persistance (`docs/06` §2) ─────────────────────────────────────────────

	public NbtCompound writeNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putString("id", id.value());
		nbt.putString("color", color.getName());
		nbt.putInt("mask", completionMask);
		nbt.putIntArray("progress", progress.clone());
		nbt.putLongArray("completedAt", completedAtMs.clone());

		NbtList list = new NbtList();
		members.forEach(uuid -> list.add(NbtHelper.fromUuid(uuid)));
		nbt.put("members", list);
		return nbt;
	}

	/**
	 * @return l'équipe relue, ou {@code null} si l'entrée est inexploitable (déjà journalisée).
	 *         Une équipe perdue vaut mieux qu'un crash au chargement du monde.
	 */
	public static @Nullable BingoTeam fromNbt(NbtCompound nbt) {
		TeamId id = TeamId.parse(nbt.getString("id"));
		if (id == null) {
			BingoConstants.LOGGER.warn("Équipe persistée à l'identifiant illisible ('{}') — ignorée",
					nbt.getString("id"));
			return null;
		}

		Formatting color = Formatting.byName(nbt.getString("color"));
		if (color == null || !color.isColor()) {
			BingoConstants.LOGGER.warn("Équipe '{}' : couleur '{}' inconnue — repli sur WHITE",
					id, nbt.getString("color"));
			color = Formatting.WHITE;
		}

		BingoTeam team = new BingoTeam(id, color);
		// Borné aux 25 bits utiles : un masque corrompu ne doit pas faire croire à des cases
		// validées au-delà de la grille, ce que WinLines interpréterait silencieusement.
		team.completionMask = nbt.getInt("mask") & WinLines.FULL_MASK;

		int[] storedProgress = nbt.getIntArray("progress");
		System.arraycopy(storedProgress, 0, team.progress, 0,
				Math.min(storedProgress.length, team.progress.length));

		long[] storedTimestamps = nbt.getLongArray("completedAt");
		System.arraycopy(storedTimestamps, 0, team.completedAtMs, 0,
				Math.min(storedTimestamps.length, team.completedAtMs.length));

		NbtList list = nbt.getList("members", NbtElement.INT_ARRAY_TYPE);
		for (int i = 0; i < list.size(); i++) {
			team.members.add(NbtHelper.toUuid(list.get(i)));
		}
		return team;
	}
}
