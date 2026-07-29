package net.mccf.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.mccf.mod.MCCF;
import net.mccf.mod.dictionary.WorldDictionary;
import net.mccf.mod.translation.TranslationService;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /mccf} 管理命令树。
 *
 * 子命令：
 *   /mccf status                        查看当前活跃 Conversation 数量、Provider 状态
 *   /mccf provider list                 列出所有已注册的翻译 Provider
 *   /mccf provider set <id>             切换当前使用的翻译 Provider
 *   /mccf dictionary add <term> <lang> <translation>   添加词典条目
 *   /mccf dictionary remove <term>      移除词典条目
 *   /mccf reload                        重新加载配置与词典
 *
 * 均要求 op 权限等级 2（服务器管理员），因为这些操作会影响所有玩家的翻译体验。
 */
public class MCCFCommand {

	private static final int PERMISSION_LEVEL = 2;

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(literal("mccf")
						.requires(source -> source.hasPermissionLevel(PERMISSION_LEVEL))
						.then(literal("status").executes(MCCFCommand::status))
						.then(literal("provider")
								.then(literal("list").executes(MCCFCommand::listProviders))
								.then(literal("set")
										.then(argument("id", StringArgumentType.word())
												.executes(MCCFCommand::setProvider))))
						.then(literal("dictionary")
								.then(literal("add")
										.then(argument("term", StringArgumentType.string())
												.then(argument("lang", StringArgumentType.word())
														.then(argument("translation", StringArgumentType.greedyString())
																.executes(MCCFCommand::addDictionaryEntry)))))
								.then(literal("remove")
										.then(argument("term", StringArgumentType.string())
												.executes(MCCFCommand::removeDictionaryEntry))))
						.then(literal("reload").executes(MCCFCommand::reload))
				));
	}

	private static int status(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		int activeConversations = MCCF.getConversationManager().getActiveConversationCount();
		String provider = MCCF.getTranslationService().getActiveProvider() != null
				? MCCF.getTranslationService().getActiveProvider().getDisplayName()
				: "(none)";

		ctx.getSource().sendFeedback(() -> Text.literal(
				"MCCF status | active conversations: %d | provider: %s".formatted(activeConversations, provider)
		), false);
		return 1;
	}

	private static int listProviders(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		TranslationService service = MCCF.getTranslationService();
		StringBuilder sb = new StringBuilder("Registered providers:");
		service.getProviders().forEach((id, provider) ->
				sb.append("\n - ").append(id).append(": ").append(provider.getDisplayName()));
		ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
		return 1;
	}

	private static int setProvider(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		boolean success = MCCF.getTranslationService().setActiveProvider(id);
		if (success) {
			MCCF.getTranslationService().clearCache();
			// 把切换后的 Provider 写回 config 并落盘，保证重启后仍然生效。
			// 为什么命令修改也要持久化：管理员用命令切换 Provider 时期望重启后
			// 仍然生效，否则和通过配置界面修改的行为不一致（界面修改走
			// ConfigSyncHandler 会调用 config.save()，命令路径也应如此）。
			MCCF.getConfig().activeProvider = id;
			MCCF.getConfig().save();
			ctx.getSource().sendFeedback(() -> Text.translatable("command.mccf.provider.set", id), true);
			return 1;
		} else {
			ctx.getSource().sendError(Text.literal("Unknown provider: " + id));
			return 0;
		}
	}

	private static int addDictionaryEntry(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		String term = StringArgumentType.getString(ctx, "term");
		String lang = StringArgumentType.getString(ctx, "lang");
		String translation = StringArgumentType.getString(ctx, "translation");

		WorldDictionary dictionary = MCCF.getWorldDictionary();
		dictionary.addEntry(term, lang, translation);
		MCCF.getTranslationService().clearCache();

		ctx.getSource().sendFeedback(() -> Text.translatable("command.mccf.dictionary.added", term, translation), true);
		return 1;
	}

	private static int removeDictionaryEntry(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		String term = StringArgumentType.getString(ctx, "term");
		boolean removed = MCCF.getWorldDictionary().removeEntry(term);
		if (removed) {
			MCCF.getTranslationService().clearCache();
			ctx.getSource().sendFeedback(() -> Text.translatable("command.mccf.dictionary.removed", term), true);
			return 1;
		} else {
			ctx.getSource().sendError(Text.literal("No such dictionary entry: " + term));
			return 0;
		}
	}

	private static int reload(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		// 委托给 MCCF.reload() 完成完整的读盘 + 重建 Provider + 清缓存流程。
		// 之前只调 clearCache() 等于没 reload——管理员改了 JSON 文件不会生效。
		MCCF.reload();
		ctx.getSource().sendFeedback(() -> Text.translatable("command.mccf.reload"), true);
		return 1;
	}
}
