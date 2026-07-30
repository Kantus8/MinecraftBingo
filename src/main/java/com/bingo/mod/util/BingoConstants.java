package com.bingo.mod.util;

import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identité du mod, logger partagé et fabriques d'identifiants.
 *
 * <p>Aucune logique de jeu ici : cette classe est chargée par tout le monde,
 * y compris par les entrypoints, et ne doit jamais dépendre d'un état.
 */
public final class BingoConstants {

	/** Doit rester synchronisé avec {@code mod_id} de {@code gradle.properties}. */
	public static final String MOD_ID = "bingo";

	/** Doit rester synchronisé avec {@code mod_name} de {@code gradle.properties}. */
	public static final String MOD_NAME = "Minecraft Bingo";

	/** Logger unique du mod, partagé par les deux source sets. */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

	private BingoConstants() {
	}

	/** Construit {@code bingo:<path>}. */
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	/** Construit une clé de traduction {@code bingo.<suffix>}. */
	public static String key(String suffix) {
		return MOD_ID + "." + suffix;
	}
}
