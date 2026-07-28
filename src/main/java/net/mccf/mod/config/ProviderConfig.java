package net.mccf.mod.config;

/**
 * 单个翻译 Provider 的配置项：API Key、模型名、API 基础地址（endpoint）。
 *
 * endpoint 字段对所有 Provider（除 Mock 外）都有意义，不再是 Ollama 专属：
 * 每个 Provider 都有一个默认官方地址（见 {@link ProviderDefaults}），
 * 用户可以在配置界面里改成自建反代 / 兼容网关的地址，也可以一键恢复默认。
 *
 * 不是所有字段对所有 Provider 都同样关键——例如 DeepL 不需要 model，
 * Ollama 不需要 apiKey——各 Provider 的实现类只读取自己关心的字段。
 */
public class ProviderConfig {

	/** API Key / Auth Token。Ollama 本地部署通常不需要，可留空。 */
	public String apiKey = "";

	/** 模型名称，例如 "gpt-4o-mini"、"claude-sonnet-4-6"、"gemini-3.5-flash"、"deepseek-v4-flash"。 */
	public String model = "";

	/**
	 * API 基础地址。默认值见 {@link ProviderDefaults}，用户可修改为自建
	 * 反代/兼容网关地址；留空或点击"恢复默认"时使用官方默认地址。
	 */
	public String endpoint = "";

	public ProviderConfig() {}

	public ProviderConfig(String apiKey, String model) {
		this.apiKey = apiKey;
		this.model = model;
	}

	public ProviderConfig(String apiKey, String model, String endpoint) {
		this.apiKey = apiKey;
		this.model = model;
		this.endpoint = endpoint;
	}

	/** 若 endpoint 为空，返回该 Provider 的官方默认地址；否则返回用户自定义值。 */
	public String effectiveEndpoint(String providerId) {
		return (endpoint == null || endpoint.isBlank())
				? ProviderDefaults.get(providerId).endpoint()
				: endpoint.trim();
	}
}
