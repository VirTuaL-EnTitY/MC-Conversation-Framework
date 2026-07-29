package net.mccf.mod.client.config;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.mccf.mod.client.util.LogExporter;
import net.mccf.mod.network.RequestConfigPayload;
import net.mccf.mod.network.RequestModelsPayload;
import net.mccf.mod.network.UpdateConfigPayload;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

/**
 * MCCF 的游戏内配置界面。两个入口都指向这个 Screen：
 * - ModMenu 集成（{@code MCCFModMenuIntegration}）
 * - 按键绑定直接呼出（见 {@code MCCFClient} 里的按键监听）
 *
 * 所有界面文字均通过 {@link Text#translatable(String)} 引用语言文件
 * （见 assets/mccf/lang/en_us.json、zh_cn.json），跟随玩家客户端语言设置
 * 自动切换，不是硬编码英文。
 *
 * 行为：
 * - 打开时向服务端请求最新配置快照（{@link RequestConfigPayload}），
 *   在收到 {@code ConfigSnapshotPayload} 之前显示"加载中"。
 * - 若接收方不是 op（{@code canEdit == false}），所有输入控件禁用，
 *   API Key 显示为空（服务端本来就不会下发真实 Key 给非 op），
 *   界面仅用于查看"当前生效的 Provider 是什么"。
 * - 点击"保存"会把当前编辑状态打包发给服务端；服务端应用后会回发
 *   最新快照，Screen 收到后刷新显示，等于是"服务端确认过的状态"，
 *   不是客户端本地乐观更新——避免显示一个实际没有生效的假状态。
 * - "恢复默认端点" 显式标记 endpointAction=RESET_DEFAULT，保存时让服务端
 *   恢复官方默认地址（见 {@link ClientProviderConfig.EndpointAction} 三态说明）。
 * - "获取模型" 用输入框里（可能尚未保存）的 Key/Endpoint 向服务端
 *   发起模型列表查询，成功后弹出 {@link ModelSelectionScreen} 让玩家可视化
 *   选择——不再打印到聊天栏让玩家手动复制粘贴。
 * - "清除密钥" 显式把 apiKey 置空。输入框留空既可能表示"不改"也可能表示
 *   "清空"，用按钮 + boolean 标记把"清空"变成显式动作，消除歧义。
 * - "导出日志" 把 MCCF 相关日志 + 完整 latest.log 导出到
 *   <游戏目录>/mccf-exports/，纯本地操作，不需要联网/联服务器。
 */
public class MCCFConfigScreen extends Screen {

	private final Screen parent;
	private final ClientConfigState state = ClientConfigState.get();

	private CyclingButtonWidget<String> providerButton;
	private TextFieldWidget apiKeyField;
	private TextFieldWidget modelField;
	private TextFieldWidget endpointField;
	private ButtonWidget saveButton;
	private ButtonWidget fetchModelsButton;
	private ButtonWidget clearApiKeyButton;
	private Text statusMessage = Text.empty();
	/**
	 * 状态消息的颜色。成功类消息用绿色，警告/错误/普通提示用黄色。
	 * 用字段而不是硬编码在 render 里，是因为不同事件设置的消息语义不同——
	 * onSnapshotUpdated 是"服务端已确认保存成功"应该绿色，其他多为过程提示。
	 */
	private int statusColor = Colors.YELLOW;
	/**
	 * 玩家是否点了"清除密钥"按钮。 onSave 时据此区分"未输入（保持原值）"
	 * 和"主动清空（设为空字符串）"两种意图。切换 Provider 时重置。
	 */
	private boolean userClearedApiKey = false;

