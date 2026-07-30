package com.bingo.mod.game;

import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Effets visuels de partie côté monde (`docs/04` §2.3, tâche 4.6).
 *
 * <p>La contrepartie serveur de l'animation client : elle est 100 % locale sauf ce burst, qui doit
 * être vu <em>des autres</em>.
 */
public final class BingoVfx {

	/** 40 particules, sphère de 1,5 bloc, vitesse ±0.15 (`docs/04` §2.3). */
	private static final int FIREWORK_COUNT = 40;
	private static final double FIREWORK_RADIUS = 1.5;
	private static final double FIREWORK_SPEED = 0.15;

	/** Hauteur d'émission : au niveau du torse plutôt qu'aux pieds, sinon la moitié part dans le sol. */
	private static final double FIREWORK_HEIGHT = 1.2;

	private BingoVfx() {
	}

	/**
	 * Le burst de fin de tirage, à {@code t = 3000}.
	 *
	 * <p><strong>Spawn serveur et non client</strong> : un spawn local coûterait moins cher mais
	 * chaque joueur ne verrait que ses propres feux d'artifice — or tout l'intérêt du moment est de
	 * voir ceux des autres. Le coût réseau est un burst par manche.
	 */
	public static void rollFinale(BingoGame game) {
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			player.getServerWorld().spawnParticles(
					player,
					ParticleTypes.FIREWORK,
					// force = true : sans lui, les joueurs réglés en particules « Minimales » ne
					// voient strictement rien du moment le plus spectaculaire de la manche.
					true,
					player.getX(), player.getY() + FIREWORK_HEIGHT, player.getZ(),
					FIREWORK_COUNT,
					FIREWORK_RADIUS, FIREWORK_RADIUS, FIREWORK_RADIUS,
					FIREWORK_SPEED);
		}
	}
}
