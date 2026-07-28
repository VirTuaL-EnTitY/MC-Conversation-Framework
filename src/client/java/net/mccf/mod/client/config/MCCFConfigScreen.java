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
 * - "恢复默认端点" 清空当前 Provider 的自定义 endpoint，恢复官方默认。
 * - "获取模型" 用输入框里（可能尚未保存）的 Key/Endpoint 向服务端
 *   发起模型列表查询，结果显示在状态栏 + 完整列表打印到聊天栏。
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
	private Text statusMessage = Text.empty();

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
	}

	private void applyEditability() {
		providerButton.active = state.canEdit;
		saveButton.active = state.canEdit;
		if (!state.canEdit) {
			statusMessage = Text.translatable("mccf.config.no_permission");
		} else {
			statusMessage = Text.empty();
		}
	}

	/** 收到服务端最新快照后调用（由 MCCFClient 的网络接收器触发）。 */
	public void onSnapshotUpdated() {
		refreshFieldsFromState();
		applyEditability();
		statusMessage = Text.translatable("mccf.config.saved");
	}

	/** 收到服务端模型列表查询结果后调用（由 MCCFClient 的网络接收器触发）。 */
	public void onModelsResult(boolean success, String providerId, java.util.List<String> models, String error) {
		if (success) {
			statusMessage = Text.translatable("mccf.config.models_found", models.size());
			if (client != null && client.player != null) {
				client.player.sendMessage(Text.translatable("mccf.config.models_header", providerId), false);
				for (String model : models) {
					client.player.sendMessage(Text.literal("  " + model), false);
				}
			}
		} else {
			statusMessage = Text.translatable("mccf.config.fetch_failed", error);
		}
	}

	private void onResetEndpoint() {
		if (!state.canEdit) return;
		ClientProviderConfig pc = state.getOrCreate(state.activeProvider);
		pc.endpoint = "";
		pc.isCustomEndpoint = false;
		endpointField.setText("");
		statusMessage = Text.translatable("mccf.config.endpoint_reset");
	}

	private void onFetchModels() {
		if (!state.canEdit) return;
		if (!ClientPlayNetworking.canSend(RequestModelsPayload.ID)) {
			statusMessage = Text.translatable("mccf.config.not_connected");
			return;
		}

		com.google.gson.JsonObject requestRoot = new com.google.gson.JsonObject();
		requestRoot.addProperty("providerId", state.activeProvider);
		requestRoot.addProperty("apiKey", apiKeyField.getText());
		requestRoot.addProperty("endpoint", endpointField.getText());

		ClientPlayNetworking.send(new RequestModelsPayload(requestRoot.toString()));
		statusMessage = Text.translatable("mccf.config.fetching_models");
	}

	private void onExportLog() {
		String result = LogExporter.export(LogExporter.ExportMode.BOTH);
		statusMessage = Text.literal(result);
	}

	private void onSave() {
		if (!state.canEdit) return;
		if (!ClientPlayNetworking.canSend(UpdateConfigPayload.ID)) {
			statusMessage = Text.translatable("mccf.config.not_connected");
			return;
		}

		// 把当前输入框内容写回内存 state，再打包提交。
		ClientProviderConfig pc = state.getOrCreate(state.activeProvider);
		String enteredKey = apiKeyField.getText();
		if (!enteredKey.isBlank()) {
			pc.apiKey = enteredKey;
		}
		pc.model = modelField.getText();
		String enteredEndpoint = endpointField.getText();
		pc.endpoint = enteredEndpoint;
		pc.isCustomEndpoint = !enteredEndpoint.isBlank();

		ClientPlayNetworking.send(new UpdateConfigPayload(state.buildUpdateJson()));
		statusMessage = Text.translatable("mccf.config.saving");
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 16, Colors.WHITE);

		if (!state.hasReceivedSnapshot) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mccf.config.loading"),
					this.width / 2, this.height - 40, Colors.LIGHT_GRAY);
		} else if (!statusMessage.getString().isEmpty()) {
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
