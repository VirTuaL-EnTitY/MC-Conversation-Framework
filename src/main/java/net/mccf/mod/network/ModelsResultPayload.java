package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 服务端 -> 客户端：模型列表查询结果。
 *
 * json 内容：
 * 成功：{"success":true,"providerId":"openai","models":["gpt-4o","gpt-4o-mini",...]}
 * 失败：{"success":false,"providerId":"openai","error":"错误信息（供玩家看的简短提示）"}
 */
public record ModelsResultPayload(String json) implements CustomPayload {

	public static final CustomPayload.Id<ModelsResultPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "models_result"));

	public static final PacketCodec<RegistryByteBuf, ModelsResultPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, ModelsResultPayload::json,
			ModelsResultPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
