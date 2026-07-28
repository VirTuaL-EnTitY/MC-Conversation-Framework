package net.mccf.mod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.mccf.mod.MCCF;
import net.mccf.mod.client.chat.ClientOnlyChatTranslator;
import net.mccf.mod.client.config.ClientConfigState;
import net.mccf.mod.client.config.MCCFConfigScreen;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.client.subtitle.HotbarSubtitleRenderer;
import net.mccf.mod.client.subtitle.SubtitleManager;
import net.mccf.mod.client.subtitle.WorldSubtitleRenderer;
import net.mccf.mod.network.ConfigSnapshotPayload;
import net.mccf.mod.network.LanguageReportPayload;
import net.mccf.mod.network.ModelsResultPayload;
import net.mccf.mod.network.SubtitlePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * MCCF 客户端入口。
 *
 * 职责：
 * 1. 加入服务器时上报本机 Minecraft 语言设置（原则："客户端自动检测语言"）。
 * 2. 接收服务端字幕包，写入 {@link SubtitleManager}。
 * 3. 接收服务端配置快照包，写入 {@link ClientConfigState}，供配置界面显示。
 * 4. 注册两套字幕渲染器：
 *    - {@link WorldSubtitleRenderer}：VISIBLE 模式，悬浮在说话者头顶
 *    - {@link HotbarSubtitleRenderer}：AUDIBLE 模式，显示在物品栏上方
 * 5. 注册按键绑定，独立于 ModMenu 呼出配置界面（{@link MCCFConfigScreen}）。
 *
 * 注：目标版本固定在 1.21.1（1.21.x 系列早期稳定版本），因为：
 * - WorldRenderEvents（VISIBLE 模式悬浮字幕依赖的 API）在 1.21.9 的移植中
 *   因原版渲染管线重构被整体移除，目前没有稳定替代方案；1.21.1 及之前的
 *   1.21.x 版本该 API 正常可用。
 * - KeyBinding 的分类参数在 1.21.9 起从字符串改为 KeyBinding.Category 对象，
 *   按键检测方法也从 wasPressed() 改名为 consumeClick()；1.21.1 仍用旧写法
 *   （字符串分类 + wasPressed()），本类按此实现。
 */
public class MCCFClient implements ClientModInitializer {

	private static KeyBinding openConfigKey;

	@Override
	public void onInitializeClient() {
		// 纯客户端模式：读取玩家上次保存的手动模式覆盖设置，并注册本地聊天翻译。
		// 详见 ClientOnlyModeManager / ClientOnlyChatTranslator 的类注释。
		ClientOnlyModeManager.load();
		ClientOnlyChatTranslator.register();

		WorldSubtitleRenderer worldRenderer = new WorldSubtitleRenderer();
		HotbarSubtitleRenderer hotbarRenderer = new HotbarSubtitleRenderer();

		WorldRenderEvents.AFTER_ENTITIES.register(worldRenderer::render);

		// 挂载在所有原版 HUD 层之后渲染，确保字幕不被物品栏/生命值等遮挡。
		// 1.21.1 上用 HudRenderCallback.EVENT（旧 API）；HudElementRegistry 是
		// Fabric API 后续版本（约 1.21.6+）才引入的新 API，1.21.1 上不存在。
		HudRenderCallback.EVENT.register((context, tickCounter) -> hotbarRenderer.render(context, tickCounter));

		ClientPlayNetworking.registerGlobalReceiver(SubtitlePayload.ID, (payload, context) ->
				context.client().execute(() -> SubtitleManager.onReceive(payload)));

		// 配置界面数据同步：服务端下发的快照写入本地状态，若配置 Screen 正开着则刷新其显示。
		ClientPlayNetworking.registerGlobalReceiver(ConfigSnapshotPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					ClientConfigState.get().applySnapshot(payload);
					if (context.client().currentScreen instanceof MCCFConfigScreen screen) {
						screen.onSnapshotUpdated();
					}
				}));

		// 配置界面"一键获取模型"的结果：解析 JSON，转发给正开着的配置 Screen 展示。
		ClientPlayNetworking.registerGlobalReceiver(ModelsResultPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					if (!(context.client().currentScreen instanceof MCCFConfigScreen screen)) return;
					try {
						com.google.gson.JsonObject root =
								com.google.gson.JsonParser.parseString(payload.json()).getAsJsonObject();
						boolean success = root.has("success") && root.get("success").getAsBoolean();
						String providerId = root.has("providerId") ? root.get("providerId").getAsString() : "";
						if (success) {
							java.util.List<String> models = new java.util.ArrayList<>();
							if (root.has("models")) {
								root.getAsJsonArray("models").forEach(e -> models.add(e.getAsString()));
							}
							screen.onModelsResult(true, providerId, models, null);
						} else {
							String error = root.has("error") ? root.get("error").getAsString() : "Unknown error";
							screen.onModelsResult(false, providerId, java.util.List.of(), error);
						}
					} catch (Exception e) {
						screen.onModelsResult(false, "", java.util.List.of(), "Failed to parse response.");
					}
				}));

		// 每次加入服务器（含切换服务器）时上报当前客户端语言设置，并刷新
		// "服务器是否装了 MCCF" 的自动检测结果（纯客户端模式判定的依据之一）。
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			String language = detectClientLanguage(client);
			ClientPlayNetworking.send(new LanguageReportPayload(language));
			ClientOnlyModeManager.onJoinServer();
		});

		// 断开连接时清空本地字幕状态 + 重置模式检测结果，避免残留到下一局/下一个服务器。
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SubtitleManager.clear();
			ClientOnlyModeManager.onDisconnect();
		});

		// 独立按键呼出配置界面。默认未绑定具体键位（InputUtil.UNKNOWN_KEY），
		// 玩家需要自己在"设置 -> 控制 -> 按键绑定"里指定一个键；不装 ModMenu 也能用。
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.mccf.open_config",
				InputUtil.Type.KEYSYM,
				InputUtil.UNKNOWN_KEY.getCode(),
				"key.category.mccf.main"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openConfigKey.wasPressed()) {
				if (client.currentScreen == null) {
					client.setScreen(new MCCFConfigScreen(null));
				}
			}
		});
	}

	private String detectClientLanguage(MinecraftClient client) {
		// Options.language 存的就是 Minecraft locale 格式（如 "zh_cn"），
		// 与我们在 fabric.mod.json / lang 文件里使用的格式一致，无需转换。
		String language = client.options.language;
		return (language == null || language.isBlank()) ? "en_us" : language;
	}
}
