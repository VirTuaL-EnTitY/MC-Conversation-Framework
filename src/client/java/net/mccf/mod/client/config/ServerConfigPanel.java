package net.mccf.mod.client.config;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mccf.mod.client.util.LogExporter;
import net.mccf.mod.network.RequestConfigPayload;
import net.mccf.mod.network.RequestModelsPayload;
import net.mccf.mod.network.UpdateConfigPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

/**
 * "服务端配置"标签页：左侧 Provider 列表 + 右侧该 Provider 的 apiKey / model /
 * endpoint 等设置，需要 op 权限才能编辑，改动经由 {@link UpdateConfigPayload}
 * 提交给服务端、对所有玩家生效。
 *
 * 与旧版 MCCFConfigScreen 的行为差异（新版 UI 改动，见需求确认）：
 * - 原来"切换 Provider 下拉框"等于直接切换 activeProvider；现在左侧列表点击
 *   只切换"选中查看"（{@link #selectedProvider}），要点击"保存并启用"才会把
 *   它写进 {@link ClientConfigState#pendingActiveProvider} 并随下一次保存提交。
 *   普通"保存"按钮只保存当前查看的 Provider 的字段改动，不切换 activeProvider——
 *   两个操作分开，避免玩家只是想改个 API Key 却不小心把 Provider 切了。
 * - 其余字段级别的行为（清除 Key、恢复默认 Endpoint、获取模型列表、只读态）
 *   与旧版一致，原样迁移。
 */
public class ServerConfigPanel extends ProviderConfigPanel {

	private final ClientConfigState state = ClientConfigState.get();

	private TextFieldWidget apiKeyField;
	private TextFieldWidget modelField;
	private TextFieldWidget endpointField;
	private ButtonWidget saveButton;
	private ButtonWidget activateButton;
	private ButtonWidget fetchModelsButton;
	private ButtonWidget clearApiKeyButton;

	private Text statusMessage = Text.empty();
	private int statusColor = Colors.YELLOW;
	/** 玩家是否点了"清除密钥"按钮，语义同旧版 MCCFConfigScreen。 */
	private boolean userClearedApiKey = false;

	public ServerConfigPanel(Screen screen, int left, int top, int right, int bottom, int screenCenterY) {
		super(screen, left, top, right, bottom, screenCenterY);
		// 打开界面时向服务端请求最新快照。canSend 检查避免玩家尚未进入任何世界/
		// 连接任何服务器时调用 send() 抛异常导致崩溃（这是旧版就有的已知坑）。
		if (ClientPlayNetworking.canSend(RequestConfigPayload.ID)) {
			ClientPlayNetworking.send(new RequestConfigPayload(true));
		}
	}

	@Override
	protected String initialSelectedProvider() {
		return state.pendingActiveProvider;
	}

	@Override
	protected String activeProviderId() {
		return state.activeProvider;
	}

	@Override
	protected void buildRightPanel(int panelLeft, int panelTop, int panelRight, int panelBottom) {
		int panelWidth = panelRight - panelLeft;
		int fieldHeight = 20;
		// 动态间距：6 行控件（高 20）+ 5 个间距，默认 36px 宽松行距，
		// 在较小屏幕上自动压缩，避免按钮跑出屏幕。
		int spacing = Math.max(22, Math.min(36, (panelBottom - panelTop - 120) / 5));
		int y = panelTop;

		// "保存并启用"：把当前查看的 Provider 设为待启用目标，随下一次保存提交。
		// 与普通保存分开，避免玩家只想改字段却误切了 activeProvider。
		activateButton = own(ButtonWidget.builder(
						Text.translatable("mccf.config.activate"), button -> onActivate())
				.dimensions(panelLeft, y, panelWidth, fieldHeight)
				.build());
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

		int halfWidth = (panelWidth - 8) / 2;
		own(ButtonWidget.builder(Text.translatable("mccf.config.reset_endpoint"), button -> onResetEndpoint())
				.dimensions(panelLeft, y, halfWidth, fieldHeight)
				.build());
		fetchModelsButton = own(ButtonWidget.builder(
						Text.translatable("mccf.config.fetch_models"), button -> onFetchModels())
				.dimensions(panelLeft + halfWidth + 8, y, halfWidth, fieldHeight)
				.build());
		y += spacing;

		int thirdWidth = (panelWidth - 8) / 3;
		saveButton = own(ButtonWidget.builder(Text.translatable("mccf.config.save"), button -> onSave())
				.dimensions(panelLeft, y, thirdWidth, fieldHeight)
				.build());
		own(ButtonWidget.builder(Text.translatable("mccf.config.export_log"), button -> onExportLog())
				.dimensions(panelLeft + thirdWidth + 4, y, thirdWidth, fieldHeight)
				.build());
		own(ButtonWidget.builder(Text.translatable("mccf.config.close"), button -> screen.close())
				.dimensions(panelLeft + (thirdWidth + 4) * 2, y, thirdWidth, fieldHeight)
				.build());

		refreshFieldsFromState();
		applyEditability();
	}

