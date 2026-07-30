package com.bingo.mod.client.roll;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Util;

/**
 * Étincelles 2D de la finale du tirage (`docs/04` §4, tâche 4.8).
 *
 * <p>Le système de particules de Minecraft est world-space : il ne peut rien dessiner dans le HUD.
 * D'où ce micro-système maison — 24 sprites de 2×2 px, 600 ms, émis du centre du panneau au moment
 * où la dernière ligne se verrouille.
 *
 * <p>Comme l'animation elle-même, <strong>tout est dérivé du temps</strong> : la position d'une
 * étincelle est une fonction close de son âge, pas une intégration frame par frame. Une frame
 * sautée ne décale donc rien, et il n'y a aucun état à remettre à zéro entre deux manches.
 */
public final class RollSparks {

	private static final int COUNT = 24;
	private static final long LIFETIME_MS = 600L;

	/** Norme de la vitesse initiale, en pixels par seconde (`docs/04` §4). */
	private static final float SPEED_MIN = 60f;
	private static final float SPEED_MAX = 140f;

	/** Gravité, en pixels par seconde². */
	private static final float GRAVITY = 240f;

	private static final int SIZE = 2;

	/** Tirage pondéré : deux tons chauds pour un blanc (`docs/04` §4). */
	private static final int[] COLORS = {0x00FFDD44, 0x00FF8822, 0x00FFDD44, 0x00FFFFFF};

	private static long birthMs;
	private static int originX;
	private static int originY;
	private static long seed;
	private static boolean active;

	private RollSparks() {
	}

	/** Émet la gerbe depuis un point de l'écran. Une seule gerbe à la fois — il n'en faut qu'une. */
	public static void emit(int x, int y, long seed) {
		originX = x;
		originY = y;
		RollSparks.seed = seed;
		birthMs = Util.getMeasuringTimeMs();
		active = true;
	}

	public static void stop() {
		active = false;
	}

	/**
	 * Dessine la gerbe, sans effet si elle est éteinte.
	 *
	 * <p>Appelée après le panneau : les étincelles passent devant la grille, ce qui est le seul
	 * ordre qui donne l'impression qu'elles en jaillissent.
	 */
	public static void render(DrawContext context) {
		if (!active) {
			return;
		}
		long age = Util.getMeasuringTimeMs() - birthMs;
		if (age >= LIFETIME_MS) {
			active = false;
			return;
		}

		float seconds = age / 1000f;
		float alpha = 1f - (float) age / LIFETIME_MS;

		for (int index = 0; index < COUNT; index++) {
			long hash = hash(seed, index);

			// Direction uniforme sur le cercle, norme dans [60, 140] px/s.
			float angle = (float) (Math.floorMod(hash, 3600) / 3600.0 * Math.PI * 2.0);
			float speed = SPEED_MIN + (SPEED_MAX - SPEED_MIN) * (Math.floorMod(hash >>> 12, 1000) / 1000f);

			float x = originX + (float) Math.cos(angle) * speed * seconds;
			float y = originY + (float) Math.sin(angle) * speed * seconds
					+ 0.5f * GRAVITY * seconds * seconds;

			int color = COLORS[(int) Math.floorMod(hash >>> 24, COLORS.length)]
					| ((int) (alpha * 0xFF) << 24);

			int left = (int) x;
			int top = (int) y;
			context.fill(left, top, left + SIZE, top + SIZE, color);
		}
	}

	/** Même mélange que {@code RollAnimationState} : déterministe, sans état. */
	private static long hash(long seed, int index) {
		long value = seed * 0x9E3779B97F4A7C15L + index * 0xD1342543DE82EF95L;
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		value ^= value >>> 31;
		return value;
	}
}
