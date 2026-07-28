package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：响应 {@link RequestModelListPayload}，返回模型列表或错误。
 *
 * 三个字段：
 * - providerId：对应请求的 Provider id，用于客户端区分是哪个 Provider 的列表
 * - modelsJson：成功时为模型 ID 列表的 JSON 字符串（如 {@code ["gpt-4o-mini","gpt-4o"]}），
 *               失败时为空字符串
 * - error：成功时为空字符串，失败时为可显示给玩家的错误消息（已 i18n 处理或英文兜底）
 *
 * 字段语义：{@code error.isEmpty()} 表示成功（即便 modelsJson 为 "[]" 也可能是正常的，
 * 例如某些账号下确实没有任何模型）；{@code error} 非空表示拉取失败
 * （网络/鉴权/不支持等）。
 *
 * 之所以用 JSON 字符串而不是 {@code PacketCodecs.STRING.list()}，
 * 是因为不同 Fabric API 版本中 List 编解码 API 名称有变动（list / collect / collection），
 * 用 String 包装可以避免对具体 API 名称的依赖，保证跨版本编译稳定。
 */
public record ModelListResponsePayload(String providerId, String modelsJson, String error) implements CustomPayload {

	public static final CustomPayload.Id<ModelListResponsePayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "model_list_response"));

	public static final PacketCodec<RegistryByteBuf, ModelListResponsePayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, ModelListResponsePayload::providerId,
			PacketCodecs.STRING, ModelListResponsePayload::modelsJson,
			PacketCodecs.STRING, ModelListResponsePayload::error,
			ModelListResponsePayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}

	/** 成功构造器：从 List<String> 生成 JSON 字符串。 */
	public static ModelListResponsePayload success(String providerId, List<String> models) {
		String json = toJsonArray(models);
		return new ModelListResponsePayload(providerId, json, "");
	}

	/** 失败构造器：modelsJson 留空。 */
	public static ModelListResponsePayload failure(String providerId, String error) {
		return new ModelListResponsePayload(providerId, "[]", error);
	}

	/** 把响应里的 modelsJson 解析回 List<String>。失败时返回空列表。 */
	public List<String> parseModels() {
		if (modelsJson == null || modelsJson.isBlank() || "[]".equals(modelsJson)) {
			return List.of();
		}
		// 简单 JSON 数组解析：去掉方括号后按逗号分割，去掉每个元素两端的引号和空白。
		// 不直接用 Gson 是为了避免引入对它的依赖（虽然 main 里其他地方已经用了 Gson，
		// 但 client 反序列化也走这里，保持本类自包含）。
		String inner = modelsJson.trim();
		if (inner.startsWith("[")) inner = inner.substring(1);
		if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
		if (inner.isBlank()) return List.of();

		List<String> result = new ArrayList<>();
		for (String part : inner.split(",")) {
			String s = part.trim();
			if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
				s = s.substring(1, s.length() - 1);
			}
			// 反转义 \" 和 \\
			s = s.replace("\\\"", "\"").replace("\\\\", "\\");
			if (!s.isBlank()) result.add(s);
		}
		return result;
	}

	/** 简单 JSON 数组序列化：对每个 String 做 JSON 转义后用逗号拼接，外加方括号。 */
	private static String toJsonArray(List<String> models) {
		if (models == null || models.isEmpty()) return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < models.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append("\"").append(escapeJson(models.get(i))).append("\"");
		}
		return sb.append("]").toString();
	}

	private static String escapeJson(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		return sb.toString();
	}
}