	public MCCFConfigScreen(Screen parent) {
		super(Text.translatable("mccf.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// 关键修复：init() 不仅在首次打开时调用，Minecraft 在玩家调整游戏
		// 窗口大小时也会对所有当前打开的 Screen 重新调用一次 init()（这是
		// 原版的 resize 处理流程）。如果玩家尚未进入任何世界/连接任何服务器
		// 就打开了本界面，这里原来无条件调用 ClientPlayNetworking.send()
		// 会因为没有活跃连接而抛出异常，而这个异常发生在 init() 里、又是
		// resize 流程的一部分，未被捕获，会直接导致客户端崩溃。
		// ClientPlayNetworking.canSend() 是 Fabric 官方提供的、专门用于
		// 判断"当前是否处于游戏中、可以发送该 payload"的方法。
		if (ClientPlayNetworking.canSend(RequestConfigPayload.ID)) {
			ClientPlayNetworking.send(new RequestConfigPayload(true));
		}

		int centerX = this.width / 2;
		int y = 40;
		int fieldWidth = 260;
		int fieldHeight = 20;
		int spacing = 28;

		providerButton = CyclingButtonWidget.<String>builder(id ->
						Text.translatable(ClientConfigState.providerNameKey(id)))
				.values(java.util.List.of(ClientConfigState.PROVIDER_IDS))
				.initially(state.activeProvider)
				.build(centerX - fieldWidth / 2, y, fieldWidth, fieldHeight,
						Text.translatable("mccf.config.provider"), (button, value) -> onProviderChanged(value));
		addDrawableChild(providerButton);
		y += spacing;

		// apiKeyField 缩短到 216 像素，右侧留 44 像素放"清除密钥"按钮。
		// 为什么需要清除按钮：输入框留空既可能表示"我不改 Key"也可能表示"我要清空 Key"，
		// onSave 逻辑无法区分这两种意图——原来用 isBlank() 判断默认走"不改"，玩家想
		// 清空已保存的 Key 只能干瞪眼。这里用按钮 + userClearedApiKey 标记把"清空"
		// 变成一个显式动作，消除歧义。
		int apiKeyFieldWidth = 216;
		int clearBtnWidth = 40;
		apiKeyField = new TextFieldWidget(textRenderer, centerX - fieldWidth / 2, y, apiKeyFieldWidth, fieldHeight,
				Text.translatable("mccf.config.api_key"));
		apiKeyField.setMaxLength(512);
		apiKeyField.setPlaceholder(Text.translatable("mccf.config.api_key.placeholder"));
		// 密码遮盖：API Key 是敏感凭证，不应在屏幕共享/截图时明文暴露给旁观者。
		// 1.21.1 的 TextFieldWidget 没有 setRenderPasswordReveal 这种"一行开启
		// 密码框"的便捷开关（那是 1.20.x 及之前的 API，1.21.1 已移除），
		// 只能通过 setRenderTextProvider 传一个把字符替换成圆点的 BiFunction
		// 来实现同等效果。这里用 '•'（U+2022）作为遮盖字符——比 '*' 更接近
		// 原版密码框的视觉风格。
		// 代价：失去原版"按住可短暂显示明文"的交互（需要自己实现，本项目用不到）。
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
		y += spacing;

		ButtonWidget resetEndpointButton = ButtonWidget.builder(
						Text.translatable("mccf.config.reset_endpoint"), button -> onResetEndpoint())
				.dimensions(centerX - fieldWidth / 2, y, 125, 20)
				.build();
		addDrawableChild(resetEndpointButton);

		fetchModelsButton = ButtonWidget.builder(
						Text.translatable("mccf.config.fetch_models"), button -> onFetchModels())
				.dimensions(centerX - fieldWidth / 2 + 135, y, 125, 20)
				.build();
		addDrawableChild(fetchModelsButton);
		y += spacing + 8;

		saveButton = ButtonWidget.builder(Text.translatable("mccf.config.save"), button -> onSave())
				.dimensions(centerX - fieldWidth / 2, y, 84, 20)
				.build();
		addDrawableChild(saveButton);

		ButtonWidget exportLogButton = ButtonWidget.builder(
						Text.translatable("mccf.config.export_log"), button -> onExportLog())
				.dimensions(centerX - fieldWidth / 2 + 88, y, 84, 20)
				.build();
		addDrawableChild(exportLogButton);

		ButtonWidget doneButton = ButtonWidget.builder(Text.translatable("mccf.config.close"), button -> close())
				.dimensions(centerX - fieldWidth / 2 + 176, y, 84, 20)
				.build();
		addDrawableChild(doneButton);
		y += spacing;

		// 纯客户端模式入口：始终显示、始终可点，与上面这套"服务端权威配置"完全独立——
		// 不管当前玩家是不是 op，本地翻译设置都可以自由编辑。详见 ClientOnlyConfigScreen。
		ButtonWidget clientOnlyButton = ButtonWidget.builder(
						Text.translatable("mccf.config.client_only_settings"), button -> openClientOnlySettings())
				.dimensions(centerX - fieldWidth / 2, y, fieldWidth, 20)
				.build();
		addDrawableChild(clientOnlyButton);

		refreshFieldsFromState();
		applyEditability();
	}

	private void openClientOnlySettings() {
		if (client != null) {
			client.setScreen(new ClientOnlyConfigScreen(this));
		}
	}

	private void onProviderChanged(String newProviderId) {
		state.activeProvider = newProviderId;
		// 切换 Provider 时重置"清除密钥"标记——每个 Provider 的 Key 独立保存，
		// 玩家在 A Provider 点了清除不意味着切到 B Provider 也想清除 B 的 Key。
		userClearedApiKey = false;
		refreshFieldsFromState();
	}

	/** 把 state 里当前选中 Provider 的数据填入输入框。切换 Provider 按钮时也会调用。 */
	private void refreshFieldsFromState() {
		ClientProviderConfig pc = state.getOrCreate(state.activeProvider);
		apiKeyField.setText(pc.apiKey == null ? "" : pc.apiKey);
		modelField.setText(pc.model == null ? "" : pc.model);
		endpointField.setText(pc.endpoint == null ? "" : pc.endpoint);

		boolean isMock = state.activeProvider.equals("mock");
		boolean isDeepL = state.activeProvider.equals("deepl");
		boolean supportsModelList = !ClientConfigState.NO_MODEL_LIST_SUPPORT.contains(state.activeProvider);

		apiKeyField.setEditable(state.canEdit && !isMock);
		endpointField.setEditable(state.canEdit && !isMock);
		modelField.setEditable(state.canEdit && !isMock && !isDeepL);
		if (fetchModelsButton != null) {
			fetchModelsButton.active = state.canEdit && supportsModelList;
		}
		// 清除按钮跟随 apiKeyField 的可编辑状态——Mock Provider 没有 Key 的概念，
		// 非 op 也不能改，这两种情况下清除按钮应该禁用。
		if (clearApiKeyButton != null) {
			clearApiKeyButton.active = state.canEdit && !isMock;
		}
	}

	private void applyEditability() {
		providerButton.active = state.canEdit;
		saveButton.active = state.canEdit;
		if (!state.canEdit) {
			statusMessage = Text.translatable("mccf.config.no_permission");
			statusColor = Colors.YELLOW;
		} else {
			statusMessage = Text.empty();
		}
	}

	/** 收到服务端最新快照后调用（由 MCCFClient 的网络接收器触发）。 */
	public void onSnapshotUpdated() {
		refreshFieldsFromState();
		applyEditability();
		// 保存成功用绿色，与"正在保存""未连接"等黄色过程提示区分开，
		// 让玩家一眼看出"这次保存确实生效了"而不是还在处理中。
		statusMessage = Text.translatable("mccf.config.saved");
		statusColor = Colors.GREEN;
	}

	/**
	 * 收到服务端模型列表查询结果后调用（由 MCCFClient 的网络接收器触发）。
	 *
	 * 成功且列表非空时弹出 {@link ModelSelectionScreen} 让玩家可视化选择，而不是
	 * 打印到聊天栏——玩家需要可视化地浏览几十个模型名、一眼看到当前选中项
	 * （黄色高亮）、一键应用到 modelField。打印到聊天栏的话玩家还得手动复制
	 * 粘贴模型名，体验糟糕且容易拼错。
	 */
	public void onModelsResult(boolean success, String providerId, java.util.List<String> models, String error) {
		if (success) {
			if (models.isEmpty()) {
				// 服务端成功返回了，但模型列表是空的——可能是 API Key 无权限、
				// 或者该 Provider 确实没有任何可用模型。仍然提示玩家，不要静默成功。
				statusMessage = Text.translatable("mccf.config.models_empty");
				statusColor = Colors.YELLOW;
				return;
			}
			// 这里设置的 statusMessage 玩家几乎看不到——下一行立刻 setScreen 切到
			// ModelSelectionScreen，父 Screen 不再渲染。保留它只是作为"切回父 Screen
			// 后如果 onSnapshotUpdated 还没来得及刷新"的兜底文案。旧的 models_found
			// 文案是"已打印到聊天栏"，但现在改成了弹出选择界面，文案对不上，所以换成
			// models_opened。models_opened 不带 %s 参数——玩家要的是"接下来怎么做"
			// 的提示，不是模型数量（数量在 ModelSelectionScreen 列表里一眼能看到）。
			statusMessage = Text.translatable("mccf.config.models_opened");
			statusColor = Colors.YELLOW;
			if (client != null) {
				client.setScreen(new ModelSelectionScreen(this, providerId, models, this::setModelFromSelection));
			}
		} else {
			statusMessage = Text.translatable("mccf.config.fetch_failed", error);
			statusColor = Colors.YELLOW;
		}
	}

	/**
	 * ModelSelectionScreen 选中模型后的回调：把选中的模型名填入 modelField，
	 * 并同步更新 state——这样即使父 Screen 重新 init() 时 RequestConfigPayload
	 * 的异步回应覆盖了 state，onSave 时也能从 modelField 读到玩家选中的值。
	 *
	 * 注意：ModelSelectionScreen 关闭时父 Screen 会重新 init()，modelField 会被
	 * 重建并经 refreshFieldsFromState() 从 state 回读。这里的 setText 主要覆盖
	 * "init() 还没来得及跑就被玩家看到"的极短窗口，以及作为 state 被覆盖后的
	 * 一道保险——onSave 永远从 modelField.getText() 读取，所以只要 modelField
	 * 的文本是对的，保存就不会丢。
	 */
	public void setModelFromSelection(String model) {
		if (modelField != null) {
			modelField.setText(model);
		}
		state.getOrCreate(state.activeProvider).model = model;
	}

	private void onResetEndpoint() {
		if (!state.canEdit) return;
		ClientProviderConfig pc = state.getOrCreate(state.activeProvider);
		pc.endpoint = "";
		pc.isCustomEndpoint = false;
		// 显式标记为 RESET_DEFAULT——保存时 buildUpdateJson 会发送 resetEndpoint=true，
		// 让服务端恢复官方默认地址。原来只清空输入框 + isCustomEndpoint=false，
		// buildUpdateJson 靠 isCustomEndpoint 派生 resetEndpoint，无法和"玩家没动过"
		// 区分。现在用三态显式表达"我要恢复默认"。
		pc.endpointAction = ClientProviderConfig.EndpointAction.RESET_DEFAULT;
		endpointField.setText("");
		statusMessage = Text.translatable("mccf.config.endpoint_reset");
		statusColor = Colors.YELLOW;
	}

	/** "清除密钥"按钮回调：清空输入框并标记玩家意图为"主动清空"。 */
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
		requestRoot.addProperty("providerId", state.activeProvider);
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

		// 把当前输入框内容写回内存 state，再打包提交。
		ClientProviderConfig pc = state.getOrCreate(state.activeProvider);
		String enteredKey = apiKeyField.getText();
		if (userClearedApiKey) {
			// 玩家点了"清除"按钮——显式清空，即便输入框里后来又被填了内容，
			// 也以清除意图为准（通常玩家点完清除不会再去手填，但以防万一按显式动作处理）。
			pc.apiKey = "";
		} else if (!enteredKey.isBlank()) {
			pc.apiKey = enteredKey;
		}
		// 否则 enteredKey 为空且没点清除：保持原值不变，不写 pc.apiKey。
		// 保存后重置标记，避免下次保存时误判——但如果保存失败（网络问题），
		// 玩家重新点保存时清除意图会丢失。这是可接受的折中：保存失败的提示
		// 已经告诉玩家"没保存成功"，玩家自然会再点一次清除按钮。
		userClearedApiKey = false;

		pc.model = modelField.getText();
		String enteredEndpoint = endpointField.getText();
		pc.endpoint = enteredEndpoint;
		// endpoint 三态：根据输入框当前内容 + 是否点过"恢复默认"按钮来推断玩家意图。
		// 输入框有内容 → CUSTOM（不管之前点没点过恢复默认，有内容就是自定义）。
		// 输入框为空 + 点过恢复默认 → RESET_DEFAULT（发 resetEndpoint=true 让服务端恢复默认）。
		// 输入框为空 + 没点过恢复默认 → UNCHANGED（不发 endpoint 字段，服务端保持原值）。
		// 这与旧逻辑（isCustomEndpoint + endpoint.isBlank() 自动派生 resetEndpoint）的关键
		// 区别：旧逻辑下"玩家切到某 Provider 看一眼"也会触发"恢复默认"，把自定义 endpoint 冲掉。
		if (!enteredEndpoint.isBlank()) {
			pc.endpointAction = ClientProviderConfig.EndpointAction.CUSTOM;
			pc.isCustomEndpoint = true;
		} else if (pc.endpointAction != ClientProviderConfig.EndpointAction.RESET_DEFAULT) {
			pc.endpointAction = ClientProviderConfig.EndpointAction.UNCHANGED;
			pc.isCustomEndpoint = false;
		}
		// 如果是 RESET_DEFAULT，onResetEndpoint 已经设置好了，这里保持不动。

		ClientPlayNetworking.send(new UpdateConfigPayload(state.buildUpdateJson()));
		statusMessage = Text.translatable("mccf.config.saving");
		statusColor = Colors.YELLOW;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 16, Colors.WHITE);

		// Provider 简短说明：在 providerButton 下方显示一句话，帮助玩家理解每个
		// Provider 的特点（是否需要 Key、是否支持上下文等），不用查文档也能选对。
		// 用 translatable 让说明跟随客户端语言切换，不硬编码。语言键统一用
		// mccf.config.provider_hint.<id> 前缀，与 ClientOnlyConfigScreen 共用同一组
		// 翻译，避免两个 Screen 各维护一套 Provider 说明导致文案不一致。
		Text providerDesc = Text.translatable(
				"mccf.config.provider_hint." + state.activeProvider);
		context.drawCenteredTextWithShadow(textRenderer, providerDesc,
				this.width / 2, 70, Colors.LIGHT_GRAY);

		if (!state.hasReceivedSnapshot) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mccf.config.loading"),
					this.width / 2, this.height - 40, Colors.LIGHT_GRAY);
		} else if (!statusMessage.getString().isEmpty()) {
			// 用 statusColor 而不是硬编码黄色——保存成功是绿色，其他提示是黄色。
			context.drawCenteredTextWithShadow(textRenderer, statusMessage,
					this.width / 2, this.height - 40, statusColor);
		}
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}
}
