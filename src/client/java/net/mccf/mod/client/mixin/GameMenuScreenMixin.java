package net.mccf.mod.client.mixin;

import net.mccf.mod.client.config.ChatHistoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给游戏暂停菜单（Esc 菜单）注入"聊天历史记录"按钮。
 *
 * 职责边界：
 * - 只管：在 GameMenuScreen 的 initWidgets 末尾追加一个按钮，点击后打开 ChatHistoryScreen
 * - 不管：ChatHistoryScreen 内部如何渲染历史记录（那是 ChatHistoryScreen 的职责）
 *
 * 为什么用 Mixin 而不是 ScreenEvents.afterInit：
 * ScreenEvents.afterInit 的回调拿到的是 Screen 对象，但 Screen.addDrawableChild 是
 * protected 方法，外部包无法调用——Fabric API 没有提供 public 的 widget 添加接口，
 * 直接往 Screens.getButtons() 返回的 list 里 add 只会加到 Fabric 注入的额外存储里，
 * 不会被注册到原版的渲染/点击管线。Mixin 把代码注入到 GameMenuScreen 内部，
 * 此时调用 addDrawableChild 是合法的（protected 在子类内部可访问），这是 Fabric
 * 模组给原版界面加按钮的标准做法。
 *
 * 为什么方法名是 initWidgets：1.20.5 起 Screen.init() 改名为 initWidgets()，
 * 1.21.1 沿用此名。如果升级到更早的版本（1.20.4 及以下）需要改回 init。
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

	protected GameMenuScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "initWidgets", at = @At("TAIL"))
	private void mccf$addChatHistoryButton(CallbackInfo ci) {
		// 按钮位置：在原版按钮组下方追加一行。原版暂停菜单左列从 height/4 - 16 开始，
		// 每行间距 24，最后一行（Open to LAN / Quit Game）大约在 height/4 + 80。
		// 这里在 height/4 + 104 追加一行，和原版最后一行留 24 像素间距，不重叠。
		// 宽度 204 跨两列（原版每列宽 98，中间间距 8），视觉上是一个完整的横条按钮。
		int buttonX = this.width / 2 - 102;
		int buttonY = this.height / 4 + 104;
		this.addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.button"),
						button -> MinecraftClient.getInstance().setScreen(new ChatHistoryScreen(this)))
				.dimensions(buttonX, buttonY, 204, 20)
				.build());
	}
}