	@Override
	protected void onProviderSelected(String providerId) {
		// 切换查看时重置"清除密钥"标记——每个 Provider 的 Key 独立保存，
		// 玩家在 A Provider 点了清除不意味着切到 B Provider 也想清除 B 的 Key。
		userClearedApiKey = false;
		refreshFieldsFromState();
		applyEditability();
	}

	/** 把 state 里当前查看 Provider 的数据填入输入框。 */
	private void refreshFieldsFromState() {
		if (apiKeyField == null) return; // buildRightPanel 尚未跑完（初次 init 时序）
		ClientProviderConfig pc = state.getOrCreate(selectedProvider);
		apiKeyField.setText(pc.apiKey == null ? "" : pc.apiKey);
		modelField.setText(pc.model == null ? "" : pc.model);
		endpointField.setText(pc.endpoint == null ? "" : pc.endpoint);

		boolean isActive = selectedProvider.equals(state.pendingActiveProvider);
		activateButton.active = tabVisible && state.canEdit && !isActive;
		activateButton.setMessage(Text.translatable(
				isActive ? "mccf.config.activate.current" : "mccf.config.activate"));
	}

	private void applyEditability() {
		boolean isMock = selectedProvider.equals("mock");
		boolean isDeepL = selectedProvider.equals("deepl");
		boolean supportsModelList = !ClientConfigState.NO_MODEL_LIST_SUPPORT.contains(selectedProvider);

		apiKeyField.active = tabVisible && state.canEdit && !isMock;
		endpointField.active = tabVisible && state.canEdit && !isMock;
		modelField.active = tabVisible && state.canEdit && !isMock && !isDeepL;
		fetchModelsButton.active = tabVisible && state.canEdit && supportsModelList;
		clearApiKeyButton.active = tabVisible && state.canEdit && !isMock;
		saveButton.active = tabVisible && state.canEdit;

		if (!state.canEdit) {
			statusMessage = Text.translatable("mccf.config.no_permission");
			statusColor = Colors.YELLOW;
		}
	}

	@Override
	protected void onTabVisibilityChanged() {
		if (apiKeyField == null) return; // 尚未 buildRightPanel
		applyEditability();
		// activateButton 的 active 还依赖 isActive，单独刷新一次。
		refreshFieldsFromState();
	}

	/** "保存并启用"：把当前查看的 Provider 记为待启用目标。真正提交仍需点"保存"。 */
	private void onActivate() {
		if (!state.canEdit) return;
		state.pendingActiveProvider = selectedProvider;
		refreshFieldsFromState();
		statusMessage = Text.translatable("mccf.config.activate_pending");
		statusColor = Colors.YELLOW;
	}

	/** 收到服务端最新快照后调用。 */
	public void onSnapshotUpdated() {
		// 快照里的 pendingActiveProvider 已经被 applySnapshot 重置为服务端确认值，
		// 列表的"选中查看"跟随过去，保持"保存后看到的就是刚生效的" 的直觉。
		selectedProvider = state.pendingActiveProvider;
		if (listWidget != null) {
			listWidget.setSelectedProvider(selectedProvider);
		}
		refreshFieldsFromState();
		applyEditability();
		statusMessage = Text.translatable("mccf.config.saved");
		statusColor = Colors.GREEN;
	}

