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
