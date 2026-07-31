package com.bingo.mod.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Rétrécit le titre et le sous-titre plein écran envoyés par {@code BingoAnnouncer} au début et à
 * la fin d'une manche.
 *
 * <p>Le mod est le seul émetteur de {@code TitleS2CPacket}/{@code SubtitleS2CPacket} de cette
 * partie : personne d'autre ne passe par {@code InGameHud#render} pour un titre. Modifier
 * l'échelle vanilla (×4 et ×2, jugée disproportionnée à l'écran) à cet unique endroit revient donc
 * à ne toucher que les annonces de Bingo, sans introduire de condition sur le texte affiché.
 */
@Mixin(InGameHud.class)
public class InGameHudTitleScaleMixin {

	private static final float TITLE_SCALE = 2.5f;
	private static final float SUBTITLE_SCALE = 1.5f;

	@ModifyConstant(method = "render", constant = @Constant(floatValue = 4.0f))
	private float bingo$shrinkTitle(float original) {
		return TITLE_SCALE;
	}

	@ModifyConstant(method = "render", constant = @Constant(floatValue = 2.0f))
	private float bingo$shrinkSubtitle(float original) {
		return SUBTITLE_SCALE;
	}
}
