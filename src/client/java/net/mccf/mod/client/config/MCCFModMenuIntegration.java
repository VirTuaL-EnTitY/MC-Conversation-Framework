package net.mccf.mod.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成：在 Mods 列表里给 MCCF 提供一个"配置"齿轮图标，点击后打开
 * {@link MCCFConfigScreen}。
 *
 * 这个类只有在 ModMenu 已安装时才会被加载（见 fabric.mod.json 里的
 * "modmenu" entrypoint，Fabric Loader 只会在对应 mod 存在时初始化该类型
 * 的入口点）。若玩家没装 ModMenu，配置界面仍然可以通过按键绑定打开
 * （见 MCCFClient 里的 KeyBinding 监听），完全不受影响。
 */
public class MCCFModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return MCCFConfigScreen::new;
	}
}
