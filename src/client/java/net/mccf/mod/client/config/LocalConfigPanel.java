package net.mccf.mod.client.config;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.network.RequestConfigPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
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
 * - 左侧列表点击同样只切换"选中查看"，"设为本地默认"按钮才会把选中的
 *   Provider 写入 {@link ClientOnlyTranslationConfig#activeProvider}（点保存
 *   才真正落盘，与服务端面板的"保存并启用"同理）。
 * - "从服务器同步"按钮拷贝 Provider 选择/模型名/Endpoint 等公开字段，不拷贝
 *   API Key。
 */
public class LocalConfigPanel extends ProviderConfigPanel {

	private final ClientOnlyTranslationConfig config = ClientOnlyTranslationConfig.get();

	/** 待启用的本地 Provider——点了"设为本地默认"但还没点保存时的暂存值。 */
	private String pendingActiveProvider;

	private CyclingButtonWidget<ClientOnlyModeManager.Override> modeButton;
	private TextFieldWidget apiKeyField;
	private TextFieldWidget modelField;
	private TextFieldWidget endpointField;
	private ButtonWidget syncButton;
	private ButtonWidget saveButton;
	private ButtonWidget activateButton;
	private ButtonWidget clearApiKeyButton;

	private Text statusMessage = Text.empty();
	private int statusColor = Colors.YELLOW;
	private boolean userClearedApiKey = false;
	/** 暂存的运行模式选择，点保存才落盘，语义同旧版 ClientOnlyConfigScreen。 */
	private ClientOnlyModeManager.Override pendingOverride = null;

	public LocalConfigPanel(Screen screen, int left, int top, int right, int bottom, int screenCenterY) {
		super(screen, left, top, right, bottom, screenCenterY);
		this.pendingActiveProvider = config.activeProvider;
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
		// 动态间距：7 行控件（高 20）+ 6 个间距，默认 36px 宽松行距，
		// 在较小屏幕上自动压缩，避免按钮跑出屏幕。
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
		boolean isActive = selectedProvider.equals(pendingActiveProvider);

		apiKeyField.active = tabVisible && !isMock;
		endpointField.active = tabVisible && !isMock;
		modelField.active = tabVisible && !isMock && !isDeepL;
		clearApiKeyButton.active = tabVisible && !isMock;
		activateButton.active = tabVisible && !isActive;
		activateButton.setMessage(Text.translatable(
				isActive ? "mccf.config.activate.current" : "mccf.config.activate"));
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

	/** "设为本地默认"：把当前查看的 Provider 记为待启用目标，点保存才真正落盘。 */
	private void onActivate() {
		pendingActiveProvider = selectedProvider;
		refreshFieldsFromState();
		statusMessage = Text.translatable("mccf.config.activate_pending");
		statusColor = Colors.YELLOW;
	}

	private void onSync() {
		if (!ClientConfigState.get().hasReceivedSnapshot) {
			statusMessage = Text.translatable("mccf.localconfig.sync_no_data");
			statusColor = Colors.YELLOW;
			return;
		}
		config.copyPublicFieldsFrom(ClientConfigState.get());
		pendingActiveProvider = config.activeProvider;
		selectedProvider = config.activeProvider;
		if (listWidget != null) {
			listWidget.setSelectedProvider(selectedProvider);
		}
		userClearedApiKey = false;
		refreshFieldsFromState();
		statusMessage = Text.translatable("mccf.localconfig.sync_done");
		statusColor = Colors.YELLOW;
	}

	private void onClearApiKey() {
		apiKeyField.setText("");
		userClearedApiKey = true;
		statusMessage = Text.translatable("mccf.config.api_key_cleared");
		statusColor = Colors.YELLOW;
	}

	private void onSave() {
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

		config.activeProvider = pendingActiveProvider;

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
		if (!tabVisible) return;
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int centerX = screen.width / 2;

		Text providerTitle = Text.translatable(ClientConfigState.providerNameKey(selectedProvider));
		context.drawCenteredTextWithShadow(textRenderer, providerTitle, centerX, top - 14, Colors.WHITE);

		int screenBottom = screen.height - 20;
		Text providerDesc = Text.translatable("mccf.config.provider_hint." + selectedProvider);
		context.drawCenteredTextWithShadow(textRenderer, providerDesc, centerX, screenBottom - 58, Colors.LIGHT_GRAY);

		// 警告：选了"强制服务器模式"但服务器没装 MCCF 时翻译会完全失效。
		ClientOnlyModeManager.Override effectiveOverride =
				pendingOverride != null ? pendingOverride : ClientOnlyModeManager.getOverride();
		if (effectiveOverride == ClientOnlyModeManager.Override.FORCE_SERVER_MODE
				&& !ClientOnlyModeManager.isServerDetected()) {
			String warningText = Text.translatable("mccf.localconfig.warn_force_server_no_mod").getString();
			int wrapWidth = screen.width - 40;
			int warningX = centerX - wrapWidth / 2;
			int warningY = screenBottom - 46;
			String remaining = warningText;
			while (!remaining.isEmpty()) {
				String trimmed = textRenderer.trimToWidth(remaining, wrapWidth);
				if (trimmed.isEmpty() && !remaining.isEmpty()) {
					trimmed = remaining.substring(0, 1);
				}
				context.drawTextWithShadow(textRenderer, trimmed, warningX, warningY, 0xFF5555);
				warningY += textRenderer.fontHeight + 1;
				if (trimmed.length() >= remaining.length()) break;
				remaining = remaining.substring(trimmed.length());
			}
		}

		Text detectedLine = Text.translatable(
				ClientOnlyModeManager.isServerDetected() ? "mccf.localconfig.detected_yes" : "mccf.localconfig.detected_no");
		context.drawCenteredTextWithShadow(textRenderer, detectedLine, centerX, screenBottom - 30, Colors.LIGHT_GRAY);

		if (!statusMessage.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, centerX, screenBottom - 16, statusColor);
		}
	}
}
