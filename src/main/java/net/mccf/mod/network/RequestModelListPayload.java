package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 客户端 -> 服务端：请求某个 Provider 当前账号下可用的模型列表。
 *
 * 客户端不直接发 HTTP 请求去拉模型列表，而是通过此 payload 让服务端代为请求，
 * 服务端持有 API Key，拉到结果后通过 {@link ModelListResponsePayload} 回发。
 * 这样 API Key 完全不出服务端，符合"配置界面只读快照里非 op 看不到 Key"
 * 的整体权限模型。
 *
 * 权限：与 {@link RequestConfigPayload} 一样，只有 op 能获取到真实结果；
 * 服务端会校验权限，非 op 请求会得到空列表 + "permission denied" 错误消息。
 */
public record RequestModelListPayload(String providerId) implements CustomPayload {

	public static final CustomPayload.Id<RequestModelListPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "request_model_list"));

	public static final PacketCodec<RegistryByteBuf, RequestModelListPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, RequestModelListPayload::providerId,
			RequestModelListPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
