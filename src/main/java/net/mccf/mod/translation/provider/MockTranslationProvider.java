package net.mccf.mod.translation.provider;

import java.util.concurrent.CompletableFuture;

/**
 * 示例 / 占位 Provider。不调用任何真实的翻译 API，仅用于验证整套
 * Conversation / Spatial / Subtitle 管线在没有联网权限时也能跑通。
 *
 * 行为：在原文前加上目标语言标签，模拟"已翻译"的效果，例如：
 *   输入 "你好" 目标语言 en_us -> 输出 "[en_us] 你好"
 *
 * 接入真实 Provider（OpenAI/DeepL/Ollama 等）时，参照本类实现
 * {@link TranslationProvider} 接口，在 onInitialize 中调用
 * translationService.registerProvider(new YourProvider()) 即可，
 * 无需改动其余任何模块。
 */
public class MockTranslationProvider implements TranslationProvider {

	public static final String ID = "mock";

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Mock Provider (Debug/Placeholder)";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (request.sourceLang().equals(request.targetLang())) {
			// 同语言无需翻译，直接透传。
			return CompletableFuture.completedFuture(new TranslationResult(request.sourceText()));
		}
		String fake = "[" + request.targetLang() + "] " + request.sourceText();
		return CompletableFuture.completedFuture(new TranslationResult(fake));
	}
}
