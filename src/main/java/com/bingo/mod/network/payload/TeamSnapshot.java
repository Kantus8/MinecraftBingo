package com.bingo.mod.network.payload;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.board.WinLines;
import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.game.team.TeamId;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Une équipe telle que le client la voit : identité, composition et grille (`docs/06` §3.1).
 *
 * <p>Sert à la fois à {@code bingo:board_sync} et à {@code bingo:team_sync}. `docs/06` §3.1
 * décrit deux charges utiles distinctes, {@code team_sync} sans la complétion ; les fusionner
 * coûte 29 octets par équipe et supprime un encodeur entier — surtout, un {@code team_sync}
 * porteur de la grille ne peut pas laisser le client afficher une composition à jour sur une
 * complétion périmée.
 *
 * <p>{@code progress} est un tableau : ce record est un porteur de transport, jamais comparé ni
 * placé dans une table de hachage, donc l'égalité par référence des tableaux est sans
 * conséquence ici.
 */
public record TeamSnapshot(
		TeamId id,
		Formatting color,
		Text name,
		List<UUID> members,
		int completionMask,
		byte[] progress
) {

	/**
	 * Plafond d'allocation pour la composition lue du réseau.
	 *
	 * <p>{@code ruleset.team_size} vaut 2 par défaut, et {@code /bingo team set} permet à un
	 * opérateur de déséquilibrer volontairement : 512 couvre tout serveur réel sans laisser une
	 * taille venue du réseau dimensionner l'allocation (même règle que les 25 cases).
	 */
	static final int MAX_MEMBERS = 512;

	public static TeamSnapshot of(BingoTeam team) {
		return new TeamSnapshot(
				team.id(),
				team.color(),
				team.displayName(),
				List.copyOf(team.members()),
				team.completionMask(),
				team.progressBytes());
	}

	/** Nom affiché coloré, reconstruit côté client. */
	public Text coloredName() {
		return name.copy().formatted(color);
	}

	public boolean isCompleted(int index) {
		return WinLines.isCompleted(completionMask, index);
	}

	public int progressAt(int index) {
		return index >= 0 && index < progress.length ? progress[index] : 0;
	}

	public int tileCount() {
		return WinLines.tileCount(completionMask);
	}

	public void write(PacketByteBuf buf) {
		buf.writeString(id.value());
		// La couleur part par son nom et non par son ordinal : Formatting mêle couleurs et
		// modificateurs de style, et son ordre n'a rien de contractuel côté Mojang.
		buf.writeString(color.getName());
		buf.writeText(name);
		buf.writeCollection(members, PacketByteBuf::writeUuid);
		buf.writeInt(completionMask);
		buf.writeByteArray(progress);
	}

	public static TeamSnapshot read(PacketByteBuf buf) {
		TeamId id = TeamId.parse(buf.readString());
		Formatting color = Formatting.byName(buf.readString());
		Text name = buf.readText();
		List<UUID> members = buf.readCollection(
				size -> new ArrayList<>(Math.min(size, MAX_MEMBERS)), PacketByteBuf::readUuid);
		int completionMask = buf.readInt() & WinLines.FULL_MASK;
		byte[] progress = buf.readByteArray(BingoBoard.TILE_COUNT);

		// Un identifiant illisible ne peut venir que d'un serveur incompatible : on le remplace
		// plutôt que de lever une exception sur le thread réseau, ce qui déconnecterait le joueur.
		return new TeamSnapshot(
				id == null ? new TeamId("?") : id,
				color == null ? Formatting.WHITE : color,
				name, members, completionMask, progress);
	}
}
