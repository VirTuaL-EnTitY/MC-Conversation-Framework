package net.mccf.mod.client.config;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mccf.mod.MCCF;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.network.RequestConfigPayload;
import net.mccf.mod.config.ProviderConfig;
import net.mccf.mod.translation.provider.ProviderFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.List;
import java.util.Locale;

/**
 * "本地设置"标签页：纯客户端翻译配置，不需要 op 权限，任何玩家都能自由编辑
 * （原 {@link ClientOnlyConfigScreen} 的功能迁移到这里，复用同一套左侧
 * Provider 列表 + 右侧设置的布局骨架——两个标签页视觉风格保持一致，见需求确认）。
 *
 * 与"服务端配置"标签页的关键区别：
 * - 这里的配置只影响玩家自己本地看到的翻译结果，不经过服务端。
 * - 多了一个"运行模式"选择器（自动检测 / 强制纯客户端 / 强制服务器模式）。
 * - 左侧列表点击只切换"选中查看"，点"保存"时当前选中的 Provider 会**同时**
 *   成为待启用目标并写入 {@link ClientOnlyTranslationConfig#activeProvider}——
 *   不再需要单独的"设为本地默认"按钮，与 {@link ServerConfigPanel} 的简化
 *   保持一致（应用户明确要求）。
 * - "从服务器同步"按钮拷贝 Provider 选择/模型名/Endpoint 等公开字段，不拷贝
 *   API Key。
 */
public class LocalConfigPanel extends ProviderConfigPanel {

	private final ClientOnlyTranslationConfig config = ClientOnlyTranslationConfig.get();

	private CyclingButtonWidget<ClientOnlyModeManager.Override> modeButton;
	private TextFieldWidget apiKeyField;
	private TextFieldWidget modelField;
	private TextFieldWidget endpointField;
	private ButtonWidget syncButton;
	private ButtonWidget saveButton;
	private ButtonWidget clearApiKeyButton;
	private ButtonWidget fetchModelsButton;
	/** "强制关闭思考"开关，只在 selectedProvider 支持思考控制时可见，见 ServerConfigPanel 同名字段注释。 */
	private CyclingButtonWidget<Boolean> disableThinkingButton;

	private Text statusMessage = Text.empty();
	private int statusColor = Colors.YELLOW;
	private boolean userClearedApiKey = false;
	/** 暂存的运行模式选择，点保存才落盘，语义同旧版 ClientOnlyConfigScreen。 */
	private ClientOnlyModeManager.Override pendingOverride = null;

	public LocalConfigPanel(Screen screen, int left, int top, int right, int bottom, int screenCenterY) {
		super(screen, left, top, right, bottom, screenCenterY);
	}

	@Override
	protected String initialSelectedProvider() {
		return config.activeProvider;
	}

	@Override
	protected String activeProviderId() {
		return config.activeProvider;
	}

	@Override
	protected void buildRightPanel(int panelLeft, int panelTop, int panelRight, int panelBottom) {
		int panelWidth = panelRight - panelLeft;
		int fieldHeight = 20;
		// 动态间距：7 行控件（高 20）+ 6 个间距——比之前多了一行"强制关闭思考"
		// 开关，分母从 5 改成 6。
		int spacing = Math.max(22, Math.min(36, (panelBottom - panelTop - 140) / 6));
		int y = panelTop;

		ClientOnlyModeManager.Override initialMode =
				pendingOverride != null ? pendingOverride : ClientOnlyModeManager.getOverride();
		modeButton = own(CyclingButtonWidget.<ClientOnlyModeManager.Override>builder(mode ->
						Text.translatable("mccf.localconfig.mode." + mode.name().toLowerCase(Locale.ROOT)))
				.values(List.of(ClientOnlyModeManager.Override.values()))
				.initially(initialMode)
				.build(panelLeft, y, panelWidth, fieldHeight,
						Text.translatable("mccf.localconfig.mode"), (button, value) -> pendingOverride = value));
		y += spacing;

		int apiKeyFieldWidth = panelWidth - 44;
		apiKeyField = own(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, panelLeft, y, apiKeyFieldWidth, fieldHeight,
				Text.translatable("mccf.config.api_key")));
		apiKeyField.setMaxLength(512);
		apiKeyField.setPlaceholder(Text.translatable("mccf.config.api_key.placeholder"));
		apiKeyField.setRenderTextProvider((text, firstCharacterIndex) ->
				Text.literal("•".repeat(text.length())).asOrderedText());

