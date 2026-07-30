package com.bingo.mod.game.detect;

import net.minecraft.nbt.NbtCompound;

/**
 * Un déclencheur du registre {@code bingo:action} (`docs/01` §4.5).
 *
 * <p>Sa seule responsabilité est de répondre à une question : cet événement de jeu, confronté à
 * ces {@code params} de datapack, valide-t-il l'objectif ? Le déclencheur n'observe rien lui-même
 * — les hooks vivent dans {@link BingoDetectors} — ce qui permet de le tester sans monde chargé.
 */
@FunctionalInterface
public interface ActionTrigger {

	/**
	 * @param event  ce qui vient de se produire
	 * @param params la charge utile libre du datapack, un compound vide si l'objectif n'en donne pas
	 */
	boolean matches(ActionEvent event, NbtCompound params);
}
