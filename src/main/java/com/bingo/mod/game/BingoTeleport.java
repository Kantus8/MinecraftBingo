package com.bingo.mod.game;

import com.bingo.mod.config.BingoServerConfig;
import com.bingo.mod.util.BingoConstants;
import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Zone de départ vierge, tirée à chaque {@code /bingo start … teleport} (option de commande).
 *
 * <p><strong>Ce que « encore inexplorée » veut dire ici.</strong> Minecraft ne sait pas répondre à
 * « ce terrain a-t-il déjà été vu ? » : un fichier de région existe dès qu'un joueur a survolé la
 * zone, et l'API de lecture des chunks non chargés est asynchrone et privée. Le mod approxime donc
 * par trois critères qui se vérifient sans toucher au stockage :
 * <ul>
 *   <li>à {@code teleport_min_distance} au moins du spawn du monde ;</li>
 *   <li>à {@link #SEPARATION} blocs au moins de toute zone déjà tirée par une manche précédente
 *       — c'est ce que {@code BingoGame} persiste, et la seule raison pour laquelle un coin perdu
 *       aurait déjà été fouillé est précisément qu'un bingo y a été joué ;</li>
 *   <li>à {@link #SEPARATION} blocs au moins de la position actuelle de chaque joueur.</li>
 * </ul>
 *
 * <p><strong>La sélection passe par une recherche de biome</strong>, pas par un tirage de
 * coordonnées à l'aveugle. {@code ServerWorld#locateBiome} interroge la source de biomes — du bruit
 * pur — donc elle voit le terrain vierge <em>sans générer un seul chunk</em>, et elle recentre un
 * point tombé en pleine mer sur la terre la plus proche. Un tirage aléatoire pur, lui, rejetait un
 * candidat sur trois pour cause d'océan et n'avait aucun moyen de se rattraper.
 *
 * <p><strong>Le piège qui rendait la version précédente inopérante</strong>, et qu'il ne faut pas
 * réintroduire : {@code World#getTopY} <em>ne génère pas</em> le chunk. Pour un chunk non chargé il
 * rend {@code getBottomY()}, sans le dire. Chaque candidat en terrain vierge — c'est-à-dire tous —
 * était donc rejeté comme un puits de vide, et la recherche échouait systématiquement. Toute lecture
 * d'altitude passe désormais par {@link #surfaceAt}, qui charge le chunk et lit son heightmap.
 *
 * <p><strong>Un seul point d'arrivée pour tout le monde</strong>, à quelques blocs de dispersion
 * près. Éparpiller les équipes serait plus spectaculaire mais fausserait la manche : deux biomes
 * différents ne donnent pas accès aux mêmes objectifs, et le bingo se jouerait au tirage de la
 * destination plutôt qu'à la carte.
 *
 * <p>À noter, puisque {@link #relocateAll} déplace aussi le spawn du monde : les distances de la
 * manche suivante se comptent depuis la <em>zone précédente</em>, pas depuis le spawn d'origine. Les
 * manches s'éloignent donc en chaîne, ce qui va dans le sens recherché.
 */
public final class BingoTeleport {

	/**
	 * Biomes acceptables pour un départ.
	 *
	 * <p>Trois exclusions et pas une liste blanche : tout ce qui n'est pas de l'eau libre fait un
	 * point de départ jouable, et énumérer les biomes terrestres obligerait à maintenir la liste à
	 * chaque version. Un biome souterrain peut encore passer au travers — la vérification de surface
	 * de {@link #surfaceAt} est l'arbitre final et rejettera l'océan qui le surplombe.
	 */
	private static final Predicate<RegistryEntry<Biome>> HABITABLE = biome ->
			!biome.isIn(BiomeTags.IS_OCEAN)
					&& !biome.isIn(BiomeTags.IS_DEEP_OCEAN)
					&& !biome.isIn(BiomeTags.IS_RIVER);

	/**
	 * Anneaux successifs, en multiples de la distance configurée.
	 *
	 * <p>Élargir plutôt qu'échouer : au bout de quelques dizaines de manches, l'anneau nominal est
	 * saturé de zones déjà visitées et aucun candidat ne passe le test de séparation. Une manche qui
	 * refuse de téléporter parce que l'historique est trop rempli se lirait comme un bug.
	 */
	private static final double[] RINGS = {1.0, 2.0, 4.0};

	/** Tirages d'angle et de distance par anneau. Bon marché : ni chunk ni disque touchés. */
	private static final int ATTEMPTS_PER_RING = 12;

	/**
	 * Candidats dont on charge réellement le chunk, par anneau.
	 *
	 * <p>C'est le seul travail lourd de la sélection, et le plafonner est ce qui empêche un monde
	 * hostile de figer le serveur le temps de générer trois douzaines de chunks. Le plafond est bas
	 * parce que la recherche de biome a déjà écarté l'eau : un sondage échoue rarement.
	 */
	private static final int SURFACE_PROBES_PER_RING = 4;

	/**
	 * Rayon de recherche autour du point tiré, et pas de balayage.
	 *
	 * <p>2 km suffisent à retomber sur la terre ferme depuis presque n'importe quel point d'un océan,
	 * et le pas de 64 blocs correspond à la résolution utile de la source de biomes — plus fin ne
	 * trouverait rien de plus et multiplierait les échantillons.
	 */
	private static final int BIOME_SEARCH_RADIUS = 2048;
	private static final int BIOME_HORIZONTAL_STEP = 64;
	private static final int BIOME_VERTICAL_STEP = 64;

	/** Rayon de « déjà vu » autour d'une zone tirée ou d'un joueur, en blocs. */
	private static final int SEPARATION = 512;

	/** Dispersion autour du point d'arrivée : de quoi ne pas s'empiler, pas de quoi se perdre. */
	private static final int SPREAD = 8;

	private BingoTeleport() {
	}

	/**
	 * Déplace tous les joueurs connectés vers une zone fraîche de l'overworld.
	 *
	 * @param avoided zones à fuir : celles des manches précédentes, le spawn, et les joueurs
	 * @return le point d'arrivée retenu, vide si aucun candidat n'a passé les critères
	 */
	public static Optional<BlockPos> relocateAll(BingoGame game, List<BlockPos> avoided) {
		ServerWorld world = game.server().getOverworld();
		Optional<BlockPos> zone = pickZone(world, avoided);
		if (zone.isEmpty()) {
			BingoConstants.LOGGER.warn(
					"Aucune zone de départ trouvée entre {} et {} blocs du spawn (×4 au plus large) —"
							+ " téléportation ignorée",
					BingoServerConfig.teleportMinDistance, BingoServerConfig.teleportMaxDistance);
			return Optional.empty();
		}

		BlockPos anchor = zone.get();

		// Spawn du monde déplacé sur la zone, en plus du point de réapparition individuel posé plus
		// bas. Les deux ne couvrent pas les mêmes cas : le spawn du monde sert aux joueurs qui se
		// connectent <em>après</em> le lancement, et le point individuel forcé écrase un lit où un
		// joueur aurait dormi la manche précédente — un lit gagne toujours contre le spawn du monde.
		world.setSpawnPos(anchor, 0.0f);

		Random random = world.getRandom();
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			int x = anchor.getX() + random.nextBetween(-SPREAD, SPREAD);
			int z = anchor.getZ() + random.nextBetween(-SPREAD, SPREAD);

			// Repli sur l'ancre elle-même : la dispersion peut franchir la limite du chunk, et le
			// voisin peut tomber dans l'eau. Mieux vaut deux joueurs au même bloc qu'un joueur à l'eau.
			BlockPos landing = surfaceAt(world, x, z).orElse(anchor);

			// Chute et vitesse remises à zéro : un joueur téléporté en pleine chute garde sa
			// fallDistance et meurt à l'atterrissage, ce qui est une drôle de façon de commencer.
			player.fallDistance = 0.0f;
			player.setVelocity(Vec3d.ZERO);
			player.teleport(world, landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5,
					player.getYaw(), player.getPitch());

			// Sans ce point de réapparition, la première mort renverrait le joueur au spawn du monde,
			// à plusieurs kilomètres : la téléportation serait annulée par le premier zombie.
			player.setSpawnPoint(world.getRegistryKey(), anchor, 0.0f, true, false);

			player.sendMessage(Text.translatable(BingoConstants.key("message.teleported"),
					anchor.getX(), anchor.getZ()).formatted(Formatting.AQUA), false);
		}

		BingoConstants.LOGGER.info(
				"Zone de départ : {} / {} — spawn du monde déplacé, {} joueur(s) téléporté(s)",
				anchor.getX(), anchor.getZ(), game.server().getPlayerManager().getCurrentPlayerCount());
		return zone;
	}

	private static Optional<BlockPos> pickZone(ServerWorld world, List<BlockPos> avoided) {
		for (double ring : RINGS) {
			Optional<BlockPos> found = pickInRing(world, avoided, ring);
			if (found.isPresent()) {
				return found;
			}
			BingoConstants.LOGGER.debug("Anneau ×{} sans zone exploitable — élargissement", ring);
		}
		return Optional.empty();
	}

	private static Optional<BlockPos> pickInRing(ServerWorld world, List<BlockPos> avoided, double ring) {
		BlockPos spawn = world.getSpawnPos();
		Random random = world.getRandom();

		// Le max est forcé au-dessus du min : les deux clés sont réglables séparément, et un opérateur
		// qui pose max < min obtiendrait sinon un intervalle négatif et des distances aberrantes.
		double min = Math.max(0.0, BingoServerConfig.teleportMinDistance) * ring;
		double max = Math.max(min + 1.0, BingoServerConfig.teleportMaxDistance * ring);

		int probes = 0;
		for (int attempt = 0; attempt < ATTEMPTS_PER_RING && probes < SURFACE_PROBES_PER_RING; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double distance = min + random.nextDouble() * (max - min);
			int x = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
			int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);
			if (!isAcceptable(world, spawn, x, z, min, avoided)) {
				continue;
			}

			// Aucun chunk généré ici : la recherche travaille sur le bruit de la source de biomes.
			Pair<BlockPos, RegistryEntry<Biome>> located = world.locateBiome(
					HABITABLE, new BlockPos(x, world.getSeaLevel(), z),
					BIOME_SEARCH_RADIUS, BIOME_HORIZONTAL_STEP, BIOME_VERTICAL_STEP);
			if (located == null) {
				continue;
			}

			// Revalidé après recentrage : la recherche peut ramener un point à deux kilomètres de là,
			// donc éventuellement trop près du spawn, d'un joueur ou d'une zone déjà jouée.
			BlockPos land = located.getFirst();
			if (!isAcceptable(world, spawn, land.getX(), land.getZ(), min, avoided)) {
				continue;
			}

			probes++;
			Optional<BlockPos> surface = surfaceAt(world, land.getX(), land.getZ());
			if (surface.isPresent()) {
				BingoConstants.LOGGER.debug("Zone retenue dans le biome {} après {} essai(s)",
						biomeName(located.getSecond()), attempt + 1);
				return surface;
			}
		}
		return Optional.empty();
	}

	/** Border, distance minimale au spawn, et éloignement de tout ce qui est déjà connu. */
	private static boolean isAcceptable(ServerWorld world, BlockPos spawn, int x, int z,
	                                   double minDistance, List<BlockPos> avoided) {
		if (!world.getWorldBorder().contains(new BlockPos(x, spawn.getY(), z))) {
			return false;
		}
		if (distanceSq(x, z, spawn.getX(), spawn.getZ()) < minDistance * minDistance) {
			return false;
		}
		return avoided.stream().noneMatch(other ->
				distanceSq(x, z, other.getX(), other.getZ()) < (double) SEPARATION * SEPARATION);
	}

	/**
	 * Altitude du sol en {@code x, z}, chunk généré au besoin.
	 *
	 * <p><strong>Ne pas remplacer par {@code world.getTopY(...)}</strong> : celui-ci teste
	 * {@code isChunkLoaded} et rend {@code getBottomY()} quand le chunk est absent, silencieusement.
	 * En terrain vierge — le cas normal ici — il rend donc toujours le fond du monde, ce qui faisait
	 * échouer 100 % des recherches. Lire le heightmap du chunk une fois chargé est la seule forme qui
	 * dise la vérité.
	 *
	 * @return vide si la colonne est vide (puits de vide) ou si le sol est un fluide — océan, lac,
	 *         lave. Le heightmap {@code MOTION_BLOCKING} compte les fluides : sans ce test, une équipe
	 *         entière atterrirait à la surface de l'eau.
	 */
	private static Optional<BlockPos> surfaceAt(ServerWorld world, int x, int z) {
		WorldChunk chunk = world.getChunk(x >> 4, z >> 4);
		int y = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
		if (y <= world.getBottomY() + 1) {
			return Optional.empty();
		}
		BlockPos pos = new BlockPos(x, y, z);
		return chunk.getBlockState(pos.down()).getFluidState().isEmpty()
				? Optional.of(pos)
				: Optional.empty();
	}

	private static String biomeName(RegistryEntry<Biome> biome) {
		return biome.getKey().map(key -> key.getValue().toString()).orElse("?");
	}

	/**
	 * Distance horizontale au carré, en {@code double} : un monde va à ±30 000 000 blocs, où le carré
	 * d'un écart déborde largement un {@code int}.
	 */
	private static double distanceSq(int x, int z, int otherX, int otherZ) {
		double dx = (double) x - otherX;
		double dz = (double) z - otherZ;
		return dx * dx + dz * dz;
	}
}
