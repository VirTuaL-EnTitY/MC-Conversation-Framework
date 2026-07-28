package net.mccf.mod.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.mccf.mod.MCCF;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 世界词典：服务器统一维护的专有名词映射表。
 *
 * 设计目的（对应项目原则"信息一致性"）：
 * 不同的 AI Provider、不同的调用时机，可能把同一个专有名词（NPC 名字、地名、
 * 剧情术语）翻译成不一样的东西。这会破坏玩家的沉浸感和剧情理解。
 *
 * 世界词典在翻译前对原文做"预处理占位替换"：把词典中的词替换为一个不会被
 * 翻译引擎改写的占位符，翻译完成后再替换回词典中配置好的目标语言版本。
 * 这样无论底层用哪个 AI Provider，专有名词的翻译结果永远一致、可控。
 *
 * 配置文件：config/mccf/dictionary.json
 * 格式： { "词条": { "en_us": "...", "zh_cn": "...", "ja_jp": "..." }, ... }
 */
public class WorldDictionary {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(MCCF.MOD_ID);
	private static final Path DICTIONARY_FILE = CONFIG_DIR.resolve("dictionary.json");

	/** term -> (langCode -> translation) */
	private final Map<String, Map<String, String>> entries;

	private WorldDictionary(Map<String, Map<String, String>> entries) {
		this.entries = entries;
	}

	public static WorldDictionary loadOrCreate() {
		try {
			if (Files.exists(DICTIONARY_FILE)) {
				try (Reader reader = Files.newBufferedReader(DICTIONARY_FILE)) {
					Type type = new TypeToken<LinkedHashMap<String, Map<String, String>>>() {}.getType();
					Map<String, Map<String, String>> loaded = GSON.fromJson(reader, type);
					if (loaded != null) {
						MCCF.LOGGER.info("[MCCF] Loaded world dictionary with {} entries.", loaded.size());
						return new WorldDictionary(loaded);
					}
				}
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to read dictionary, falling back to defaults.", e);
		}

		WorldDictionary dict = new WorldDictionary(defaultEntries());
		dict.save();
		return dict;
	}

	private static Map<String, Map<String, String>> defaultEntries() {
		Map<String, Map<String, String>> defaults = new LinkedHashMap<>();
		// 示例条目：服务器管理员可自行编辑 dictionary.json 增删。
		Map<String, String> example = new LinkedHashMap<>();
		example.put("en_us", "Force Merge");
		example.put("zh_cn", "强制合并");
		defaults.put("Force Merge", example);
		return defaults;
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_DIR);
			try (Writer writer = Files.newBufferedWriter(DICTIONARY_FILE)) {
				GSON.toJson(entries, writer);
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to save dictionary.", e);
		}
	}

	public void addEntry(String term, String langCode, String translation) {
		entries.computeIfAbsent(term, k -> new LinkedHashMap<>()).put(langCode, translation);
		save();
	}

	public boolean removeEntry(String term) {
		boolean removed = entries.remove(term) != null;
		if (removed) save();
		return removed;
	}

	public Map<String, Map<String, String>> getEntries() {
		return entries;
	}

	/**
	 * 用占位符替换文本中出现的词典词条，返回替换后的文本与占位符映射表。
	 * 占位符使用不会被常见翻译引擎误处理的格式：〔MCCF_DICT_n〕
	 */
	public DictionaryPass applyPlaceholders(String text) {
		Map<String, String> placeholderToTerm = new LinkedHashMap<>();
		String result = text;
		int index = 0;
		for (String term : entries.keySet()) {
			if (term.isBlank()) continue;
			Pattern pattern = Pattern.compile(Pattern.quote(term));
			Matcher matcher = pattern.matcher(result);
			if (matcher.find()) {
				String placeholder = "\u3014MCCF_DICT_" + index + "\u3015";
				result = matcher.replaceAll(Matcher.quoteReplacement(placeholder));
				placeholderToTerm.put(placeholder, term);
				index++;
			}
		}
		return new DictionaryPass(result, placeholderToTerm);
	}

	/**
	 * 翻译完成后，将占位符替换为目标语言下词典配置的对应译文。
	 * 若词典中没有该语言的条目，则回退为词条原文。
	 */
	public String restorePlaceholders(String translatedText, Map<String, String> placeholderToTerm, String targetLang) {
		String result = translatedText;
		for (Map.Entry<String, String> e : placeholderToTerm.entrySet()) {
			String placeholder = e.getKey();
			String term = e.getValue();
			String replacement = entries.getOrDefault(term, Map.of()).getOrDefault(targetLang, term);
			result = result.replace(placeholder, replacement);
		}
		return result;
	}

	/** applyPlaceholders 的返回结果：预处理后的文本 + 占位符映射表。 */
	public record DictionaryPass(String processedText, Map<String, String> placeholderToTerm) {}
}