	/** 收到服务端模型列表查询结果后调用。 */
	public void onModelsResult(boolean success, String providerId, java.util.List<String> models, String error) {
		if (success) {
			if (models.isEmpty()) {
				statusMessage = Text.translatable("mccf.config.models_empty");
				statusColor = Colors.YELLOW;
				return;
			}
			statusMessage = Text.translatable("mccf.config.models_opened");
			statusColor = Colors.YELLOW;
			if (MinecraftClient.getInstance() != null) {
				String currentModel = state.getOrCreate(providerId).model;
				MinecraftClient.getInstance().setScreen(
						new ModelSelectionScreen(screen, providerId, models, currentModel, this::setModelFromSelection));
			}
		} else {
			statusMessage = Text.translatable("mccf.config.fetch_failed", error);
			statusColor = Colors.YELLOW;
		}
	}

	/** ModelSelectionScreen 选中模型后的回调。 */
	public void setModelFromSelection(String model) {
		if (modelField != null) {
			modelField.setText(model);
		}
		state.getOrCreate(selectedProvider).model = model;
	}

	private void onResetEndpoint() {
		if (!state.canEdit) return;
		ClientProviderConfig pc = state.getOrCreate(selectedProvider);
		pc.endpoint = "";
		pc.isCustomEndpoint = false;
		pc.endpointAction = ClientProviderConfig.EndpointAction.RESET_DEFAULT;
		endpointField.setText("");
		statusMessage = Text.translatable("mccf.config.endpoint_reset");
		statusColor = Colors.YELLOW;
	}

	private void onClearApiKey() {
		if (!state.canEdit) return;
		apiKeyField.setText("");
		userClearedApiKey = true;
		statusMessage = Text.translatable("mccf.config.api_key_cleared");
		statusColor = Colors.YELLOW;
	}

	private void onFetchModels() {
		if (!state.canEdit) return;
		if (!ClientPlayNetworking.canSend(RequestModelsPayload.ID)) {
			statusMessage = Text.translatable("mccf.config.not_connected");
			statusColor = Colors.YELLOW;
			return;
		}

		com.google.gson.JsonObject requestRoot = new com.google.gson.JsonObject();
		requestRoot.addProperty("providerId", selectedProvider);
		requestRoot.addProperty("apiKey", apiKeyField.getText());
		requestRoot.addProperty("endpoint", endpointField.getText());

		ClientPlayNetworking.send(new RequestModelsPayload(requestRoot.toString()));
		statusMessage = Text.translatable("mccf.config.fetching_models");
		statusColor = Colors.YELLOW;
	}

	private void onExportLog() {
		String result = LogExporter.export(LogExporter.ExportMode.BOTH);
		statusMessage = Text.literal(result);
		statusColor = Colors.YELLOW;
	}

	private void onSave() {
		if (!state.canEdit) return;
		if (!ClientPlayNetworking.canSend(UpdateConfigPayload.ID)) {
			statusMessage = Text.translatable("mccf.config.not_connected");
			statusColor = Colors.YELLOW;
			return;
		}

		ClientProviderConfig pc = state.getOrCreate(selectedProvider);
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
		if (!enteredEndpoint.isBlank()) {
			pc.endpointAction = ClientProviderConfig.EndpointAction.CUSTOM;
			pc.isCustomEndpoint = true;
		} else if (pc.endpointAction != ClientProviderConfig.EndpointAction.RESET_DEFAULT) {
			pc.endpointAction = ClientProviderConfig.EndpointAction.UNCHANGED;
			pc.isCustomEndpoint = false;
		}

		ClientPlayNetworking.send(new UpdateConfigPayload(state.buildUpdateJson()));
		statusMessage = Text.translatable("mccf.config.saving");
		statusColor = Colors.YELLOW;
	}

	@Override
	protected void renderExtra(DrawContext context, int mouseX, int mouseY, float delta) {
		if (!tabVisible) return;
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int centerX = screen.width / 2;

		Text providerTitle = Text.translatable(ClientConfigState.providerNameKey(selectedProvider));
		context.drawCenteredTextWithShadow(textRenderer, providerTitle, centerX, top - 14, Colors.WHITE);

		int screenBottom = screen.height - 20;
		Text providerDesc = Text.translatable("mccf.config.provider_hint." + selectedProvider);
		context.drawCenteredTextWithShadow(textRenderer, providerDesc, centerX, screenBottom - 30, Colors.LIGHT_GRAY);

		if (!state.hasReceivedSnapshot) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mccf.config.loading"),
					centerX, screenBottom - 16, Colors.LIGHT_GRAY);
		} else if (!statusMessage.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, centerX, screenBottom - 16, statusColor);
		}
	}
}