		clearApiKeyButton = own(ButtonWidget.builder(
						Text.translatable("mccf.config.clear_api_key"), button -> onClearApiKey())
				.dimensions(panelLeft + apiKeyFieldWidth + 4, y, 40, fieldHeight)
				.build());
		y += spacing;

		modelField = own(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, panelLeft, y, panelWidth, fieldHeight,
				Text.translatable("mccf.config.model")));
		modelField.setMaxLength(128);
		modelField.setPlaceholder(Text.translatable("mccf.config.model.placeholder"));
		y += spacing;

		endpointField = own(new TextFieldWidget(MinecraftClient.getInstance().textRenderer, panelLeft, y, panelWidth, fieldHeight,
				Text.translatable("mccf.config.endpoint")));
		endpointField.setMaxLength(256);
		endpointField.setPlaceholder(Text.translatable("mccf.config.endpoint.placeholder"));
		y += spacing;

		// "强制关闭思考"开关：与 ServerConfigPanel 同款逻辑，见其注释。这里的
		// disableThinking 存在 ClientProviderConfig（纯客户端模式，不经服务端）。
		ClientProviderConfig initialPc = config.getOrCreate(selectedProvider);
		disableThinkingButton = own(CyclingButtonWidget.onOffBuilder(initialPc.disableThinking)
				.build(panelLeft, y, panelWidth, fieldHeight,
						Text.translatable("mccf.config.disable_thinking"),
						(button, value) -> onDisableThinkingToggled(button, value)));
		y += spacing;

		// "获取模型列表"按钮：纯客户端模式下不走服务端中转，客户端直接调 Provider 的
		// listModels() 接口拉取。和 ServerConfigPanel 的同名按钮功能一致，区别是
		// ServerConfigPanel 把请求转发给服务端处理（因为服务端有 API Key 的权威副本），
		// 这里直接在客户端构造 Provider 发 HTTP——纯客户端模式下服务端可能根本没装 MCCF，
		// 没法帮忙。
		fetchModelsButton = own(ButtonWidget.builder(
						Text.translatable("mccf.config.fetch_models"), button -> onFetchModels())
				.dimensions(panelLeft, y, panelWidth, fieldHeight)
				.build());
		y += spacing;

		syncButton = own(ButtonWidget.builder(Text.translatable("mccf.localconfig.sync"), button -> onSync())
				.dimensions(panelLeft, y, panelWidth, fieldHeight)
				.build());
		y += spacing;

		int halfWidth = (panelWidth - 8) / 2;
		saveButton = own(ButtonWidget.builder(Text.translatable("mccf.config.save"), button -> onSave())
				.dimensions(panelLeft, y, halfWidth, fieldHeight)
				.build());
		own(ButtonWidget.builder(Text.translatable("mccf.config.close"), button -> screen.close())
				.dimensions(panelLeft + halfWidth + 8, y, halfWidth, fieldHeight)
				.build());

		refreshFieldsFromState();
		refreshSyncButtonState();
	}

	@Override
	protected void onProviderSelected(String providerId) {
		userClearedApiKey = false;
		refreshFieldsFromState();
	}

	private void refreshFieldsFromState() {
		if (apiKeyField == null) return;
		ClientProviderConfig pc = config.getOrCreate(selectedProvider);
		apiKeyField.setText(pc.apiKey == null ? "" : pc.apiKey);
		modelField.setText(pc.model == null ? "" : pc.model);
		endpointField.setText(pc.endpoint == null ? "" : pc.endpoint);

		boolean isMock = selectedProvider.equals("mock");
		boolean isDeepL = selectedProvider.equals("deepl");
		boolean supportsModelList = !ClientConfigState.NO_MODEL_LIST_SUPPORT.contains(selectedProvider);
		boolean supportsThinking = ClientConfigState.THINKING_CAPABLE_PROVIDERS.contains(selectedProvider);

		apiKeyField.active = tabVisible && !isMock;
		endpointField.active = tabVisible && !isMock;
		modelField.active = tabVisible && !isMock && !isDeepL;
		clearApiKeyButton.active = tabVisible && !isMock;
		if (fetchModelsButton != null) {
			fetchModelsButton.active = tabVisible && supportsModelList;
		}
		if (disableThinkingButton != null) {
			disableThinkingButton.visible = supportsThinking;
			disableThinkingButton.active = tabVisible && supportsThinking;
			disableThinkingButton.setValue(pc.disableThinking);
		}
	}

	private void refreshSyncButtonState() {
		boolean canSync = ClientPlayNetworking.canSend(RequestConfigPayload.ID)
				&& ClientConfigState.get().hasReceivedSnapshot;
		if (syncButton != null) {
			syncButton.active = tabVisible && canSync;
		}
	}

	@Override
	protected void onTabVisibilityChanged() {
		if (apiKeyField == null) return;
		refreshFieldsFromState();
		refreshSyncButtonState();
		if (saveButton != null) saveButton.active = tabVisible;
		if (modeButton != null) modeButton.active = tabVisible;
	}

	private void onSync() {
		if (!ClientConfigState.get().hasReceivedSnapshot) {
			statusMessage = Text.translatable("mccf.localconfig.sync_no_data");
			statusColor = Colors.YELLOW;
			return;
		}
		config.copyPublicFieldsFrom(ClientConfigState.get());
		selectedProvider = config.activeProvider;
		if (listWidget != null) {
			listWidget.setSelectedProvider(selectedProvider);
		}
		userClearedApiKey = false;
		refreshFieldsFromState();
		statusMessage = Text.translatable("mccf.localconfig.sync_done");
		statusColor = Colors.YELLOW;
	}

	/**
	 * "强制关闭思考"开关的点击回调——纯客户端模式版本，逻辑与
	 * {@link ServerConfigPanel#onDisableThinkingToggled} 一致（打开时弹确认
	 * 警告，关闭直接生效），区别只是这里直接改本地 {@code ClientProviderConfig}
	 * 对象，不涉及 UpdateConfigPayload 网络提交（纯客户端模式配置只影响
	 * 玩家自己本地看到的翻译结果）。点"保存"（onSave/performSave）时才会
	 * 调用 {@link ClientOnlyTranslationConfig#save()} 落盘。
	 */
	private void onDisableThinkingToggled(CyclingButtonWidget<Boolean> button, boolean newValue) {
		if (!newValue) {
			config.getOrCreate(selectedProvider).disableThinking = false;
			return;
		}

		MinecraftClient.getInstance().setScreen(new ConfirmScreen(
				confirmed -> {
					MinecraftClient.getInstance().setScreen(screen);
					if (confirmed) {
						config.getOrCreate(selectedProvider).disableThinking = true;
					} else {
						button.setValue(false);
					}
				},
				Text.translatable("mccf.config.disable_thinking_warning_title"),
				Text.translatable("mccf.config.disable_thinking_warning_body")));
	}

	/**
	 * 客户端直连 Provider API 拉取模型列表——不经过服务端中转。
	 *
	 * 为什么不走服务端：纯客户端模式下服务端可能根本没装 MCCF，发 RequestModelsPayload
	 * 会被 canSend 拦截（通道不存在）。即使服务端装了 MCCF，玩家也可能在强制纯客户端
	 * 模式下不想依赖服务端——本地配置的 API Key 和服务端配置的可能不是同一个，
	 * 用服务端的 Key 去查模型列表反而会查到错误账号下的模型。
	 *
	 * 用输入框里当前填的 apiKey/endpoint（可能还没点保存）构造一次性 Provider，
	 * 方便玩家"填完 Key 立刻测一下能不能拉到模型"——和服务端面板的交互一致。
	 * 如果输入框为空，沿用已保存的配置（方便"已经保存过，只是想重新拉一次"的场景）。
	 */
	private void onFetchModels() {
		ClientProviderConfig savedPc = config.getOrCreate(selectedProvider);
		String apiKey = apiKeyField.getText().isBlank() ? savedPc.apiKey : apiKeyField.getText();
		String endpoint = endpointField.getText().isBlank() ? savedPc.endpoint : endpointField.getText();
		String model = modelField.getText().isBlank() ? savedPc.model : modelField.getText();

		ProviderConfig tempConfig = new ProviderConfig(apiKey, model, endpoint);
		var provider = ProviderFactory.create(selectedProvider, tempConfig);

		statusMessage = Text.translatable("mccf.config.fetching_models");
		statusColor = Colors.YELLOW;

		provider.listModels().thenAccept(models -> MinecraftClient.getInstance().execute(() -> {
			if (models.isEmpty()) {
				statusMessage = Text.translatable("mccf.config.models_empty");
				statusColor = Colors.YELLOW;
				return;
			}
			statusMessage = Text.translatable("mccf.config.models_opened");
			statusColor = Colors.YELLOW;
			MinecraftClient.getInstance().setScreen(
					new ModelSelectionScreen(screen, selectedProvider, models, model, this::setModelFromSelection));
		})).exceptionally(ex -> {
			String reason = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
			MCCF.LOGGER.warn("[MCCF] Local model list fetch failed for provider {}: {}", selectedProvider, reason);
			MinecraftClient.getInstance().execute(() -> {
				statusMessage = Text.translatable("mccf.config.fetch_failed", reason);
				statusColor = Colors.YELLOW;
			});
			return null;
		});
	}

	/** ModelSelectionScreen 选中模型后的回调：更新输入框 + 写入本地配置（不立即保存）。 */
	public void setModelFromSelection(String model) {
		if (modelField != null) {
			modelField.setText(model);
		}
		config.getOrCreate(selectedProvider).model = model;
	}

	private void onClearApiKey() {
		apiKeyField.setText("");
		userClearedApiKey = true;
		statusMessage = Text.translatable("mccf.config.api_key_cleared");
		statusColor = Colors.YELLOW;
	}

	/**
	 * 保存前置检查：如果玩家选的是"强制服务器模式"、且客户端实际检测到当前连接的
	 * 服务器没有装 MCCF，直接保存这个选择会导致翻译完全失效（见
	 * mccf.localconfig.warn_force_server_no_mod 的说明）——这不是"可能有风险"，
	 * 是"确定会出问题"，所以改成原版 ConfirmScreen 风格的拦截式确认弹窗，而不是
	 * 一段常驻在设置界面里、容易被忽略或跟其他提示文字挤在一起的静态警告文字。
	 *
	 * 弹窗只在"点保存的这一刻"触发检查（而不是玩家一在下拉框选中该模式就弹），
	 * 因为：(a) 玩家可能只是随手切换看看选项，还没想好，此时弹窗打断操作体验不好；
	 * (b) 服务器检测状态可能在玩家操作过程中变化（比如切标签页时重新连接），
	 * 点保存时的检测结果才是最终会被写入配置的、真正起作用的状态。
	 */
	private void onSave() {
		ClientOnlyModeManager.Override effectiveOverride =
				pendingOverride != null ? pendingOverride : ClientOnlyModeManager.getOverride();
		boolean needsConfirmation = effectiveOverride == ClientOnlyModeManager.Override.FORCE_SERVER_MODE
				&& !ClientOnlyModeManager.isServerDetected();

		if (needsConfirmation) {
			// 用 ConfirmScreen 最基础的 3 参数构造函数（callback, title, message）——
			// 这是自早期版本起就稳定存在、Yarn mapping 从未变动过的签名。ConfirmScreen
			// 还有一个额外重载支持自定义"是/否"按钮文字，但那个重载在不同 Minecraft
			// 版本间的参数顺序/个数有过变化，在没有本地反编译源码可核对的情况下贸然
			// 使用有编译失败的风险，所以这里保守地用最基础版本，按钮固定显示原版的
			// "是/否"（gui.yes / gui.no），语义上"是=仍然保存，否=取消"依然清楚。
			MinecraftClient.getInstance().setScreen(new ConfirmScreen(
					confirmed -> {
						// 无论选哪个按钮，都要先把界面切回配置屏幕本身——ConfirmScreen 的
						// callback 触发时它自己还是 currentScreen，不主动切回的话，点"是"
						// 之后玩家会卡在一个已经关闭逻辑但仍显示的确认弹窗上。
						MinecraftClient.getInstance().setScreen(screen);
						if (confirmed) {
							performSave();
						}
						// 取消：不调用 performSave()，pendingOverride 等已输入的字段原样保留在
						// 界面上（不清空），玩家可以直接改成别的模式再保存，不需要重新填一遍
						// API Key 等其他字段。
					},
					Text.translatable("mccf.localconfig.warn_force_server_title"),
					Text.translatable("mccf.localconfig.warn_force_server_no_mod")));
			return;
		}

		performSave();
	}

	/**
	 * 实际执行保存——原 onSave() 的全部逻辑，供确认弹窗回调和"无需确认"路径共用。
	 * 保存 = 同时把当前查看的 Provider 设为本地激活目标（不再需要单独的
	 * "设为本地默认"按钮，见类注释）。
	 */
	private void performSave() {
		ClientProviderConfig pc = config.getOrCreate(selectedProvider);
		String enteredKey = apiKeyField.getText();
		if (userClearedApiKey) {
			pc.apiKey = "";
		} else if (!enteredKey.isBlank()) {
			pc.apiKey = enteredKey;
		}
		userClearedApiKey = false;

		pc.model = modelField.getText();
		String enteredEndpoint = endpointField.getText();
		pc.endpoint = enteredEndpoint;
		pc.isCustomEndpoint = !enteredEndpoint.isBlank();

		config.activeProvider = selectedProvider;

		if (pendingOverride != null) {
			ClientOnlyModeManager.setOverride(pendingOverride);
		}

		config.save();
		if (listWidget != null) {
			listWidget.setSelectedProvider(selectedProvider);
		}
		statusMessage = Text.translatable("mccf.config.saved");
		statusColor = Colors.GREEN;
	}

	@Override
	protected void renderExtra(DrawContext context, int mouseX, int mouseY, float delta) {
		// 注：不需要在这里判断 tabVisible——见 ServerConfigPanel#renderExtra 的
		// 同款注释，MCCFConfigScreen.render() 只在 activeTab 匹配时才会调用
		// 到这里，非活动标签页时这个方法根本不会被执行。
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int centerX = screen.width / 2;

		Text providerTitle = Text.translatable(ClientConfigState.providerNameKey(selectedProvider));
		int titleY = top - 14;
		context.drawCenteredTextWithShadow(textRenderer, providerTitle, centerX, titleY, Colors.WHITE);

		// 检测状态行：没有加入任何世界/服务器时，"服务器是否装了 MCCF"这个问题
		// 根本无意义（serverHasMod 在未连接时恒为 false，跟"连接了但真的没装"
		// 是同一个 false，界面上无法区分，此前就是这个原因导致主界面/单机模式下
		// 也会显示"服务器未检测到 MCCF"这种带有误导性的提示）。用
		// MinecraftClient.player 是否为 null 判断"当前是否处于某个世界中"——
		// 这是 Minecraft 客户端判断"是否在游戏内"的标准方式，单人存档和联机
		// 服务器通用。未在任何世界中时，这一行整行不显示（HintLine 空文本会被
		// renderLeftBottomHints 自动跳过）。
		boolean inWorld = MinecraftClient.getInstance().player != null;
		HintLine detectedLine = inWorld
				? new HintLine(Text.translatable(ClientOnlyModeManager.isServerDetected()
						? "mccf.localconfig.detected_yes" : "mccf.localconfig.detected_no"), Colors.LIGHT_GRAY)
				: new HintLine(Text.empty(), Colors.LIGHT_GRAY);

		// 现在只剩检测状态 + 操作状态消息两类常驻内容（Provider 说明已改为
		// tooltip），最多 2 行，左下角预留空间可以大幅缩小，见
		// MCCFConfigScreen.BOTTOM_HINT_AREA_HEIGHT 的调整。
		renderLeftBottomHints(context, left, bottom + 6,
				detectedLine,
				new HintLine(statusMessage, statusColor));
	}
}
