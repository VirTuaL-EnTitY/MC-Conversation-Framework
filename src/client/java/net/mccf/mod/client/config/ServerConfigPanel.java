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
 * - 左侧列表点击只切换"选中查看"（{@link #selectedProvider}），不会立即生效。
 * - 点"保存"时，当前选中查看的 Provider 会**同时**成为待启用目标——不再需要
 *   单独的"保存并启用"/"设为默认"按钮，保存这一个动作同时做两件事：
 *   (a) 保存当前查看 Provider 的字段改动（API Key / 模型 / Endpoint）；
 *   (b) 把当前查看的 Provider 设为 activeProvider。这是应用户明确要求的
 *   简化——"选择某个 Provider 再点保存，就代表把它设为默认"，不需要额外
 *   再点一次"设为默认"。
 * - 其余字段级别的行为（清除 Key、恢复默认 Endpoint、获取模型列表、只读态）
 *   与旧版一致，原样迁移。
 */
public class ServerConfigPanel extends ProviderConfigPanel {

	private final ClientConfigState state = ClientConfigState.get();

	private TextFieldWidget apiKeyField;
	private TextFieldWidget modelField;
	private TextFieldWidget endpointField;
	private ButtonWidget saveButton;
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
		return state.activeProvider;
	}

	@Override
	protected String activeProviderId() {
		return state.activeProvider;
	}

	@Override
	protected void buildRightPanel(int panelLeft, int panelTop, int panelRight, int panelBottom) {
		int panelWidth = panelRight - panelLeft;
		int fieldHeight = 20;
		// 动态间距：5 行控件（高 20）+ 4 个间距——比原来少了"保存并启用"这一行，
		// 分母也从 5 改成 4，其余按钮更宽松（原设计的"最多 6 行"上限不再适用）。
		int spacing = Math.max(22, Math.min(36, (panelBottom - panelTop - 100) / 4));
		int y = panelTop;

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
	}

	/** 收到服务端最新快照后调用。 */
	public void onSnapshotUpdated() {
		// 快照里的 activeProvider 是服务端确认生效的值，列表的"选中查看"跟随
		// 过去，保持"保存后看到的就是刚生效的"这个直觉。
		selectedProvider = state.activeProvider;
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

	/**
	 * 保存：把当前查看的 Provider 的字段改动写入，并且——按用户明确要求——
	 * 把当前查看的 Provider 直接设为待启用目标（{@code pendingActiveProvider}）。
	 * 不再需要单独的"设为默认"按钮，保存这一步就代表"我选的就是这个"。
	 */
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

		// 保存 = 同时把当前查看的 Provider 设为激活目标，见方法注释。
		state.pendingActiveProvider = selectedProvider;

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

		// 底部提示区两行，间距 18px，与 LocalConfigPanel 保持一致的视觉风格
		// （两个标签页共用同一套骨架，提示文字排布也应该看起来是"同一个界面的
		// 两个页签"而不是两套不同的间距规则）。
		int screenBottom = screen.height - 20;
		int lineSpacing = 18;
		Text providerDesc = Text.translatable("mccf.config.provider_hint." + selectedProvider);
		context.drawCenteredTextWithShadow(textRenderer, providerDesc, centerX, screenBottom - lineSpacing * 2, Colors.LIGHT_GRAY);

		if (!state.hasReceivedSnapshot) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mccf.config.loading"),
					centerX, screenBottom - lineSpacing, Colors.LIGHT_GRAY);
		} else if (!statusMessage.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, centerX, screenBottom - lineSpacing, statusColor);
		}
	}
}
