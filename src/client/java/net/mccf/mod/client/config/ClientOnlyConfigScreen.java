package net.mccf.mod.client.config;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.network.RequestConfigPayload;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.List;

/**
 * "本地翻译设置" 子界面：从 {@link MCCFConfigScreen} 的按钮打开。
 *
 * 与主配置界面（{@link MCCFConfigScreen}）的关键区别：
 * - 这里的配置只影响玩家自己本地看到的翻译结果，不经过服务端，也不需要
 *   op 权限——任何玩家都能自由编辑（见 {@link ClientOnlyTranslationConfig}）。
 * - 多了一个"运行模式"选择器（{@link ClientOnlyModeManager.Override}）：
 *   自动检测 / 强制纯客户端 / 强制服务器模式。
 * - "从服务器同步"按钮只拷贝 Provider 选择 / 模型名 / Endpoint 这几个公开
 *   字段，不会拷贝 API Key（即使当前是 op 也一样，见
 *   {@link ClientOnlyTranslationConfig#copyPublicFieldsFrom}）——本地 Key
 *   必须由玩家自己填写。
 */
public class ClientOnlyConfigScreen extends Screen {

	private final Screen parent;
	private final ClientOnlyTranslationConfig config = ClientOnlyTranslationConfig.get();

	private CyclingButtonWidget<String> providerButton;
	private CyclingButtonWidget<ClientOnlyModeManager.Override> modeButton;
	private TextFieldWidget apiKeyField;
	private TextFieldWidget modelField;
	private TextFieldWidget endpointField;
	private ButtonWidget syncButton;
	private Text statusMessage = Text.empty();

	public ClientOnlyConfigScreen(Screen parent) {
		super(Text.translatable("mccf.localconfig.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int y = 32;
		int fieldWidth = 260;
		int fieldHeight = 20;
		int spacing = 28;

		modeButton = CyclingButtonWidget.<ClientOnlyModeManager.Override>builder(mode ->
						Text.translatable("mccf.localconfig.mode." + mode.name().toLowerCase(java.util.Locale.ROOT)))
				.values(List.of(ClientOnlyModeManager.Override.values()))
				.initially(ClientOnlyModeManager.getOverride())
				.build(centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
						Text.translatable("mccf.localconfig.mode"), (button, value) -> ClientOnlyModeManager.setOverride(value));
		addDrawableChild(modeButton);
		y += spacing;

		providerButton = CyclingButtonWidget.<String>builder(id ->
						Text.translatable(ClientConfigState.providerNameKey(id)))
				.values(java.util.List.of(ClientConfigState.PROVIDER_IDS))
				.initially(config.activeProvider)
				.build(centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
						Text.translatable("mccf.config.provider"), (button, value) -> onProviderChanged(value));
		addDrawableChild(providerButton);
		y += spacing;

		apiKeyField = new TextFieldWidget(textRenderer, centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
				Text.translatable("mccf.config.api_key"));
		apiKeyField.setMaxLength(512);
		apiKeyField.setPlaceholder(Text.translatable("mccf.config.api_key.placeholder"));
		addDrawableChild(apiKeyField);
		y += spacing;

		modelField = new TextFieldWidget(textRenderer, centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
				Text.translatable("mccf.config.model"));
		modelField.setMaxLength(128);
		modelField.setPlaceholder(Text.translatable("mccf.config.model.placeholder"));
		addDrawableChild(modelField);
		y += spacing;

		endpointField = new TextFieldWidget(textRenderer, centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
				Text.translatable("mccf.config.endpoint"));
		endpointField.setMaxLength(256);
		endpointField.setPlaceholder(Text.translatable("mccf.config.endpoint.placeholder"));
		addDrawableChild(endpointField);
		y += spacing + 8;

		syncButton = ButtonWidget.builder(Text.translatable("mccf.localconfig.sync"), button -> onSync())
				.dimensions(centerX - fieldWidth / 2, y, 260, 20)
				.build();
		addDrawableChild(syncButton);
		y += spacing;

		ButtonWidget saveButton = ButtonWidget.builder(Text.translatable("mccf.config.save"), button -> onSave())
				.dimensions(centerX - fieldWidth / 2, y, 125, 20)
				.build();
		addDrawableChild(saveButton);

		ButtonWidget doneButton = ButtonWidget.builder(Text.translatable("mccf.config.close"), button -> close())
				.dimensions(centerX - fieldWidth / 2 + 135, y, 125, 20)
				.build();
		addDrawableChild(doneButton);

		refreshFieldsFromState();
		refreshSyncButtonState();
	}

	private void onProviderChanged(String newProviderId) {
		config.activeProvider = newProviderId;
		refreshFieldsFromState();
	}

	private void refreshFieldsFromState() {
		ClientProviderConfig pc = config.getOrCreate(config.activeProvider);
		apiKeyField.setText(pc.apiKey == null ? "" : pc.apiKey);
		modelField.setText(pc.model == null ? "" : pc.model);
		endpointField.setText(pc.endpoint == null ? "" : pc.endpoint);

		boolean isMock = config.activeProvider.equals("mock");
		boolean isDeepL = config.activeProvider.equals("deepl");
		apiKeyField.setEditable(!isMock);
		endpointField.setEditable(!isMock);
		modelField.setEditable(!isMock && !isDeepL);
	}

	private void refreshSyncButtonState() {
		boolean canSync = ClientPlayNetworking.canSend(RequestConfigPayload.ID)
				&& ClientConfigState.get().hasReceivedSnapshot;
		if (syncButton != null) {
			syncButton.active = canSync;
		}
	}

	private void onSync() {
		if (!ClientConfigState.get().hasReceivedSnapshot) {
			statusMessage = Text.translatable("mccf.localconfig.sync_no_data");
			return;
		}
		config.copyPublicFieldsFrom(ClientConfigState.get());
		providerButton.setValue(config.activeProvider);
		refreshFieldsFromState();
		statusMessage = Text.translatable("mccf.localconfig.sync_done");
	}

	private void onSave() {
		ClientProviderConfig pc = config.getOrCreate(config.activeProvider);
		String enteredKey = apiKeyField.getText();
		if (!enteredKey.isBlank()) {
			pc.apiKey = enteredKey;
		}
		pc.model = modelField.getText();
		String enteredEndpoint = endpointField.getText();
		pc.endpoint = enteredEndpoint;
		pc.isCustomEndpoint = !enteredEndpoint.isBlank();

		config.save();
		statusMessage = Text.translatable("mccf.config.saved");
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 12, Colors.WHITE);

		Text detectedLine = Text.translatable(
				ClientOnlyModeManager.isServerDetected() ? "mccf.localconfig.detected_yes" : "mccf.localconfig.detected_no");
		context.drawCenteredTextWithShadow(textRenderer, detectedLine, this.width / 2, this.height - 56, Colors.LIGHT_GRAY);

		if (!statusMessage.getString().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, this.width / 2, this.height - 40, Colors.YELLOW);
		}
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}
}
