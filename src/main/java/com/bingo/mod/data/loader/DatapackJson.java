package com.bingo.mod.data.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Utilitaires communs aux chargeurs de datapack. */
public final class DatapackJson {

	private DatapackJson() {
	}

	/**
	 * Supprime récursivement les clés à valeur {@code null}.
	 *
	 * <p>Les schémas de `docs/01` documentent les champs optionnels avec {@code "champ": null},
	 * et ces blocs servent de modèle à qui écrit un datapack. Or {@code optionalFieldOf} de DFU
	 * attend une clé <em>absente</em> : un {@code null} explicite fait échouer le décodage. Sans
	 * ce nettoyage, un datapack copié depuis notre propre documentation serait rejeté.
	 */
	public static JsonElement stripNulls(JsonElement element) {
		if (element instanceof JsonObject object) {
			object.entrySet().removeIf(entry -> entry.getValue().isJsonNull());
			object.entrySet().forEach(entry -> stripNulls(entry.getValue()));
		}
		return element;
	}
}
