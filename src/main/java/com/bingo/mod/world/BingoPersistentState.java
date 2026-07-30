package com.bingo.mod.world;

import com.bingo.mod.game.BingoGame;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

/**
 * Sauvegarde de l'état de partie dans le NBT du monde (`docs/06` §2, tâche 2.16).
 *
 * <p>Attaché au {@link ServerWorld} de l'<strong>overworld</strong> et non à chaque dimension :
 * une partie de Bingo est unique par serveur, et un état par dimension se dédoublerait dès qu'un
 * joueur passe au Nether.
 *
 * <p>La classe ne contient aucune donnée. Elle ne fait que brancher le mécanisme vanilla de
 * sauvegarde sur {@link BingoGame#writeNbt} / {@link BingoGame#readNbt} : dupliquer les champs
 * ici obligerait à les recopier dans les deux sens à chaque mutation, ce qui est exactement le
 * genre de duplication que `docs/06` §2 interdit.
 */
public final class BingoPersistentState extends PersistentState {

	/** Nom du fichier : {@code <monde>/data/bingo.dat}. */
	private static final String KEY = BingoConstants.MOD_ID;

	private final BingoGame game;

	private BingoPersistentState(BingoGame game) {
		this.game = game;
	}

	/**
	 * Récupère l'état sauvegardé — en le relisant s'il existe — et le branche sur la partie.
	 *
	 * <p>L'ordre importe : {@code getOrCreate} appelle la fonction de lecture immédiatement si le
	 * fichier existe, donc {@link BingoGame#readNbt} s'exécute avant le retour de cette méthode.
	 * C'est pourquoi elle doit être appelée après le chargement des datapacks, faute de quoi
	 * aucun identifiant de case ne serait résoluble et toute partie sauvegardée basculerait en
	 * {@code FINISHED}.
	 */
	public static BingoPersistentState attach(BingoGame game) {
		ServerWorld overworld = game.server().getWorld(World.OVERWORLD);
		if (overworld == null) {
			// Inatteignable sur un serveur démarré : l'overworld est la première dimension chargée.
			// Le garde-fou existe pour que l'absence de persistance ne se traduise pas par un NPE
			// au premier markDirty().
			BingoConstants.LOGGER.error("Overworld indisponible — état de partie non persisté");
			return new BingoPersistentState(game);
		}

		return overworld.getPersistentStateManager().getOrCreate(
				nbt -> {
					BingoPersistentState state = new BingoPersistentState(game);
					game.readNbt(nbt);
					BingoConstants.LOGGER.info("État de partie restauré : phase {}", game.phase());
					return state;
				},
				() -> new BingoPersistentState(game),
				KEY);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		return game.writeNbt(nbt);
	}
}
