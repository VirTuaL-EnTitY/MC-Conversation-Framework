package net.mccf.mod.translation.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 可插拔翻译 Provider 接口。
 *
 * 任何翻译后端（OpenAI、Claude、Gemini、DeepL、Google Translate、
 * 本地 Ollama/LM Studio 等）都通过实现这个接口接入 MCCF。
 *
 * 关键设计原则："AI 不是全知"：
 * Provider 收到的 {@link TranslationRequest} 只包含调用方明确传入的上下文
 * （当前 Conversation 内的近期消息），Provider 实现本身不应该、也没有渠道
 * 访问服务器上其他任何数据。上下文边界完全由调用方（TranslationService）
 * 在构造 request 时把控，Provider 只是"纯函数"式的执行者。
 *
 * 所有方法均为异步（返回 CompletableFuture），因为大多数 Provider 会走网络
 * 请求，不能阻塞服务器主线程 / tick 循环。
 */
public interface TranslationProvider {

	/** Provider 的唯一标识符，用于在配置文件里选择激活哪个 Provider。 */
	String getId();

	/** 展示给管理员/玩家看的可读名称。 */
	String getDisplayName();

	/**
	 * 执行一次翻译请求。
	 *
	 * @param request 翻译请求，包含原文、目标语言、以及（可选的）有限对话上下文
	 * @return 异步返回翻译结果；失败时 future 应以异常完成，调用方负责降级处理
	 */
	CompletableFuture<TranslationResult> translate(TranslationRequest request);

	/**
	 * 拉取该 Provider 当前账号下可用的模型列表（"一键获取模型"功能）。
	 * 默认实现返回失败，代表该 Provider 不支持模型列表查询（例如 DeepL
	 * 是固定的翻译引擎，没有可选模型；Mock 也没有真实模型）。支持
	 * `/models` 一类接口的 Provider（OpenAI/Claude/Gemini/Kimi/DeepSeek/
	 * Ollama）应覆写此方法。
	 *
	 * @return 异步返回模型 ID 列表（已按字母排序），调用方负责展示
	 */
	default CompletableFuture<List<String>> listModels() {
		return CompletableFuture.failedFuture(
				new UnsupportedOperationException(getDisplayName() + " does not support listing models."));
	}

	/**
	 * 翻译请求。
	 *
	 * @param sourceText     原始文本（已经过世界词典占位符预处理）
	 * @param sourceLang     源语言代码（Minecraft locale 格式，如 "zh_cn"）
	 * @param targetLang     目标语言代码
	 * @param contextMessages 当前 Conversation 内最近的若干条消息，用于帮助
	 *                        Provider 理解上下文语义（代词指代、术语一致性等）。
	 *                        这个列表严格限定于"当前对话组内、当前仍在场的
	 *                        参与者产生的消息"，不会包含服务器上其他任何内容。
	 */
	record TranslationRequest(
			String sourceText,
			String sourceLang,
			String targetLang,
			List<String> contextMessages
	) {}

	/**
	 * 翻译结果。
	 *
	 * @param translatedText 译文（占位符尚未还原，由调用方还原）
	 */
	record TranslationResult(String translatedText) {}
}
