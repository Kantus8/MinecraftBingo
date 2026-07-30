package com.bingo.mod.integration.voicechat;

import com.bingo.mod.util.BingoConstants;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

/**
 * Entrypoint {@code voicechat} — plugin Simple Voice Chat (`docs/02` §3.1-3.2, tâche 3.2).
 *
 * <p>L'entrypoint est déclaré dans {@code fabric.mod.json} depuis le lot 0 et {@code voicechat} est
 * en {@code depends} dur : sans cette classe, le jeu ne démarre pas du tout. Le lot 3 lui ajoute les
 * trois événements dont {@link BingoVoiceManager} a besoin, et rien de plus.
 *
 * <p>Aucune logique ici : cette classe est un adaptateur. Elle traduit trois événements de
 * Simple Voice Chat en trois appels au gestionnaire, qui est seul à décider. {@code initialize} est
 * laissé à son implémentation par défaut — on veut l'API <em>serveur</em>, qui n'arrive qu'avec
 * {@link VoicechatServerStartedEvent}, pas l'API générique.
 *
 * <p>Vit dans {@code src/main/java} : la logique de groupes est 100 % serveur et l'entrypoint doit
 * être chargeable sur un serveur dédié (`docs/06` §5).
 *
 * <p><strong>Pas de {@code MicrophonePacketEvent}</strong> (`docs/02` §5) : aucun traitement audio,
 * aucun filtre, aucun canal custom. Le mod assigne des groupes, Simple Voice Chat fait tout le
 * mixage. C'est ce qui rend cette intégration robuste — et courte.
 */
public class BingoVoicechatPlugin implements VoicechatPlugin {

	/** Doit être unique parmi les plugins vocaux ; on utilise le mod id. */
	@Override
	public String getPluginId() {
		return BingoConstants.MOD_ID;
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
		registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
		registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
	}

	/** Le seul endroit d'où l'on obtient une {@code VoicechatServerApi}. */
	private void onServerStarted(VoicechatServerStartedEvent event) {
		BingoVoiceManager.get().bind(event.getVoicechat());
	}

	/**
	 * Le serveur vocal peut s'arrêter sans que le serveur Minecraft s'arrête — l'opérateur peut le
	 * couper en cours de partie. Garder l'API dans ce cas ferait échouer chaque assignation
	 * suivante en silence.
	 */
	private void onServerStopped(VoicechatServerStoppedEvent event) {
		BingoVoiceManager.get().unbind();
	}

	/** Connexion ou reconnexion en pleine manche : retrouver son groupe (`docs/02` §4). */
	private void onPlayerConnected(PlayerConnectedEvent event) {
		BingoVoiceManager.get().reapply(event.getConnection());
	}
}
