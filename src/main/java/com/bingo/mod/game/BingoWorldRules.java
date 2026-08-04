package com.bingo.mod.game;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;

/**
 * Ce qu'une manche impose au <em>monde</em>, par opposition à ce que {@link BingoPlayerReset} impose
 * aux joueurs.
 *
 * <p>Deux réglages seulement, et aucun n'est derrière une clé de config : ils ne relèvent pas du goût
 * d'un opérateur mais de l'équité d'une manche chronométrée. Les remettre à l'état d'avant à la fin
 * n'est pas fait non plus — une manche s'enchaîne, et restaurer un cycle jour/nuit au milieu d'un
 * {@code /bingo reset} surprendrait plus que de laisser le monde tel que la manche l'a réglé.
 */
public final class BingoWorldRules {

	private BingoWorldRules() {
	}

	/**
	 * {@code keepInventory} à vrai, au lancement du tirage.
	 *
	 * <p>Une manche de bingo se joue à l'inventaire : perdre son stuff sur une mort de creeper ne
	 * coûte pas une vie mais la partie, et la case en cours devient inatteignable. Vanilla laisse
	 * cette règle à faux, donc la poser fait partie du lancement au même titre que la table rase.
	 *
	 * <p>{@code set(…, server)} et non un accès direct au champ : c'est la surcharge qui prévient les
	 * écouteurs de règle — sans elle, le changement passerait sous le radar de tout ce qui observe
	 * {@code GameRules}.
	 */
	public static void keepInventory(BingoGame game) {
		MinecraftServer server = game.server();
		GameRules.BooleanRule rule = server.getGameRules().get(GameRules.KEEP_INVENTORY);
		if (rule.get()) {
			return;
		}
		rule.set(true, server);
		BingoConstants.LOGGER.info("keepInventory forcé à vrai pour la manche");
	}

	/**
	 * Aube sur tous les mondes, juste avant le départ.
	 *
	 * <p>Sans cela, une manche lancée à minuit démarre par cinq minutes de monstres pour tout le
	 * monde — ce qui n'est pas un handicap équitable mais un tirage au sort sur l'heure du lancement.
	 * Repartir à {@code 0} donne à toutes les équipes la même journée pleine devant elles.
	 *
	 * <p>Tous les mondes et pas seulement l'overworld, comme {@code TimeCommand} : le temps est une
	 * donnée par dimension, et ne recaler que l'overworld laisserait un décalage visible au retour du
	 * Nether.
	 */
	public static void resetTimeOfDay(BingoGame game) {
		for (ServerWorld world : game.server().getWorlds()) {
			world.setTimeOfDay(0L);
		}
	}
}
