package net.mccf.mod.client.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.List;

/**
 * 模型选择子 Screen：用列表展示某个 Provider 拉取到的可用模型，
 * 点击一条会把它写回 {@link ClientConfigState} 里该 Provider 的 model 字段，
 * 然后返回父 Screen。父 Screen 重新 init 时会从 state 读取新值填入 modelField。
 *
 * 设计取舍：
 * - 不传 {@code Consumer<String>} 回调，而是直接修改 ClientConfigState——
 *   这样父 Screen 的字段不需要在子 Screen 关闭后保持引用，逻辑更简单。
 * - 不需要"保存"按钮——点击条目即应用，参考 Minecraft 选项菜单的常见交互。
 * - 用 {@link AlwaysSelectedEntryListWidget} 而不是一堆 ButtonWidget，
 *   因为模型数量可能很大（OpenAI 有几十个），列表可滚动更适合。
 */
public class ModelSelectionScreen extends Screen {

	private final Screen parent;
	private final String providerId;
	private final List<String> models;

	private ModelListWidget listWidget;

	public ModelSelectionScreen(Screen parent, String providerId, List<String> models) {
		super(Text.translatable("mccf.config.selectModel.title"));
		this.parent = parent;
		this.providerId = providerId;
		this.models = models;
	}

	@Override
	protected void init() {
		listWidget = new ModelListWidget(MinecraftClient.getInstance(),
				this.width, this.height, 40, this.height - 40, 14);
		// 当前已选中的模型置顶（如果有），方便用户快速找到正在用的那个
		String currentModel = ClientConfigState.get().getOrCreate(providerId).model;
		for (String model : models) {
			boolean isCurrent = model.equals(currentModel);
			listWidget.addEntry(new ModelEntry(model, isCurrent, this::onModelSelected));
		}
		addSelectableChild(listWidget);

		// 底部居中放一个"取消"按钮
		addDrawableChild(ButtonWidget.builder(Text.translatable("mccf.config.selectModel.cancel"),
						button -> close())
				.dimensions(this.width / 2 - 60, this.height - 28, 120, 20)
				.build());
	}

	private void onModelSelected(String model) {
		ClientConfigState.get().getOrCreate(providerId).model = model;
		close();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// 列表 widget 不在 addDrawableChild 里（只 addSelectableChild），
		// 这里手动渲染，让背景与列表绘制顺序符合预期。
		listWidget.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 16, Colors.WHITE);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	/** 列表 widget：负责管理 entry 的渲染与滚动。 */
	private static class ModelListWidget extends AlwaysSelectedEntryListWidget<ModelEntry> {
		public ModelListWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
			// 1.21.1 上父类构造器是 5 参数（无 itemHeight）；itemHeight 参数保留在
			// 本构造器签名里是为了 API 兼容/未来扩展，目前忽略不用。
			super(client, width, height, top, bottom);
		}

		/** 把父类的 protected addEntry 暴露给 Screen 使用。返回值为新加入条目的索引。 */
		@Override
		public int addEntry(ModelEntry entry) {
			return super.addEntry(entry);
		}
	}

	/** 单条模型条目。点击即应用选择并返回父 Screen。 */
	private static class ModelEntry extends AlwaysSelectedEntryListWidget.Entry<ModelEntry> {
		private final String model;
		private final boolean isCurrent;
		private final java.util.function.Consumer<String> onSelected;

		ModelEntry(String model, boolean isCurrent, java.util.function.Consumer<String> onSelected) {
			this.model = model;
			this.isCurrent = isCurrent;
			this.onSelected = onSelected;
		}

		@Override
		public Text getNarration() {
			return Text.literal(model);
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
						   int mouseX, int mouseY, boolean hovered, float delta) {
			// 当前选中的模型用黄色高亮，其余白色
			int color = isCurrent ? Colors.YELLOW : Colors.WHITE;
			// 左侧留 6px 缩进，看着更舒服
			context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
					Text.literal(model), x + 6, y + 2, color);
			if (isCurrent) {
				// 在右侧加个标记（"当前"），让用户一眼看出当前选中
				Text mark = Text.translatable("mccf.config.selectModel.current");
				context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
						mark, x + entryWidth - 6 - MinecraftClient.getInstance().textRenderer.getWidth(mark),
						y + 2, Colors.YELLOW);
			}
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			onSelected.accept(model);
			return true;
		}
	}
}
