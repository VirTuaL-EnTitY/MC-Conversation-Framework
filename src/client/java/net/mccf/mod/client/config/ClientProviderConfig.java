package net.mccf.mod.client.config;

/**
 * 客户端内存中的单个 Provider 配置副本，供配置 Screen 编辑。
 * 与服务端 {@code net.mccf.mod.config.ProviderConfig} 字段一一对应。
 *
 * @field endpoint API 基础地址（含 Ollama 在内所有 Provider 通用）；
 *        为空表示使用官方默认地址，非空表示玩家自定义了 endpoint。
 * @field isCustomEndpoint 标记 endpoint 是否为玩家自定义值（true）还是
 *        服务端下发的默认值（false），供界面判断"恢复默认"按钮是否需要高亮/可用。
 */
public class ClientProviderConfig {
	public String apiKey = "";
	public String model = "";
	public String endpoint = "";
	public boolean isCustomEndpoint = false;

	public ClientProviderConfig() {}

	public ClientProviderConfig(String apiKey, String model, String endpoint, boolean isCustomEndpoint) {
		this.apiKey = apiKey;
		this.model = model;
		this.endpoint = endpoint;
		this.isCustomEndpoint = isCustomEndpoint;
	}
}
