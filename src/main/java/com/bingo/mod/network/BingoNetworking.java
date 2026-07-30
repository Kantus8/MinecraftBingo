package com.bingo.mod.network;

import com.bingo.mod.util.BingoConstants;
import net.minecraft.util.Identifier;

/**
 * Identifiants des canaux réseau (`docs/06` §3).
 *
 * <p>En 1.20.1, {@code CustomPayload} n'existe pas : un paquet est un {@link Identifier} de
 * canal et un {@code PacketByteBuf} écrit et lu à la main (`docs/06` en tête). Ces constantes
 * vivent dans {@code src/main} pour que le serveur qui émet et le client qui reçoit partagent
 * littéralement la même valeur — deux littéraux recopiés finiraient par diverger.
 *
 * <p><strong>Règle absolue de lecture</strong> : décoder le buf <em>dans le handler réseau</em>,
 * puis passer les objets décodés à {@code client.execute(...)}. Le buf est libéré au retour du
 * handler.
 */
public final class BingoNetworking {

	/**
	 * Plafond d'un {@code CustomPayloadS2CPacket} en 1.20.1 (`docs/06` §3.4).
	 *
	 * <p>Recopié ici plutôt que lu depuis la classe vanilla, où la constante n'est pas publique.
	 * Le dépassement n'est <strong>pas</strong> vérifié à l'écriture par Minecraft : c'est le
	 * client qui lève à la lecture, et il se fait déconnecter avec un message qui ne désigne rien.
	 * D'où le garde-fou de {@code BingoServerNetworking}, côté émission.
	 */
	public static final int MAX_PAYLOAD_SIZE = 1_048_576;

	// ── Serveur → client (`docs/06` §3.1) ─────────────────────────────────────

	/** Catalogue d'affichage des objectifs. Envoyé <strong>avant</strong> {@link #BOARD_SYNC}. */
	public static final Identifier OBJECTIVE_SYNC = BingoConstants.id("objective_sync");

	/** État complet de la carte et des équipes. */
	public static final Identifier BOARD_SYNC = BingoConstants.id("board_sync");

	/** Transition de phase. */
	public static final Identifier PHASE = BingoConstants.id("phase");

	/** Validation d'une case. */
	public static final Identifier TILE_UPDATE = BingoConstants.id("tile_update");

	/** Scores de toutes les équipes, après chaque {@link #TILE_UPDATE}. */
	public static final Identifier SCORE_UPDATE = BingoConstants.id("score_update");

	/** Fin de partie : raison, gagnants, combinaison, classement. */
	public static final Identifier GAME_END = BingoConstants.id("game_end");

	/** Changement de composition d'équipe. */
	public static final Identifier TEAM_SYNC = BingoConstants.id("team_sync");

	/**
	 * Noms et points cumulés des joueurs, pour le tableau des équipes.
	 *
	 * <p>Canal distinct de {@link #SCORE_UPDATE} bien que les deux partent souvent ensemble : celui-ci
	 * porte un total individuel qui traverse les manches, celui-là le score d'équipe dérivé de la
	 * carte courante. Les fusionner obligerait à réémettre l'un chaque fois que l'autre change.
	 */
	public static final Identifier PLAYER_STATS = BingoConstants.id("player_stats");

	/** Demande d'ouverture de l'écran de carte ({@code /bingo card}). */
	public static final Identifier OPEN_BOARD = BingoConstants.id("open_board");

	/**
	 * Début de l'animation de tirage — <strong>lot 4</strong> (`docs/04` §1).
	 *
	 * <p>Déclaré ici dès le lot 2 parce que `docs/06` §3 exige que tous les identifiants de canal
	 * soient des constantes de cette classe : c'est ce qui garantit qu'aucun canal n'apparaît en
	 * littéral au moment où on l'implémente.
	 */
	public static final Identifier ROLL_START = BingoConstants.id("roll_start");

	// ── Client → serveur (`docs/06` §3.2) ─────────────────────────────────────

	/**
	 * Demande de resynchronisation, unique commande C2S du mod.
	 *
	 * <p>Tout le reste — rejoindre une équipe, démarrer, mettre en pause — passe par les
	 * commandes Brigadier, qui sont déjà un canal C2S validé et permissionné (`docs/06` §3.2).
	 */
	public static final Identifier REQUEST_SYNC = BingoConstants.id("request_sync");

	private BingoNetworking() {
	}
}
