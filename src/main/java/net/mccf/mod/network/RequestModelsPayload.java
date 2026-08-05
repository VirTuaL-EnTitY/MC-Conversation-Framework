package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 客户端 -> 服务端：请求某个 Provider 当前可用的模型列表（"一键获取模型"）。
 *
 * json 内容：{"providerId":"openai","apiKey":"sk-...","endpoint":""}
 *
 * 携带的是配置界面里**当前输入框中尚未保存**的 apiKey / endpoint —— 这样
 * 玩家可以先填好 Key，直接点"获取模型"验证是否有效，不需要先保存一次。
 * 服务端用这份临时数据构造一个临时 Provider 实例执行查询，不会覆盖已保存
 * 的配置（除非玩家之后主动点"保存"）。同样要求 op 权限（服务端校验）。
 */
public record RequestModelsPayload(String json) implements CustomPayload {

	/**
	 * json 字段的最大字符数。
	 *
	 * 为什么必须限制：{@code PacketCodecs.STRING} 本身不限制长度，恶意客户端
	 * （甚至不需要 op 权限——Payload 的反序列化发生在 op 权限校验**之前**）
	 * 可以构造数 MB 的 JSON 字符串，服务端用 Gson 解析时会消耗大量 CPU 和内存。
	 * 与 {@link UpdateConfigPayload#MAX_JSON_LENGTH} 对齐设 65536，已足以容纳
	 * 任意合理的 providerId + apiKey + endpoint 组合（API Key 通常不超过 200 字符，
	 * endpoint 不超过 500 字符，整个 JSON 远低于 64KB）。
	 */
	private static final int MAX_JSON_LENGTH = 65536;

	public RequestModelsPayload {
		if (json != null && json.length() > MAX_JSON_LENGTH) {
			throw new IllegalArgumentException(
					"RequestModelsPayload json exceeds max length " + MAX_JSON_LENGTH +
					" (was " + json.length() + ")");
		}
	}

	public static final CustomPayload.Id<RequestModelsPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "request_models"));

	public static final PacketCodec<RegistryByteBuf, RequestModelsPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, RequestModelsPayload::json,
			RequestModelsPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
