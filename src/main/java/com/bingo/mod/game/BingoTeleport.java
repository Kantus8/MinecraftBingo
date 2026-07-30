package com.bingo.mod.game;

import com.bingo.mod.config.BingoServerConfig;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

import java.util.List;
import java.util.Optional;

/**
 * Zone de départ vierge, tirée à chaque {@code /bingo start … teleport} (option de commande).
 *
 * <p><strong>Ce que « encore inexplorée » veut dire ici.</strong> Minecraft ne sait pas répondre à
 * « ce terrain a-t-il déjà été vu ? » : un fichier de région existe dès qu'un joueur a survolé la
 * zone, l'API de lecture des chunks non chargés est asynchrone et privée, et sonder 48 candidats sur
 * disque coûterait plus que le tirage entier. Le mod approxime donc par trois critères qui se
 * vérifient sans toucher au stockage :
 * <ul>
 *   <li>à {@code teleport_min_distance} au moins du spawn du monde ;</li>
 *   <li>à {@link #SEPARATION} blocs au moins de toute zone déjà tirée par une manche précédente
 *       — c'est ce que {@code BingoGame} persiste, et la seule raison pour laquelle un coin perdu
 *       aurait déjà été fouillé est précisément qu'un bingo y a été joué ;</li>
 *   <li>à {@link #SEPARATION} blocs au moins de la position actuelle de chaque joueur.</li>
 * </ul>
 *
 * <p><strong>Un seul point d'arrivée pour tout le monde</strong>, à quelques blocs de dispersion
 * près. Éparpiller les équipes serait plus spectaculaire mais fausserait la manche : deux biomes
 * différents ne donnent pas accès aux mêmes objectifs, et le bingo se jouerait au tirage de la
 * destination plutôt qu'à la carte.
 */
public final class BingoTeleport {

	/**
	 * Anneaux successifs, en multiples de la distance configurée.
	 *
	 * <p>Élargir plutôt qu'échouer : au bout de quelques dizaines de manches, l'anneau nominal est
	 * saturé de zones déjà visitées et aucun candidat ne passe le test de séparation. Une manche qui
	 * refuse de téléporter parce que l'historique est trop rempli se lirait comme un bug.
	 */
	private static final double[] RINGS = {1.0, 2.0, 4.0};

	/** Tirages d'angle et de distance par anneau. Bon marché : ni chunk ni disque touchés. */
	private static final int ATTEMPTS_PER_RING = 24;

	/**
	 * Candidats dont on résout réellement le sol, par anneau.
	 *
	 * <p>{@code getTopY} <strong>génère</strong> le chunk s'il n'existe pas — c'est le seul travail
	 * lourd de la sélection, et le plafonner est ce qui empêche un monde à mer infinie de figer le
	 * serveur pendant 72 générations de chunk.
	 */
	private static final int SURFACE_PROBES_PER_RING = 6;

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
					"Aucune zone de départ trouvée entre {} et {} blocs du spawn — téléportation ignorée",
					BingoServerConfig.teleportMinDistance, BingoServerConfig.teleportMaxDistance);
			return Optional.empty();
		}

		BlockPos anchor = zone.get();
		Random random = world.getRandom();
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			int x = anchor.getX() + random.nextBetween(-SPREAD, SPREAD);
			int z = anchor.getZ() + random.nextBetween(-SPREAD, SPREAD);
			int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);

			// Chute et vitesse remises à zéro : un joueur téléporté en pleine chute garde sa
			// fallDistance et meurt à l'atterrissage, ce qui est une drôle de façon de commencer.
			player.fallDistance = 0.0f;
			player.setVelocity(Vec3d.ZERO);
			player.teleport(world, x + 0.5, y, z + 0.5, player.getYaw(), player.getPitch());

			// Sans ce point de réapparition, la première mort renverrait le joueur au spawn du monde,
			// à plusieurs kilomètres : la téléportation serait annulée par le premier zombie.
			player.setSpawnPoint(world.getRegistryKey(), anchor, 0.0f, true, false);

			player.sendMessage(Text.translatable(BingoConstants.key("message.teleported"),
					anchor.getX(), anchor.getZ()).formatted(Formatting.AQUA), false);
		}

		BingoConstants.LOGGER.info("Zone de départ : {} / {} ({} joueur(s) téléporté(s))",
				anchor.getX(), anchor.getZ(), game.server().getPlayerManager().getCurrentPlayerCount());
		return zone;
	}

	private static Optional<BlockPos> pickZone(ServerWorld world, List<BlockPos> avoided) {
		for (double ring : RINGS) {
			Optional<BlockPos> found = pickInRing(world, avoided, ring);
			if (found.isPresent()) {
				return found;
			}
			BingoConstants.LOGGER.debug("Anneau ×{} saturé — élargissement", ring);
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

			// Tests bon marché d'abord, génération de chunk ensuite : l'ordre est ce qui rend 24 essais
			// par anneau acceptables.
			BlockPos flat = new BlockPos(x, spawn.getY(), z);
			if (!world.getWorldBorder().contains(flat)) {
				continue;
			}
			if (tooClose(x, z, avoided)) {
				continue;
			}

			probes++;
			BlockPos candidate = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			if (candidate.getY() <= world.getBottomY() + 1) {
				continue;
			}
			// Le heightmap MOTION_BLOCKING compte les fluides : au-dessus d'un océan ou d'un lac de lave
			// il rend la surface du liquide, et y déposer une équipe entière serait un noyade collective.
			if (!world.getFluidState(candidate.down()).isEmpty()) {
				continue;
			}
			return Optional.of(candidate);
		}
		return Optional.empty();
	}

	/**
	 * Distance horizontale seule, et en {@code long} : un monde va à ±30 000 000 blocs, où le carré
	 * d'un écart dépasse la capacité d'un {@code int}.
	 */
	private static boolean tooClose(int x, int z, List<BlockPos> avoided) {
		long limit = (long) SEPARATION * SEPARATION;
		for (BlockPos other : avoided) {
			long dx = x - (long) other.getX();
			long dz = z - (long) other.getZ();
			if (dx * dx + dz * dz < limit) {
				return true;
			}
		}
		return false;
	}
}
