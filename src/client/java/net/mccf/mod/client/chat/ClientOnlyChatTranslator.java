package net.mccf.mod.client.chat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.mccf.mod.MCCF;
import net.mccf.mod.client.config.ClientOnlyTranslationConfig;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.translation.provider.ProviderFactory;
import net.mccf.mod.translation.provider.TranslationProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 纯客户端模式下的聊天翻译：不做任何空间化听觉判定（没有服务端参与，做不到
 * 真正的点对点分发/信息隔离），单纯把收到的每一条玩家聊天消息在本地翻译成
 * 玩家自己的语言，追加显示在聊天栏里——原始消息仍然正常显示，翻译是"追加"
 * 而不是"替换"。
 *
 * 只在 {@link ClientOnlyModeManager#isClientOnlyModeActive()} 为 true 时生效；
 * 服务器装了 MCCF 且未被手动强制切换到纯客户端模式时，翻译由服务端的
 * 空间化管线负责，这里不重复处理，避免同一条消息被翻译两次。
 *
 * 用的是 {@code ClientReceiveMessageEvents.CHAT}（信息性事件，在消息已经
 * 确定会被显示之后触发，不需要返回值），而不是 {@code ALLOW_CHAT}——
 * 因为翻译是异步的（多数 Provider 走网络请求），没法在一次同步事件回调里
 * 立刻拿到结果去替换原始文本，所以选择"让原文正常显示，翻译结果异步追加
 * 一条"的方案，而不是等翻译完成才决定是否放行原始消息。
 */
public final class ClientOnlyChatTranslator {

	private ClientOnlyChatTranslator() {}

	public static void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (!ClientOnlyModeManager.isClientOnlyModeActive()) return;

			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null) return;

			// 自己发的消息服务器回显给自己时不需要再翻译一遍——玩家显然已经知道自己说了什么。
			if (sender != null && sender.getId() != null && sender.getId().equals(client.player.getUuid())) {
				return;
			}

			String sourceText = message.getString();
			if (sourceText.isBlank()) return;

			String targetLang = detectClientLanguage(client);

			ClientOnlyTranslationConfig config = ClientOnlyTranslationConfig.get();
			TranslationProvider provider = ProviderFactory.create(
					config.activeProvider, config.toProviderConfig(config.activeProvider));

			provider.translate(new TranslationProvider.TranslationRequest(
					sourceText, "auto", targetLang, List.of()
			)).thenAccept(result -> client.execute(() -> {
				if (client.player == null) return;
				String translated = result.translatedText();
				if (translated == null || translated.isBlank() || translated.equals(sourceText)) return;
				client.inGameHud.getChatHud().addMessage(Text.literal("⇄ " + translated).formatted(net.minecraft.util.Formatting.GRAY));
			})).exceptionally(ex -> {
				// 纯客户端模式下翻译失败（比如没配 API Key）只记日志，不刷屏聊天栏——
				// 玩家可以用配置界面的"导出日志"按钮排查，不需要每条消息都弹一次错误。
				String reason = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
				MCCF.LOGGER.warn("[MCCF] Client-only translation failed: {}", reason);
				return null;
			});
		});
	}

	private static String detectClientLanguage(MinecraftClient client) {
		String language = client.options.language;
		return (language == null || language.isBlank()) ? "en_us" : language;
	}
}
