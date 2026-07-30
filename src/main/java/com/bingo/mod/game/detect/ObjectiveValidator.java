package com.bingo.mod.game.detect;

import com.bingo.mod.board.BingoBoard;
import com.bingo.mod.game.BingoGame;
import com.bingo.mod.game.phase.GamePhase;
import com.bingo.mod.game.team.BingoTeam;
import com.bingo.mod.game.team.TeamPendingIndex;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.objective.condition.EntityMatcher;
import com.bingo.mod.objective.type.DeathTarget;
import com.bingo.mod.objective.type.FindTarget;
import com.bingo.mod.objective.type.KillMobTarget;
import com.bingo.mod.objective.type.ObjectiveType;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Le chemin critique de validation d'un objectif (`docs/06` §6).
 *
 * <pre>
 * événement de jeu
 *   1. phase == RUNNING ?                     sinon → abandon
 *   2. le joueur appartient-il à une équipe ?  sinon → abandon
 *   3. parcourir les cases NON validées du type correspondant   ← point chaud
 *   4. l'objectif matche-t-il l'événement ?
 *   5..8 → délégué à {@link BingoGame#applyProgress}
 * </pre>
 *
 * <p>L'étape 3 ne parcourt <strong>jamais</strong> les 25 objectifs : elle interroge les index
 * inversés de l'équipe ({@link TeamPendingIndex}). Sur {@code FIND}, scanné 2 fois par seconde
 * pour chaque joueur, la différence n'est pas cosmétique.
 *
 * <p>Les étapes 5 à 8 — avancement, victoire, paquets, annonces — sont volontairement hors de
 * cette classe : il ne doit exister qu'<em>un</em> endroit où une case se coche, sinon la
 * détection de victoire finira par être oubliée sur un chemin.
 */
public final class ObjectiveValidator {

	private ObjectiveValidator() {
	}

	/**
	 * Résultat des étapes 1 et 2 : la partie, l'équipe du joueur, et le joueur lui-même.
	 *
	 * <p>Le joueur est retenu jusqu'au bout de la chaîne parce que c'est lui qui sera crédité des
	 * points individuels de la case ({@link BingoGame#applyProgress}) : le remplacer par son équipe
	 * dès l'étape 2, comme le faisait la version précédente, rendait l'auteur de la validation
	 * définitivement irrécupérable en aval.
	 */
	private record Context(BingoGame game, BingoTeam team, ServerPlayerEntity player) {

		TeamPendingIndex pending() {
			return team.pending();
		}

		Objective tile(int index) {
			return game.tiles().get(index);
		}
	}

	/**
	 * Étapes 1 et 2 du chemin critique.
	 *
	 * <p>Court-circuit systématique en tête de chaque détecteur : hors manche, un craft ou une
	 * mort ne doit pas coûter plus qu'une comparaison d'enum.
	 */
	private static Optional<Context> gate(@Nullable ServerPlayerEntity player) {
		if (player == null) {
			return Optional.empty();
		}
		BingoGame game = BingoGame.getOrNull();
		if (game == null || !game.phase().areObjectivesValidated() || !game.hasCard()) {
			return Optional.empty();
		}
		return game.teams().of(player.getUuid()).map(team -> new Context(game, team, player));
	}

	// ── CRAFT (`docs/01` §4.1) ────────────────────────────────────────────────

	/**
	 * Le joueur a fabriqué {@code amount} exemplaires de {@code stack}.
	 *
	 * <p>{@code amount} et non 1 : le shift-clic sur un résultat de craft produit N items en un
	 * seul événement, et l'oublier ferait d'un objectif « crafter 8 torches » une case impossible
	 * à valider autrement qu'en huit gestes séparés (piège de `docs/01` §4.1).
	 */
	public static void onCraft(ServerPlayerEntity player, ItemStack stack, int amount) {
		if (stack.isEmpty() || amount <= 0) {
			return;
		}
		gate(player).ifPresent(context -> {
			TeamPendingIndex pending = context.pending();
			for (int index : pending.craftByItem(stack.getItem())) {
				increment(context, index, amount);
			}
			for (int index : pending.craftTagged()) {
				if (context.tile(index).target() instanceof com.bingo.mod.objective.type.CraftTarget craft
						&& craft.item().matches(stack)) {
					increment(context, index, amount);
				}
			}
		});
	}

	// ── KILL_MOB (`docs/01` §4.3) ─────────────────────────────────────────────

	/** Une entité vient de mourir : crédite le joueur responsable, s'il y en a un. */
	public static void onEntityKilled(LivingEntity victim, DamageSource source) {
		ServerPlayerEntity killer = resolveKiller(victim, source);
		if (killer == null) {
			return;
		}
		gate(killer).ifPresent(context -> {
			TeamPendingIndex pending = context.pending();
			for (int index : pending.killByEntity(victim.getType())) {
				if (matchesKill(context.tile(index), killer, victim)) {
					increment(context, index, 1);
				}
			}
			for (int index : pending.killTagged()) {
				Objective objective = context.tile(index);
				if (objective.target() instanceof KillMobTarget target
						&& target.entity() instanceof EntityMatcher.OfTag
						&& target.entity().matches(victim)
						&& matchesKill(objective, killer, victim)) {
					increment(context, index, 1);
				}
			}
		});
	}

	/**
	 * Qui reçoit le crédit de la mise à mort (`docs/01` §4.3).
	 *
	 * <p>D'abord l'attaquant de la {@link DamageSource} : pour un projectile, c'est déjà le
	 * <em>propriétaire</em>, ce qui règle le cas du tir à l'arc. À défaut, le dernier joueur ayant
	 * frappé la victime — {@code getPrimeAdversary} expire au bout de 100 ticks, soit exactement
	 * la fenêtre de 5 s exigée pour les chutes et les bains de lave provoqués.
	 *
	 * <p>Une mort sans aucun des deux (noyade, feu de camp) ne crédite personne, comme demandé.
	 */
	private static @Nullable ServerPlayerEntity resolveKiller(LivingEntity victim, DamageSource source) {
		if (source.getAttacker() instanceof ServerPlayerEntity direct) {
			return direct;
		}
		if (victim.getPrimeAdversary() instanceof ServerPlayerEntity recent) {
			return recent;
		}
		return null;
	}

	private static boolean matchesKill(Objective objective, ServerPlayerEntity killer, LivingEntity victim) {
		if (!(objective.target() instanceof KillMobTarget target)) {
			return false;
		}
		if (target.requireWeapon().isPresent()
				&& !killer.getMainHandStack().isOf(target.requireWeapon().get())) {
			return false;
		}
		// `docs/01` §4.3 nomme ce champ max_distance tout en le commentant « distance minimale ».
		// On suit le nom, qui est ce qu'un auteur de datapack lit : la mise à mort doit se faire à
		// AU PLUS cette distance. Aucun objectif livré ne l'utilise.
		return target.maxDistance().isEmpty()
				|| killer.distanceTo(victim) <= target.maxDistance().get();
	}

	// ── DEATH (`docs/01` §4.4) ────────────────────────────────────────────────

	/** Le joueur est mort. Le crédit se calcule <strong>avant</strong> le respawn. */
	public static void onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
		gate(player).ifPresent(context -> {
			for (int index : context.pending().byType(ObjectiveType.DEATH)) {
				if (matchesDeath(context.tile(index), source)) {
					increment(context, index, 1);
				}
			}
		});
	}

	private static boolean matchesDeath(Objective objective, DamageSource source) {
		if (!(objective.target() instanceof DeathTarget target)) {
			return false;
		}
		if (target.anyDeath()) {
			return true;
		}
		// Les types de dégâts sont un registre dynamique depuis 1.19.4 : l'objectif ne peut porter
		// qu'un Identifier brut, résolu ici seulement (voir DeathTarget). Corollaire assumé : un
		// damage_type inexistant ne se détecte pas au chargement, il ne matche simplement jamais.
		if (target.damageType().isPresent()) {
			RegistryKey<DamageType> key = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, target.damageType().get());
			return source.isOf(key);
		}
		if (target.damageTag().isPresent()) {
			return source.isIn(TagKey.of(RegistryKeys.DAMAGE_TYPE, target.damageTag().get()));
		}
		return false;
	}

	// ── ACTION (`docs/01` §4.5) ───────────────────────────────────────────────

	/** Un déclencheur s'est produit pour ce joueur. */
	public static void onAction(ServerPlayerEntity player, ActionEvent event) {
		gate(player).ifPresent(context -> {
			for (int index : context.pending().byType(ObjectiveType.ACTION)) {
				if (ActionTriggers.matches(context.tile(index), event)) {
					increment(context, index, 1);
				}
			}
		});
	}

	// ── FIND : scan périodique (`docs/01` §4.2) ────────────────────────────────

	/**
	 * Budget indicatif du scan complet, en microsecondes (tâche 5.4).
	 *
	 * <p>Le dépasser n'a aucune conséquence fonctionnelle : c'est un simple seuil de journalisation
	 * DEBUG qui rend la recette « profiler avec 8 joueurs » exécutable sans profileur externe. À 8
	 * joueurs le scan est de l'ordre de quelques dizaines de µs (41 lookups de hash par joueur, deux
	 * fois par seconde) ; un dépassement franc signale un datapack pathologique — par exemple une
	 * dizaine d'objectifs FIND par tag, dont le prédicat s'évalue à chaque slot.
	 */
	private static final long SCAN_BUDGET_MICROS = 2000L;

	/**
	 * Scan des inventaires, appelé toutes les 10 ticks depuis {@link BingoGame#tick()}.
	 *
	 * <p>Une seule passe sur l'inventaire par joueur, avec accumulation par case dans un tableau
	 * primitif : la boucle naïve — pour chaque objectif, compter l'inventaire — serait 25 × 41
	 * comparaisons quatre fois par seconde et par joueur. Ici c'est 41 lookups de hash.
	 *
	 * <p><strong>Avancement à cliquet.</strong> L'avancement affiché ne redescend jamais, alors
	 * que la complétion exige le compte <em>courant</em>. Sans ce cliquet, ramasser et jeter de la
	 * terre émettrait un {@code tile_update} à chaque fluctuation, ce que `docs/06` §4 interdit ;
	 * et faire redescendre l'affichage n'apporterait rien au joueur, qui voit son inventaire.
	 *
	 * <p>Le bloc de mesure (tâche 5.4) n'ajoute que deux lectures d'horloge par scan et ne journalise
	 * qu'au-delà de {@link #SCAN_BUDGET_MICROS} : coût nul en régime normal, trace utile sinon.
	 */
	public static void scanInventories(BingoGame game) {
		long startNanos = System.nanoTime();
		int scanned = 0;

		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			if (game.phase() != GamePhase.RUNNING) {
				return;
			}
			Optional<BingoTeam> found = game.teams().of(player.getUuid());
			if (found.isEmpty()) {
				continue;
			}
			BingoTeam team = found.get();
			TeamPendingIndex pending = team.pending();
			if (!pending.hasFindTargets()) {
				continue;
			}
			scanInventory(game, team, pending, player);
			scanned++;
		}

		long elapsedMicros = (System.nanoTime() - startNanos) / 1000L;
		if (elapsedMicros > SCAN_BUDGET_MICROS && scanned > 0) {
			BingoConstants.LOGGER.debug("Scan FIND : {} µs pour {} joueur(s) scanné(s) — au-dessus du budget de {} µs",
					elapsedMicros, scanned, SCAN_BUDGET_MICROS);
		}
	}

	private static void scanInventory(BingoGame game,
	                                  BingoTeam team,
	                                  TeamPendingIndex pending,
	                                  ServerPlayerEntity player) {
		// Accumulateur primitif indexé par case (0..24), et non une HashMap<Integer,Integer> :
		// le scan tourne 2 fois par seconde pour chaque joueur (tâche 5.4). L'ancienne map imposait
		// une allocation et de l'autoboxing à chaque passe et par joueur — ici c'est un seul tableau
		// de 25 int, sans boxing ni hachage. Les index de case sont exactement les bornes du tableau,
		// donc aucune structure « touched » n'est nécessaire : une case à 0 ne déclenche rien.
		int[] counts = new int[BingoBoard.TILE_COUNT];

		// size() est hissé hors de la condition : le réévaluer à chaque tour parcourait la même
		// somme d'inventaires 41 fois. Il couvre les 41 emplacements — sac, armure et main gauche —
		// soit « n'importe quel slot » au sens de `docs/01` §4.2.
		int slotCount = player.getInventory().size();
		for (int slot = 0; slot < slotCount; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			for (int index : pending.findByItem(stack.getItem())) {
				counts[index] += stack.getCount();
			}
			for (int index : pending.findTagged()) {
				if (game.tiles().get(index).target() instanceof FindTarget target
						&& target.item().matches(stack)) {
					counts[index] += stack.getCount();
				}
			}
		}

		// Seules les cases FIND reçoivent un incrément (findByItem / findTagged ne rendent que
		// celles-là), donc un compteur non nul est toujours une case FIND à faire avancer.
		//
		// Le crédit des points individuels va au joueur en cours de scan : c'est lui qui porte les
		// items au moment où la case se coche. Un coffre d'équipe rempli à deux crédite donc celui qui
		// tient le lot complet — la seule lecture que le scan puisse justifier, puisqu'il ne compte
		// que des inventaires.
		for (int index = 0; index < counts.length; index++) {
			if (counts[index] > 0) {
				game.applyProgress(team, index, Math.max(team.progress(index), counts[index]), player);
			}
		}
	}

	// ── ACTION périodique : altitude, expérience, monture (`docs/01` §4.5) ─────

	/**
	 * Échantillonne l'état continu des joueurs, toutes les 20 ticks.
	 *
	 * <p>Séparé du scan d'inventaire parce que le seuil de throttle diffère (20 ticks contre 10) et
	 * qu'aucune de ces grandeurs ne change deux fois par seconde.
	 *
	 * <p>Limite assumée de l'échantillonnage : un seuil franchi puis reperdu en moins d'une seconde
	 * passe inaperçu. C'était déjà vrai de l'altitude, et un joueur qui atteint le niveau 30 pour le
	 * dépenser dans la même seconde n'a pas vraiment « tenu » l'objectif. Un hook sur chaque gain
	 * d'XP coûterait un test par orbe ramassé pour ce seul cas limite.
	 */
	public static void scanPeriodicActions(BingoGame game) {
		for (ServerPlayerEntity player : game.server().getPlayerManager().getPlayerList()) {
			if (game.phase() != GamePhase.RUNNING) {
				return;
			}
			onAction(player, new ActionEvent.YLevelReached(player.getY()));
			onAction(player, new ActionEvent.XpLevelReached(player.experienceLevel));

			// HorseEntity et non AbstractHorseEntity : ânes et mulets portent un coffre, pas une
			// armure, et getArmorType() n'existe que sur le cheval.
			if (player.getVehicle() instanceof HorseEntity horse
					&& horse.isTame() && horse.isSaddled()) {
				ItemStack armor = horse.getArmorType();
				if (!armor.isEmpty()) {
					onAction(player, new ActionEvent.RodeEquippedHorse(
							Registries.ITEM.getId(armor.getItem())));
				}
			}
		}
	}

	// ── Étapes 5 à 8 ──────────────────────────────────────────────────────────

	/**
	 * Ajoute {@code amount} à l'avancement d'une case.
	 *
	 * <p>Itérer sur les listes de l'index tout en appelant cette méthode est sûr : une complétion
	 * <em>remplace</em> l'index de l'équipe par un nouvel objet ({@link TeamPendingIndex} est
	 * immuable), sans toucher aux listes en cours de parcours.
	 */
	private static void increment(Context context, int index, int amount) {
		// Rappel du test de phase à l'intérieur de la boucle : une complétion peut faire basculer
		// la partie en FINISHED, et les index restants de la même itération ne doivent plus rien
		// valider. Le test de {@link #gate} n'a lieu qu'une fois, avant la boucle.
		if (!context.game().phase().areObjectivesValidated()) {
			return;
		}
		context.game().applyProgress(context.team(), index,
				context.team().progress(index) + amount, context.player());
	}
}
