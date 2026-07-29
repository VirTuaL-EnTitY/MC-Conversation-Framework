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
 *
 * 交互一致性：所有字段（包括运行模式）都遵循"点保存才生效"的约定——
 * 玩家点击 modeButton 只记录到 pendingOverride，不立即落盘，与 apiKey /
 * model / endpoint 的编辑模型保持一致。避免玩家切换模式后不点保存就退出
 * 导致困惑（"我明明切了模式怎么没变？"）。
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
	private ButtonWidget clearApiKeyButton;
	private Text statusMessage = Text.empty();
	/**
	 * 状态消息颜色。保存成功用绿色，其他提示用黄色。与 MCCFConfigScreen 同理。
	 */
	private int statusColor = Colors.YELLOW;
	/**
	 * 玩家是否点了"清除密钥"按钮。 onSave 时据此区分"未输入（保持原值）"
	 * 和"主动清空（设为空字符串）"两种意图。切换 Provider 时重置。
	 */
	private boolean userClearedApiKey = false;
	/**
	 * 暂存的运行模式选择，点保存才落盘。null 表示玩家还没动过 modeButton，
	 * 用当前已保存的 override 值。为什么改为暂存而不是立即生效：原来 modeButton
	 * 切换时直接 setOverride 并落盘，与 apiKey / model / endpoint 的"点保存才生效"
	 * 不一致，玩家切了模式不点保存就退出会困惑。现在统一为暂存模型。
	 */
	private ClientOnlyModeManager.Override pendingOverride = null;

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

		// modeButton 初始值优先用 pendingOverride（玩家已经切过但还没保存），
		// 否则用当前已保存的 override。这样 resize 触发 init() 重建时不会丢失
		// 玩家暂存的选择。
		ClientOnlyModeManager.Override initialMode =
				pendingOverride != null ? pendingOverride : ClientOnlyModeManager.getOverride();
		modeButton = CyclingButtonWidget.<ClientOnlyModeManager.Override>builder(mode ->
						Text.translatable("mccf.localconfig.mode." + mode.name().toLowerCase(java.util.Locale.ROOT)))
				.values(List.of(ClientOnlyModeManager.Override.values()))
				.initially(initialMode)
				.build(centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
						// 只暂存到 pendingOverride，不立即 setOverride 落盘——
						// 与 apiKey / model / endpoint 的"点保存才生效"保持一致。
						// 原来 modeButton 切换时直接 setOverride + save，玩家切了模式
						// 不点保存就退出会导致困惑（"我明明切了模式怎么没变？"，
						// 实际上已经变了但玩家以为没变因为没点保存）。
						Text.translatable("mccf.localconfig.mode"), (button, value) -> pendingOverride = value);
		addDrawableChild(modeButton);
		y += spacing;

		providerButton = CyclingButtonWidget.<String>builder(id ->
						Text.translatable(ClientConfigState.providerNameKey(id)))
				.values(java.util.List.of(ClientConfigState.PROVIDER_IDS))
				.initially(config.activeProvider)
				.build(centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
						Text.translatable("mccf.config.provider"), (button, value) -> onProviderChanged(value));
		addDrawableChild(providerButton);
		// provider_hint 文字行占用空间：在 providerButton 和 apiKeyField 之间额外留 12px
		// 给 hint 文字（hint 在 render 里画，不是 widget）。不加这个间距的话，providerButton
		// 底部(80) 到 apiKeyField 顶部(88) 只有 8px 空隙，而文字高约 9px，会重叠。
		y += 12;
		y += spacing;

		// apiKeyField 缩短到 216 像素，右侧留 44 像素放"清除密钥"按钮。
		// 与 MCCFConfigScreen 同理：输入框留空既可能"不改"也可能"清空"，
		// 用按钮 + userClearedApiKey 标记把"清空"变成显式动作。
		int apiKeyFieldWidth = 216;
		int clearBtnWidth = 40;
		apiKeyField = new TextFieldWidget(textRenderer, centerX - fieldWidth / 2, y, apiKeyFieldWidth, fieldHeight,
				Text.translatable("mccf.config.api_key"));
		apiKeyField.setMaxLength(512);
		apiKeyField.setPlaceholder(Text.translatable("mccf.config.api_key.placeholder"));
		// 密码遮盖：本地配置里的 Key 同样是敏感凭证，屏幕共享/截图时不该明文暴露。
		// 1.21.1 的 TextFieldWidget 没有 setRenderPasswordReveal（详见 MCCFConfigScreen
		// 同位置的踩坑说明），用 setRenderTextProvider 把字符替换成圆点实现遮盖。
		apiKeyField.setRenderTextProvider((text, firstCharacterIndex) ->
				Text.literal("•".repeat(text.length())).asOrderedText());
		addDrawableChild(apiKeyField);

		clearApiKeyButton = ButtonWidget.builder(
						Text.translatable("mccf.config.clear_api_key"), button -> onClearApiKey())
				.dimensions(centerX - fieldWidth / 2 + apiKeyFieldWidth + 4, y, clearBtnWidth, fieldHeight)
				.build();
		addDrawableChild(clearApiKeyButton);
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
		// 切换 Provider 时重置"清除密钥"标记——与 MCCFConfigScreen 同理。
		userClearedApiKey = false;
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
		// 清除按钮跟随 apiKeyField 的可编辑状态——Mock Provider 没有 Key 的概念。
		if (clearApiKeyButton != null) {
			clearApiKeyButton.active = !isMock;
		}
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
			statusColor = Colors.YELLOW;
			return;
		}
		config.copyPublicFieldsFrom(ClientConfigState.get());
		providerButton.setValue(config.activeProvider);
		// 同步后重置清除标记——同步拷贝的是公开字段，不影响玩家对 Key 的清除意图，
		// 但 refreshFieldsFromState 会重置输入框，清除标记也应该跟着重置避免残留。
		userClearedApiKey = false;
		refreshFieldsFromState();
		statusMessage = Text.translatable("mccf.localconfig.sync_done");
		statusColor = Colors.YELLOW;
	}

	/** "清除密钥"按钮回调：清空输入框并标记玩家意图为"主动清空"。 */
	private void onClearApiKey() {
		apiKeyField.setText("");
		userClearedApiKey = true;
		statusMessage = Text.translatable("mccf.config.api_key_cleared");
		statusColor = Colors.YELLOW;
	}

	private void onSave() {
		ClientProviderConfig pc = config.getOrCreate(config.activeProvider);
		String enteredKey = apiKeyField.getText();
		if (userClearedApiKey) {
			// 玩家点了"清除"按钮——显式清空，与 MCCFConfigScreen 同理。
			pc.apiKey = "";
		} else if (!enteredKey.isBlank()) {
			pc.apiKey = enteredKey;
		}
		userClearedApiKey = false;

		pc.model = modelField.getText();
		String enteredEndpoint = endpointField.getText();
		pc.endpoint = enteredEndpoint;
		pc.isCustomEndpoint = !enteredEndpoint.isBlank();

		// 运行模式：点保存才落盘——与其他字段保持一致。pendingOverride 为 null
		// 表示玩家没动过 modeButton，保持当前 override 不变。
		if (pendingOverride != null) {
			ClientOnlyModeManager.setOverride(pendingOverride);
		}

		config.save();
		statusMessage = Text.translatable("mccf.config.saved");
		// 保存成功用绿色，与 MCCFConfigScreen 统一。
		statusColor = Colors.GREEN;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 12, Colors.WHITE);

		// Provider 简短说明：在 providerButton 下方显示，与 MCCFConfigScreen 共用同一组
		// provider_hint 翻译键，保证两个 Screen 的 Provider 说明文案一致，不会因为
		// 各自维护一套导致描述对不上。位置 82 对应 init 里给 hint 腾出的 12px 间距区域。
		Text providerHint = Text.translatable("mccf.config.provider_hint." + config.activeProvider);
		context.drawCenteredTextWithShadow(textRenderer, providerHint,
				this.width / 2, 82, Colors.LIGHT_GRAY);

		// M6 警告：玩家选了"强制服务器模式"但服务器没装 MCCF 时，翻译功能完全失效。
		// 用红色警告文字提示玩家，避免玩家保存后才发现功能不可用。位置在 detectedLine
		// 上方——这是"需要玩家采取行动"的提示，优先级高于纯信息性的 detectedLine。
		//
		// 为什么用 pendingOverride 而不是 getOverride()：modeButton 改为暂存模型后，
		// 玩家切到 FORCE_SERVER_MODE 但还没保存时，getOverride() 返回旧值，警告不会显示——
		// 玩家看不到警告就保存，保存后才发现问题。用 pendingOverride 优先让警告立即反映
		// 玩家当前 UI 选择，符合"提示在前、保存在后"的交互。
		ClientOnlyModeManager.Override effectiveOverride =
				pendingOverride != null ? pendingOverride : ClientOnlyModeManager.getOverride();
		if (effectiveOverride == ClientOnlyModeManager.Override.FORCE_SERVER_MODE
				&& !ClientOnlyModeManager.isServerDetected()) {
			// 警告文案较长（中文约 40 字），一行画不下，用 trimToWidth 按像素宽度逐行
			// 分割后再用 drawTextWithShadow 逐行绘制。不用 drawTextWrapped 是因为该方法
			// 在 1.21.1 Yarn mapping 里的确切签名不确定，而 trimToWidth +
			// drawTextWithShadow(String) 是 HotbarSubtitleRenderer 里已经在用的稳定组合，
			// API 可靠性有保证。trimToWidth 按视觉宽度裁剪，对 CJK 双倍宽字符也能正确分行。
			// 颜色 0xFF5555 等价于 Formatting.RED 的颜色值——任务建议用 formatted(Formatting.RED)，
			// 但 getString() 提取纯文本会丢失 formatting，这里直接用 color 参数指定红色更直接。
			String warningText = Text.translatable("mccf.localconfig.warn_force_server_no_mod").getString();
			int wrapWidth = 280;
			int warningX = (this.width - wrapWidth) / 2;
			int warningY = this.height - 80;
			String remaining = warningText;
			while (!remaining.isEmpty()) {
				String trimmed = textRenderer.trimToWidth(remaining, wrapWidth);
				if (trimmed.isEmpty() && !remaining.isEmpty()) {
					// 单个字符就超宽（极端情况），强制取第一个字符避免死循环
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
		context.drawCenteredTextWithShadow(textRenderer, detectedLine, this.width / 2, this.height - 56, Colors.LIGHT_GRAY);

		if (!statusMessage.getString().isEmpty()) {
			// 用 statusColor 字段而不是硬编码 Colors.YELLOW——保存成功是绿色（onSave 里
			// 设置 statusColor = Colors.GREEN），其他过程提示是黄色。原来这里硬编码 YELLOW
			// 导致 onSave 设置的 GREEN 不生效，保存成功也显示黄色，无法和"正在保存"区分。
			context.drawCenteredTextWithShadow(textRenderer, statusMessage, this.width / 2, this.height - 40, statusColor);
		}
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}
}
