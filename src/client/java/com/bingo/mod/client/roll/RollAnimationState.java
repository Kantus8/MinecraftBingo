package com.bingo.mod.client.roll;

import com.bingo.mod.client.BingoClientState;
import com.bingo.mod.client.hud.BingoBoardLayout;
import com.bingo.mod.network.payload.RollStartPayload;
import com.bingo.mod.registry.BingoItemTags;
import com.bingo.mod.util.BingoConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * L'animation « Slot Machine », côté client (`docs/04` §2 et §6, tâches 4.2 à 4.5).
 *
 * <p><strong>Tout se dérive de {@code elapsed}</strong> (`docs/04` §6). Il n'y a pas un seul état
 * mutable par case : l'icône affichée, le nombre de lignes verrouillées, l'échelle du punch et
 * l'alpha du flash sont tous des fonctions pures du temps écoulé. Une animation dérivable du temps
 * est une animation qui ne peut pas se désynchroniser — ni entre deux clients, ni entre deux frames
 * après un pic de lag.
 *
 * <p>Les <em>sons</em> font exception : ils ne sont pas dérivables, il faut les déclencher une fois
 * et une seule. D'où {@link #nextSoundIndex}, un simple curseur sur une liste d'événements
 * pré-calculée — une frame sautée ne saute pas un son, elle en joue deux à la suite.
 *
 * <p>Base de temps : {@link Util#getMeasuringTimeMs()} et non un compteur de ticks. À 144 fps
 * l'animation doit rester fluide, et un lag serveur ne doit pas la faire saccader.
 */
public final class RollAnimationState {

	/**
	 * Le tag de leurres de `docs/04` §3 — 69 items iconiques.
	 *
	 * <p>La clé vient de {@link BingoItemTags} : le datagen (tâche 5.1) génère le JSON à partir de
	 * la même constante, donc le tag lu ici et le tag écrit là-bas ne peuvent pas diverger.
	 */
	private static final TagKey<Item> DECOY_TAG = BingoItemTags.ROLL_DECOYS;

	/** Durée de référence de la timeline de `docs/04` §2 : tous les seuils y sont exprimés. */
	private static final float REFERENCE_DURATION_MS = 3000f;

	/** Fin du défilement rapide, sur la base de référence. */
	private static final float PHASE_A_END_MS = 2000f;

	/** Verrouillage des 5 lignes, écarts croissants (`docs/04` §2.2). */
	private static final float[] LOCK_TIMES_MS = {2100f, 2290f, 2500f, 2730f, 2980f};

	/** Pitch du click à chaque verrou : il <em>monte</em> alors que le rythme ralentit. */
	private static final float[] LOCK_PITCHES = {1.0f, 1.2f, 1.4f, 1.6f, 1.8f};

	private static final float SWAP_INTERVAL_FAST_MS = 100f;
	private static final float SWAP_INTERVAL_SLOW_MS = 320f;

	/** Durée du flash blanc d'un verrou, et de son punch d'échelle (`docs/04` §2.2). */
	private static final float FLASH_MS = 120f;
	private static final float PUNCH_MS = 200f;
	private static final float PUNCH_SCALE = 1.35f;

	private static final int FLASH_ALPHA_MAX = 0xB0;

	/** 30 % de vraies icônes de la carte dans le défilement (`docs/04` §3). */
	private static final float REAL_ICON_RATIO = 0.30f;

	/**
	 * Un événement de la timeline, joué une fois quand {@code elapsed} le dépasse.
	 *
	 * @param finale marque le seul événement qui fait autre chose que du son : la gerbe d'étincelles
	 *               du HUD, qui doit partir au même instant que le son de clôture du tirage
	 */
	private record Cue(float atMs, SoundEvent sound, float pitch, float volume, boolean finale) {

		Cue(float atMs, SoundEvent sound, float pitch, float volume) {
			this(atMs, sound, pitch, volume, false);
		}
	}

	private static boolean active;
	private static long startMs;
	private static float durationMs = REFERENCE_DURATION_MS;

	/** Facteur d'échelle de la timeline quand {@code roll_ticks} n'est pas 60 (`docs/04` §1). */
	private static float timeScale = 1.0f;

	private static long seed;
	private static List<Identifier> tiles = List.of();
	private static List<Item> decoys = List.of();

	/** Instants de swap pré-calculés : l'ease-out de la phase B n'a pas d'inverse simple. */
	private static float[] swapTimes = new float[0];

	private static List<Cue> cues = List.of();
	private static int nextSoundIndex;

	private RollAnimationState() {
	}

	// ── Cycle de vie ──────────────────────────────────────────────────────────

	/** Démarre l'animation à la réception de {@code bingo:roll_start}. */
	public static void start(RollStartPayload payload) {
		tiles = payload.tiles();
		seed = payload.seed();
		durationMs = Math.max(1, payload.durationMs());
		timeScale = durationMs / REFERENCE_DURATION_MS;
		decoys = resolveDecoys();
		swapTimes = buildSwapTimes();
		cues = buildCues();
		nextSoundIndex = 0;
		startMs = Util.getMeasuringTimeMs() - initialSkew(payload);
		active = true;
	}

	/**
	 * Combien de l'animation s'est déjà écoulé au moment où le paquet arrive.
	 *
	 * <p>{@code startTimeMs} est une horloge <em>serveur</em>. En solo et en LAN c'est la même que
	 * celle du client, et la comparaison absorbe exactement la latence. Sur un serveur distant les
	 * deux horloges peuvent être arbitrairement décalées — d'où le test d'encadrement : une valeur
	 * qui ne tombe pas dans la fenêtre de l'animation est un décalage d'horloge, pas un retard
	 * réseau, et on repart de zéro. Se tromper coûte au pire une animation qui démarre au début.
	 */
	private static long initialSkew(RollStartPayload payload) {
		long serverElapsed = System.currentTimeMillis() - payload.startTimeMs();
		return serverElapsed >= 0 && serverElapsed < (long) durationMs ? serverElapsed : 0L;
	}

	/** Coupe l'animation — fin de la phase, changement de monde, {@code /bingo reset}. */
	public static void stop() {
		active = false;
		tiles = List.of();
		swapTimes = new float[0];
		cues = List.of();
	}

	/**
	 * L'animation est-elle en cours ? Faux dès que {@code elapsed} dépasse la durée.
	 *
	 * <p>Purement consultative : c'est {@link #tick()} qui éteint réellement l'animation, une fois
	 * les derniers sons joués. Éteindre ici — au premier appel du rendu passé {@code durationMs} —
	 * avalerait le son final et les étincelles, puisque le rendu arrive avant le tick.
	 */
	public static boolean isActive() {
		return active && elapsed() <= durationMs;
	}

	public static long elapsed() {
		return Util.getMeasuringTimeMs() - startMs;
	}

	// ── Sons (`docs/04` §7) ────────────────────────────────────────────────────

	/**
	 * Déclenche les sons dus, à appeler à chaque tick client.
	 *
	 * <p>Une {@code while} et non un {@code if} : à 20 Hz un tick couvre 50 ms, mais un pic de lag
	 * peut en couvrir 300 et faire passer trois seuils d'un coup. Le curseur garantit un son par
	 * seuil, jamais zéro, jamais deux.
	 */
	public static void tick() {
		if (!active) {
			return;
		}
		long elapsed = elapsed();
		while (nextSoundIndex < cues.size() && elapsed >= cues.get(nextSoundIndex).atMs()) {
			Cue cue = cues.get(nextSoundIndex++);
			MinecraftClient.getInstance().getSoundManager().play(
					PositionedSoundInstance.master(cue.sound(), cue.pitch(), cue.volume()));
			if (cue.finale()) {
				RollSparks.emit(
						BingoBoardLayout.centerX(),
						BingoBoardLayout.centerY(BingoClientState.revealOpponentProgress()),
						seed);
			}
		}

		// Extinction après la file de sons, jamais avant : la dernière cue tombe exactement à
		// durationMs, et un tick arrive rarement pile dessus.
		if (elapsed > durationMs) {
			stop();
		}
	}

	/**
	 * Les 26 sons de la timeline, calculés une fois au départ.
	 *
	 * <p>Phase A : un click à chaque swap, pitch 1.6 et <strong>volume 0.25</strong>. Ce volume
	 * n'est pas un détail — 10 sons par seconde pendant 2 s à volume plein sont agressifs au casque,
	 * et la moitié des joueurs sont en vocal (`docs/04` §2.1).
	 *
	 * <p>Phase B : un click par verrou seulement. Les lignes encore libres continuent de défiler
	 * mais en silence, sans quoi les 5 verrous se noieraient dans le bruit de fond.
	 */
	private static List<Cue> buildCues() {
		List<Cue> built = new ArrayList<>(LOCK_TIMES_MS.length + swapTimes.length + 1);
		float phaseAEnd = PHASE_A_END_MS * timeScale;

		for (float swapAt : swapTimes) {
			if (swapAt < phaseAEnd) {
				built.add(new Cue(swapAt, SoundEvents.BLOCK_COMPARATOR_CLICK, 1.6f, 0.25f));
			}
		}
		for (int row = 0; row < LOCK_TIMES_MS.length; row++) {
			built.add(new Cue(LOCK_TIMES_MS[row] * timeScale,
					SoundEvents.BLOCK_COMPARATOR_CLICK,
					LOCK_PITCHES[row],
					row == LOCK_TIMES_MS.length - 1 ? 0.8f : 0.7f));
		}
		// Écart avec `docs/04` §2.3, qui prévoit ici `ui.toast.challenge_complete` : ce son est
		// désormais celui de la victoire (BingoSounds.BINGO), et le réutiliser pour clore le tirage
		// annoncerait deux fois par manche un événement de nature différente. Le totem se lit comme
		// un basculement — la carte est verrouillée, la manche commence — et non comme une récompense.
		//
		// Second écart, sur le volume que `docs/04` §2.1 fixait à 1.0 : le totem est authoré à 1.0 et
		// c'est un son conçu pour percer un combat, pas pour clore une animation d'interface. À plein
		// volume il écrase les 5 clicks de verrouillage qui le précèdent. 0.2 le ramène sous les 0,6
		// du succès rare qu'il remplace — la clôture reste nette parce qu'elle arrive après un silence,
		// pas parce qu'elle est forte.
		//
		// L'ordre du record est (pitch, volume) : c'est le second nombre qu'on baisse ici, pas le premier.
		built.add(new Cue(durationMs, SoundEvents.ITEM_TOTEM_USE, 1.0f, 0.2f, true));

		built.sort((left, right) -> Float.compare(left.atMs(), right.atMs()));
		return List.copyOf(built);
	}

	// ── Verrouillage (`docs/04` §6) ────────────────────────────────────────────

	/** Nombre de lignes verrouillées, 0 → 5. */
	public static int lockedRows() {
		long elapsed = elapsed();
		int locked = 0;
		for (float at : LOCK_TIMES_MS) {
			if (elapsed >= at * timeScale) {
				locked++;
			}
		}
		return locked;
	}

	public static boolean isRowLocked(int row) {
		return row < lockedRows();
	}

	/**
	 * Échelle du « punch » d'une ligne : {@code 1.35 → 1.0} sur 200 ms, ease-out cubique.
	 *
	 * <p>{@code 1.0} avant le verrou comme longtemps après : la case ne bouge que pendant les 200 ms
	 * qui suivent son instant de verrouillage.
	 */
	public static float punchScale(int row) {
		float since = sinceLock(row);
		if (since < 0f || since >= PUNCH_MS) {
			return 1.0f;
		}
		float progress = since / PUNCH_MS;
		float eased = 1f - (1f - progress) * (1f - progress) * (1f - progress);
		return PUNCH_SCALE + (1.0f - PUNCH_SCALE) * eased;
	}

	/** Alpha du flash blanc d'une ligne : {@code 0xB0 → 0x00} sur 120 ms, linéaire. */
	public static int flashAlpha(int row) {
		float since = sinceLock(row);
		if (since < 0f || since >= FLASH_MS) {
			return 0;
		}
		return (int) (FLASH_ALPHA_MAX * (1f - since / FLASH_MS));
	}

	/** Millisecondes depuis le verrouillage d'une ligne, négatif si elle ne l'est pas encore. */
	private static float sinceLock(int row) {
		if (row < 0 || row >= LOCK_TIMES_MS.length) {
			return -1f;
		}
		return elapsed() - LOCK_TIMES_MS[row] * timeScale;
	}

	// ── Icônes défilantes (`docs/04` §3) ───────────────────────────────────────

	/**
	 * L'icône de leurre d'une case à l'instant courant.
	 *
	 * <p>70 % de leurres, 30 % de vraies icônes de la carte — mais jamais forcément la bonne pour
	 * cette case. Un pool 100 % leurres donne un défilement qui « sonne faux » au reveal ; mélanger
	 * de vraies icônes rend le tirage crédible sans rien révéler.
	 */
	public static ItemStack decoyStack(int index) {
		int swap = currentSwapIndex();
		long hash = mix(seed, swap, index);

		// Le bit de poids fort décide leurre / vraie icône, les suivants tirent l'élément : un seul
		// hash pour deux tirages indépendants.
		boolean useReal = (hash >>> 40 & 0xFFFF) < (long) (REAL_ICON_RATIO * 0x10000);
		if (useReal && !tiles.isEmpty()) {
			int pick = (int) Math.floorMod(hash >>> 8, tiles.size());
			ItemStack real = iconOf(tiles.get(pick));
			if (!real.isEmpty()) {
				return real;
			}
		}
		if (decoys.isEmpty()) {
			return new ItemStack(Items.PAPER);
		}
		return new ItemStack(decoys.get((int) Math.floorMod(hash >>> 24, decoys.size())));
	}

	/**
	 * Mélange déterministe (variante de splitmix64).
	 *
	 * <p>Écart assumé avec le pseudo-code de `docs/04` §3, qui consomme un {@code Random(seed)}
	 * séquentiellement : une séquence consommée n'est pas rejouable à partir du seul {@code elapsed},
	 * et §6 exige précisément que tout s'en dérive. Une fonction de hachage donne la même propriété
	 * — mêmes icônes sur tous les clients, puisque le seed est le même — sans état à rembobiner
	 * quand une frame est sautée.
	 */
	private static long mix(long seed, int swap, int cell) {
		long hash = seed * 0x9E3779B97F4A7C15L
				+ swap * 0x632BE59BD9B4E019L
				+ cell * 0xC2B2AE3D27D4EB4FL;
		hash ^= hash >>> 30;
		hash *= 0xBF58476D1CE4E5B9L;
		hash ^= hash >>> 27;
		hash *= 0x94D049BB133111EBL;
		hash ^= hash >>> 31;
		return hash;
	}

	private static ItemStack iconOf(Identifier objectiveId) {
		return BingoClientState.objective(objectiveId)
				.map(projection -> Registries.ITEM.get(projection.icon()))
				.filter(item -> item != Items.AIR)
				.map(ItemStack::new)
				.orElse(ItemStack.EMPTY);
	}

	// ── Cadence de défilement (`docs/04` §2.2) ─────────────────────────────────

	/** Index du swap courant, borné au dernier calculé. */
	private static int currentSwapIndex() {
		long elapsed = elapsed();
		int index = 0;
		while (index < swapTimes.length && swapTimes[index] <= elapsed) {
			index++;
		}
		return index;
	}

	/**
	 * Les instants de swap, calculés une fois plutôt que ré-intégrés à chaque frame.
	 *
	 * <p>L'intervalle est fixe en phase A puis suit un ease-out quadratique de 100 à 320 ms en
	 * phase B : la somme des intervalles n'a pas de forme fermée simple, donc on l'accumule ici, une
	 * fois, au lieu de rejouer la boucle 144 fois par seconde.
	 */
	private static float[] buildSwapTimes() {
		List<Float> times = new ArrayList<>();
		float at = 0f;
		// Garde-fou : sur une durée absurde, la boucle doit s'arrêter quoi qu'il arrive.
		while (at < durationMs && times.size() < 512) {
			times.add(at);
			at += swapIntervalAt(at);
		}
		float[] result = new float[times.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = times.get(i);
		}
		return result;
	}

	private static float swapIntervalAt(float at) {
		float phaseAEnd = PHASE_A_END_MS * timeScale;
		if (at < phaseAEnd) {
			return SWAP_INTERVAL_FAST_MS * timeScale;
		}
		float span = durationMs - phaseAEnd;
		float progress = span <= 0f ? 1f : Math.min(1f, (at - phaseAEnd) / span);
		float eased = 1f - (1f - progress) * (1f - progress);
		return (SWAP_INTERVAL_FAST_MS + (SWAP_INTERVAL_SLOW_MS - SWAP_INTERVAL_FAST_MS) * eased) * timeScale;
	}

	// ── Leurres ───────────────────────────────────────────────────────────────

	/**
	 * Résout {@code #bingo:roll_decoys}, synchronisé au client comme tout tag d'items.
	 *
	 * <p>Un tag vide n'est pas une erreur fatale : {@link #decoyStack} retombe sur du papier, et
	 * l'animation reste jouable. Le WARN suffit à diagnostiquer un datapack qui aurait écrasé le tag.
	 */
	private static List<Item> resolveDecoys() {
		List<Item> resolved = Registries.ITEM.getEntryList(DECOY_TAG)
				.map(entries -> entries.stream().map(RegistryEntry::value).toList())
				.orElse(List.of());
		if (resolved.isEmpty()) {
			BingoConstants.LOGGER.warn("Tag '{}' vide ou absent — défilement sans leurres",
					DECOY_TAG.id());
		}
		return resolved;
	}
}
