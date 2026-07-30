package com.bingo.mod.network.payload;

import com.bingo.mod.objective.JeiRole;
import com.bingo.mod.objective.Objective;
import com.bingo.mod.objective.ObjectiveInteraction;
import com.bingo.mod.objective.condition.ItemMatcher;
import com.bingo.mod.objective.type.CraftTarget;
import com.bingo.mod.objective.type.FindTarget;
import com.bingo.mod.objective.type.ObjectiveType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Projection d'affichage d'un objectif (`docs/06` §3.4).
 *
 * <p>Un objectif complet ne part <strong>jamais</strong> sur le réseau : le client ne fait que
 * du rendu (`docs/06` §5). Restent serveur {@code weight}, {@code conflicts},
 * {@code requires_dimension}, {@code announce}, {@code points_base}, et la cible des types
 * {@code KILL_MOB} / {@code DEATH} / {@code ACTION}.
 *
 * <p>Corollaire à ne pas perdre de vue : <strong>ce catalogue est purement présentationnel</strong>
 * (garde-fou 4 de `docs/06` §3.4). Aucune décision de jeu ne s'appuie dessus.
 *
 * @param target présent seulement pour {@code CRAFT} et {@code FIND}, pour
 *               {@code BingoJeiBridge} (`docs/03` §3)
 */
public record ObjectiveProjection(
		Identifier id,
		ObjectiveType type,
		int level,
		int count,
		Identifier icon,
		int iconCount,
		Optional<Text> name,
		Optional<Text> description,
		ObjectiveInteraction interaction,
		Optional<JeiRole> jeiRole,
		Optional<TargetHint> target
) {

	/**
	 * La cible réduite à ce dont JEI a besoin : un item ou un tag d'items.
	 *
	 * @param tag {@code true} si {@link #id} désigne un tag et non un item
	 */
	public record TargetHint(boolean tag, Identifier id) {
	}

	/** Projette un objectif serveur, en résolvant ses défauts dérivés du type (`docs/01` §5). */
	public static ObjectiveProjection of(Objective objective) {
		return new ObjectiveProjection(
				objective.id(),
				objective.type(),
				objective.level(),
				objective.count(),
				objective.display().icon(),
				objective.display().iconCount(),
				objective.display().name(),
				objective.display().description(),
				objective.effectiveInteraction(),
				objective.effectiveJeiRole(),
				targetHint(objective));
	}

	private static Optional<TargetHint> targetHint(Objective objective) {
		// Chaîne de instanceof et non switch sur motifs : ces derniers sont encore en preview sur
		// Java 17, la cible imposée par 1.20.1.
		ItemMatcher matcher = null;
		if (objective.target() instanceof CraftTarget craft) {
			matcher = craft.item();
		} else if (objective.target() instanceof FindTarget find) {
			matcher = find.item();
		}
		if (matcher == null) {
			return Optional.empty();
		}
		return Optional.of(matcher instanceof ItemMatcher.OfItem ofItem
				? new TargetHint(false, Registries.ITEM.getId(ofItem.item()))
				: new TargetHint(true, ((ItemMatcher.OfTag) matcher).tag().id()));
	}

	/** Titre affiché, avec l'ID brut en dernier recours — miroir client de {@code displayName()}. */
	public Text displayName() {
		return name.orElseGet(() -> Text.literal(id.toString()));
	}

	public void write(PacketByteBuf buf) {
		buf.writeIdentifier(id);
		buf.writeByte(type.ordinal());
		buf.writeByte(level);
		buf.writeVarInt(count);
		buf.writeIdentifier(icon);
		buf.writeVarInt(iconCount);
		buf.writeOptional(name, PacketByteBuf::writeText);
		buf.writeOptional(description, PacketByteBuf::writeText);
		buf.writeByte(interaction.ordinal());
		// Ordinal négatif = pas de rôle JEI, plutôt qu'un writeOptional : c'est un seul octet au
		// lieu de deux, sur le seul paquet du mod qui se compte en kilo-octets.
		buf.writeByte(jeiRole.map(Enum::ordinal).orElse(-1));
		buf.writeOptional(target, (target, hint) -> {
			target.writeBoolean(hint.tag());
			target.writeIdentifier(hint.id());
		});
	}

	public static ObjectiveProjection read(PacketByteBuf buf) {
		Identifier id = buf.readIdentifier();
		ObjectiveType type = ObjectiveType.byOrdinal(buf.readByte());
		int level = buf.readByte();
		int count = buf.readVarInt();
		Identifier icon = buf.readIdentifier();
		int iconCount = buf.readVarInt();
		Optional<Text> name = buf.readOptional(PacketByteBuf::readText);
		Optional<Text> description = buf.readOptional(PacketByteBuf::readText);
		ObjectiveInteraction interaction = ObjectiveInteraction.byOrdinal(buf.readByte());
		Optional<JeiRole> jeiRole = JeiRole.byOrdinal(buf.readByte());
		Optional<TargetHint> target = buf.readOptional(
				source -> new TargetHint(source.readBoolean(), source.readIdentifier()));

		// Un type inconnu vient d'un serveur plus récent que le client. On retombe sur ACTION,
		// dont l'interaction par défaut est le tooltip : la case reste affichable et cliquable
		// sans rien casser (garde-fou 3 de `docs/06` §3.4).
		return new ObjectiveProjection(id, type == null ? ObjectiveType.ACTION : type, level, count,
				icon, iconCount, name, description, interaction, jeiRole, target);
	}
}
