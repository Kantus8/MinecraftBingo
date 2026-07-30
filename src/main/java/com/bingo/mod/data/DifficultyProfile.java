package com.bingo.mod.data;

import com.bingo.mod.board.BingoBoard;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.Codecs;

import java.util.Map;
import java.util.Optional;

/**
 * Profil de distribution des niveaux sur les 25 cases (`docs/01` §7).
 *
 * @param distribution      niveau (1..4) → nombre de cases ; la somme doit valoir 25
 * @param timeLimitSeconds  priorité maximale sur la durée de manche (`docs/01` §8)
 */
public record DifficultyProfile(
		Optional<Text> displayName,
		Identifier pool,
		Map<Integer, Integer> distribution,
		Optional<Integer> timeLimitSeconds,
		Optional<Identifier> ruleset
) {

	/**
	 * Les clés de {@code distribution} sont des chaînes en JSON ({@code "1"}, {@code "2"}…) :
	 * un objet JSON n'a pas de clés entières. Ce codec les convertit tout en refusant celles
	 * hors 1..4, ce qui transforme une coquille en erreur explicite plutôt qu'en niveau ignoré.
	 */
	private static final Codec<Integer> LEVEL_KEY_CODEC = Codec.STRING.comapFlatMap(
			key -> {
				try {
					int level = Integer.parseInt(key);
					return level >= 1 && level <= 4
							? DataResult.success(level)
							: DataResult.error(() -> "Niveau hors bornes 1..4 : '" + key + "'");
				} catch (NumberFormatException exception) {
					return DataResult.error(() -> "Clé de niveau non numérique : '" + key + "'");
				}
			},
			String::valueOf);

	public static final Codec<DifficultyProfile> CODEC = RecordCodecBuilder.<DifficultyProfile>create(instance -> instance.group(
					Codecs.TEXT.optionalFieldOf("display_name").forGetter(DifficultyProfile::displayName),
					Identifier.CODEC.fieldOf("pool").forGetter(DifficultyProfile::pool),
					Codec.unboundedMap(LEVEL_KEY_CODEC, Codec.intRange(0, BingoBoard.TILE_COUNT))
							.fieldOf("distribution").forGetter(DifficultyProfile::distribution),
					Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("time_limit_seconds")
							.forGetter(DifficultyProfile::timeLimitSeconds),
					Identifier.CODEC.optionalFieldOf("ruleset").forGetter(DifficultyProfile::ruleset)
			).apply(instance, DifficultyProfile::new))
			.flatXmap(DifficultyProfile::validate, DifficultyProfile::validate);

	/**
	 * Règle de `docs/01` §7 : une distribution qui ne somme pas à 25 est refusée.
	 *
	 * <p>C'est une erreur et non un avertissement : un profil à 24 ou 26 produirait une grille
	 * incomplète ou un tirage tronqué, deux bugs bien plus difficiles à diagnostiquer qu'un
	 * fichier refusé au chargement.
	 */
	private static DataResult<DifficultyProfile> validate(DifficultyProfile profile) {
		int sum = profile.distribution.values().stream().mapToInt(Integer::intValue).sum();
		if (sum != BingoBoard.TILE_COUNT) {
			return DataResult.error(() -> "La distribution somme à " + sum
					+ " au lieu de " + BingoBoard.TILE_COUNT + " : " + profile.distribution);
		}
		return DataResult.success(profile);
	}

	/** Nombre de cases demandées pour ce niveau, 0 si le niveau est absent. */
	public int countFor(int level) {
		return distribution.getOrDefault(level, 0);
	}

	/**
	 * Durée de manche effective, selon la précédence stricte de `docs/01` §8 :
	 * profil, puis ruleset, puis config serveur.
	 */
	public int effectiveTimeLimitSeconds(Optional<Ruleset> ruleset, int serverFallback) {
		return timeLimitSeconds
				.or(() -> ruleset.flatMap(rules -> rules.timings().timeLimitSeconds()))
				.orElse(serverFallback);
	}
}
