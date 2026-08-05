# MCCF 设计决策记录

> **文档定位**：从 README 更新日志中剥离的决策分析，记录每个版本改动背后的
> "为什么"——根因分析、方案论证、取舍说明、已知限制的论证。README 更新日志
> 只保留"改了什么"的纯版本更新，决策脉络集中在本文档。
> **[English version](design-notes_en.md)** | 简体中文（当前）

---

## 1.1.2　QA 报告批量修复：Zhipu 未注册 + 网络包安全 + 翻译失败感知 + 多项 UX 改进

**根因分析 - Zhipu 未注册**：0.15.0 加入智谱 Provider 时，`ProviderDefaults` / `ProviderFactory` / `MCCFConfig.defaultProviderConfigs()` / `ClientConfigState.PROVIDER_IDS` 都正确添加了 zhipu，但 `MCCF.registerAllProviders()` 第 222 行的 `List.of("openai", "claude", "gemini", "deepl", "kimi", "deepseek", "ollama")` 漏了。这导致：
- 玩家在配置界面**能看到并选择**智谱 AI（因为 ClientConfigState.PROVIDER_IDS 包含 zhipu）
- 玩家能**填 API Key 并保存**（因为 config.providers 默认会为 zhipu 生成配置项）
- 但 `setActiveProvider("zhipu")` 在 TranslationService 找不到该 Provider，**静默 fallback 到 Mock**
- 日志只有一行 warn，且 warn 信息是"Configured provider 'zhipu' not found, falling back to mock"——管理员看到也会以为是"配置错了"，不会想到是"代码漏了一行"

这种"看着配好了实际没生效"的 bug 比完全没功能更糟糕，因为玩家会反复修改配置以为自己操作错了。根因是 Provider 注册散落在两处（运行时 `registerAllProviders()` 和界面 `ClientConfigState.PROVIDER_IDS`），没有单一数据源约束，新加 Provider 时容易遗漏一处。

**为什么是 List.of 字面量而不是从 ProviderDefaults 派生**：理想方案是 `for (String id : ProviderDefaults.all().keySet())` 自动遍历所有已定义的 Provider，新增 Provider 时只需在 ProviderDefaults 加一行即可，不会遗漏。但当前实现保持 List.of 字面量有两个考量：(1) 显式列出可以让 review 时一眼看出哪些 Provider 在注册，方便排查；(2) ProviderDefaults.all() 的迭代顺序在 Map 实现下不稳定，可能影响"Mock 优先注册以便 fallback"的隐式约定。如果未来再加 Provider 仍漏注册，应考虑改成派生方案。

**根因分析 - 网络包长度校验缺失**：`PacketCodecs.STRING` 在 Minecraft 协议层不限制字符串长度，由调用方自行校验。`SubtitlePayload` 和 `UpdateConfigPayload` 早期就加了校验（`MAX_TEXT_LENGTH = 4096` / `MAX_JSON_LENGTH = 65536`），但 `RequestModelsPayload` / `LanguageReportPayload` / `ConversationRosterPayload` 解码端都漏了。这是"先写的 Payload 加了校验，后写的忘了加"的典型遗漏——团队没有统一的"新增 Payload 必须加长度校验"规则。本次修复后应在已确认规则章节补一条约束。

**为什么 RequestModelsPayload 的恶意包风险更严重**：该 Payload 携带 apiKey + endpoint + providerId 三个字段，恶意客户端可以构造数 MB 的 JSON 字符串通过 `PacketCodecs.STRING` 传输。服务端 `ConfigSyncHandler.handleModelsRequest` 会用 Gson 解析它，Gson 解析大 JSON 时消耗大量 CPU 和内存。**关键点**：Payload 反序列化发生在 op 权限校验**之前**——也就是说，恶意客户端甚至**不需要 op 权限**就能触发服务端内存分配。这是真正的安全漏洞，不只是"管理员被刷屏"的体验问题。

**为什么 ConversationRosterPayload 的解码端校验放在 ArrayList 构造之前**：旧代码 `int idCount = buf.readVarInt(); List<UUID> ids = new ArrayList<>(idCount);` 直接把 idCount 传给 ArrayList 构造器预分配容量。`new ArrayList<>(Integer.MAX_VALUE)` 会尝试预分配约 16GB 内存（`Integer.MAX_VALUE * 8 bytes/引用 ≈ 16GB`），即使元素从未真正写入也会 OOM。校验必须在 ArrayList 构造**之前**完成，否则校验本身就晚了。构造函数的 `MAX_PARTICIPANTS` 检查（第 50 行）虽然存在，但它在校验失败时抛 `IllegalArgumentException`，而 ArrayList 已经被构造过——OOM 已经发生，异常来不及抛。

**为什么翻译失败要计数而不是直接告诉玩家**：TranslationService.translate() 的 exceptionally 把失败转成原文 fallback 返回，上层调用方收到的 future 永远成功——这是为了"翻译失败时玩家至少看到原文而不是完全收不到消息"的设计。但代价是上层无法区分"翻译成功且译文等于原文"和"翻译失败回退原文"。

要彻底解决需要引入 `TranslationResult(text, success)` 包装类型，但这会改 TranslationService 的所有调用方签名，影响范围大。本次采取折衷：
1. 加原子计数器让 `/mccf status` 能展示统计——管理员视角能感知到失败趋势
2. 客户端路径（ClientOnlyChatTranslator）直接调用 `provider.translate()` 不经 TranslationService，exceptionally 仍能触发——加去重的玩家提示

服务端路径下普通玩家仍无法直接区分译文等于原文是成功还是失败，但管理员可以查 `/mccf status` 主动排查。这是"管理员能感知 + 客户端玩家能感知 + 服务端玩家靠管理员排查"的三层方案。

**为什么 ClientOnlyChatTranslator 失败提示要 60 秒去重**：聊天刷屏时如果每条消息都失败（比如 API Key 失效），每条都给玩家发一条"翻译失败"提示会把聊天栏刷爆——比"静默失败"更糟。去重策略以 reason 字符串为 key，同一原因 60 秒内只提示一次。换 Provider / 换 Key 后失败原因通常会变，新 reason 会立即提示——让玩家感知"现在的失败是新问题"。

**为什么 ClientOnlyTranslationConfig.save() 失效 Provider 缓存**：旧版 `ClientOnlyChatTranslator.getProvider()` 按 providerId 缓存 Provider 实例永不失效，玩家改 API Key 后必须切 Provider 再切回来才能刷新——这个限制太隐晦，玩家会以为 Key 没保存成功反复改。

最简单的失效点是 `ClientOnlyTranslationConfig.save()`——保存路径单一（玩家在配置界面点保存），在文件成功写入后回调失效缓存保证"保存即刷新"。为什么不直接在 getProvider 里每次都比对字段：实时感知配置变更需要 ProviderConfig 自己支持"字段变更通知"或在 getProvider 里逐字段 diff，复杂度不值当。

**为什么 RateLimiter 从固定窗口升级为滑动窗口**：固定窗口的经典问题是边界突刺——5 条/秒限制下，窗口末尾的 5 条 + 新窗口开头的 5 条 = 10 条/秒突刺。对聊天刷屏这种异常行为，突刺本身影响有限；但对 OpenAI / DeepL 这类付费 API，2 倍突刺可能直接触发上游速率限制导致 API Key 被临时封禁——比"翻译偶尔失败"严重得多。

滑动窗口用 ArrayDeque 维护已放行请求的时间戳，每次 tryAcquire 先清理窗口外时间戳再判定队列长度。复杂度增加不大（10 行左右），但精度从"窗口级"提升到"任意连续窗口级"。被拒绝的请求不进入队列——这避免了旧版"被拒绝也 incrementAndGet"的语义混淆。

**为什么首次提示从内存 static 改为持久化**：旧版 `tipped` 是 static boolean，进程重启后重置——老玩家每次重启游戏都会被同一条提示刷屏。改为持久化到 `client-mode.json` 的 `tippedFirstJoin` 字段后，老玩家重启不再被提示。升级到 1.1.2 后会**再提示一次**——这是有意为之，让升级用户感知到"现在提示也提到了 ModMenu 入口"这个改进，之后不再重复。

**为什么提示文案加入 ModMenu 入口**：旧版只说"前往按键绑定"，但默认按键未绑定（`InputUtil.UNKNOWN_KEY`），玩家得自己先去按键绑定再回来按——这对新人太陡。ModMenu 是更直观的入口（Mods 列表里点齿轮图标），9 种语言文案同时更新。

---

## 1.1.1　修复强制关闭思考开关 + 显示原文改为客户端个人偏好

**Bug 1：强制关闭思考开关点"是"后仍显示"关"的根因**：`ConfirmScreen` 的回调里
代码顺序错了——先 `setScreen(screen)` 切回配置界面，后才更新
`disableThinking = true`。`setScreen` 会触发 `MCCFConfigScreen.init()` 重建所有
面板控件，重建时 `refreshFieldsFromState` 从 state 读取 `disableThinking` 的值
来设置按钮显示。先 setScreen 再改状态意味着 init 重建读到的还是旧值 false，
按钮就显示"关"；等状态改成 true 时按钮已经画完了，不会再刷新。修复：把状态
更新移到 `setScreen` 之前。`LocalConfigPanel` 有完全相同的 bug。

**为什么取消分支不需要 `button.setValue(false)`**：init 重建会创建全新的按钮
widget，lambda 里捕获的 `button` 是旧 widget，操作它没有意义；新按钮会从 state
（仍是 false）正确读取并显示"关"。

**Bug 2：显示原文从服务端 op 配置改为客户端个人偏好的决策**：0.16.2 起这两个
开关被设为灰色不可选（`active=false`），只能通过改 `config.json` 修改，且是
服务端 op 配置——所有玩家共享同一个值。用户反馈这不符合需求：每个玩家应该能
独立决定要不要看原文，不受服务器/op 限制。1.1.1 把 `showOriginalText` /
`showOriginalTextInChat` 从 `MCCFConfig`（服务端配置）迁移到
`ClientOnlyTranslationConfig`（客户端个人偏好，存 `client-only-config.json`）。

**为什么开关保留在"服务端配置"标签页**：应用户明确要求。虽然这两个开关在
语义上已经是客户端偏好，但它们和 Provider 配置属于同一组"翻译展示设置"，
放在原位置更符合用户的使用习惯。开关的 `active` 只依赖 `tabVisible`（不依赖
`canEdit`），所有玩家都能切换，切换后立即生效并落盘，不需要点"保存"。

**AUDIBLE 字幕不显示原文的根因**：`HotbarSubtitleRenderer.render()` 只拼接
`translatedText()`，完全没读 `originalText()`——服务端正确发送了原文、
`SubtitleManager` 正确存储了原文，但渲染器忽略了。1.1.1 修复：渲染时根据
`ClientOnlyTranslationConfig.showOriginalText` 决定是否额外画一行原文
（格式与 VISIBLE 聊天栏一致：`<名字> 原文` + `⇄ 译文`）。

**服务端始终发送原文的设计**：`SpatialChatHandler.dispatchTo` 不再根据
`config.showOriginalText` / `config.showOriginalTextInChat` 决定是否填充
`originalText`，而是始终填入原文。是否显示完全由客户端决定——这样不同玩家
可以有不同的显示偏好，服务端不需要为每个听众单独判断。

---

## 1.1.0　发布工作流改为纯手动触发 + GitHub Release 恢复附带 jar 和 sources jar

**为什么改为纯手动触发**：发版是"确认这版可以发了"的显式动作，应该由人在
GitHub Actions 界面手动点 "Run workflow" 执行，避免误触发产生无意义的 Release
或在代码改一半时意外触发编译验证。push 到 main 只编译验证、push tag 自动发布
这两条触发器都去掉。

**为什么删除 release.py**：workflow 改为手动触发后，版本号和 tag 都由 workflow
从 gradle.properties 读取并自动创建（softprops/action-gh-release 的 tag_name
参数指定，tag 不存在时在当前 commit 上自动创建），本地不再需要打 tag 脚本。
发版流程从"本地跑脚本打 tag"简化为"去 GitHub Actions 网页点 Run workflow"。

**为什么恢复 jar 附带**：0.16.4 起曾改为"GitHub Release 只含源码，jar 统一走
Modrinth"。1.1.0 改回 GitHub Release 也带 jar + sources jar，方便玩家多渠道
下载。sources jar 来自 build.gradle 已有的 `withSourcesJar()` 配置，供开发者
在 IDE 里查看源码 / 调试反编译用。build job 新增对 sources jar 的存在性校验，
在"编译看似成功但产物没生成"的边缘情况下能及时暴露问题。

---

## 1.0.0　首个正式版

**版本号从 0.x.x 跳到 1.0.0 的理由**：按语义化版本规则，1.0.0 的含义是"第一个
公开稳定版本"，0.x.x 阶段表示"还在早期开发、可能随时变"。本项目经过 0.3.0~
0.16.4 的迭代，核心功能（空间化翻译、Conversation 上下文、Provider 可插拔、
纯客户端模式、聊天历史记录、9 种语言本地化）已经稳定，具备 1.0.0 发布资格。

**reload 漏字段的教训**：`showOriginalTextInChat` 是 0.12.0 新增的字段，当时
reload 漏了这一行，导致 `/mccf reload` 无法重载这个配置项——管理员改完
`config.json` 跑 reload 不生效，必须重启服务器。这违反了 reload 命令的契约
（"完整重载"）。教训：新增配置字段时必须同步更新 reload 的拷贝列表，否则会
出现"改了不生效"的隐蔽 bug。

**死代码清理的背景**：`RequestModelListPayload` 和 `ModelListResponsePayload`
是"获取模型"功能早期迭代时留下的死代码（后来改用了
`RequestModelsPayload`/`ModelsResultPayload` 这条路径），从未被 `MCCF.java` /
`MCCFClient.java` 注册使用。`ModelListResponsePayload` 内部还自带了 70 行手写
JSON 解析工具方法，全部是死代码。

**扫描结论的论证**：发布前做了全项目扫描（死代码 / TODO 标记 / 异常处理 /
配置一致性 / 语言文件完整性 / 网络包完整性），除上述修复项外全部通过——无任何
TODO/FIXME/XXX/HACK 标记残留；无空 catch 块、无吞异常、所有 future 都有
`.exceptionally` 处理；9 种语言文件 key 完全一致（每个都是 102 个 key）；
异常处理完善，失败路径都有日志；已知限制项都是合理的功能边界（STT 未实现、
字幕时长未可配置等），不是缺陷；硬编码值都有详细注释论证（缓存 TTL、限流阈值、
环形缓冲容量等）。

---

## 0.16.4　GitHub Release 改为源码 Release + 新增一键发版脚本

**为什么 GitHub Release 改为只含源码**：应用户需求。这样 GitHub Release 纯粹
是版本记录 + changelog，不会和 Modrinth 上的 jar 产生"两边版本不同步"的问题。
早期版本（0.8.0~0.16.3）的旧 Release 里仍然有历史版本的 jar，新版本不再这么做。

**release.py 的设计考量**：不自动 bump 版本号、不自动 commit——发版是"确认这版
可以发了"的显式动作，版本号由作者在开发过程中按 9.1 规则手动维护。前置检查：
工作区干净（防止改一半代码就发版）、tag 不存在（防止重复发）、当前分支已推到
远程。支持 `--check` dry-run 模式。

---

## 0.16.3　配置界面输入框 placeholder 文字超出输入框宽度

**根因分析**：`apiKeyField` 宽度是 `panelWidth - 44`（比 model/endpoint 输入框
窄 44px，给 clearApiKeyButton 留位），但它的 placeholder 却是最长的。各语言版本
长度差异极大——中文约 18 字符、英文约 36 字符，而德语/法语/西班牙语/俄语版本
长达 50+ 字符，在窄屏或小 GUI 比例下必然超出。`TextFieldWidget` 在 1.21.1 上
渲染 placeholder 时不做 `trimToWidth` 截断，文字直接画到输入框外面。endpoint
的 placeholder 在德语/法语等语言下也有同样问题。

**为什么简化**：去掉冗长的"留空则保持当前值不变"解释，只保留核心提示。"留空
保持当前值"这个行为对用户是直观的，且 `clearApiKeyButton` 已经区分了"留空保存"
和"主动清除"两种操作，不需要在 placeholder 里重复说明。

---

## 0.16.2　配置界面"显示原文"开关布局 + 灰色不可选 + 强制关闭思考按钮重复显示

**误判过程**：第一轮以为是 spacing 公式算错导致控件挤在一起、文字叠加被误看成
两个按钮（改了 140→160）。用户明确指出"没在开玩笑，那就是强制关闭思考按钮"后，
才定位到真正根因——不是 spacing，而是真的有两个 `disableThinkingButton` 同时
显示。

**真正根因**：`ServerConfigPanel` 和 `LocalConfigPanel` 用同一套
`left/top/right/bottom` 坐标，各自的 `disableThinkingButton` 位置完全重叠。
`refreshFieldsFromState` 里 `disableThinkingButton.visible = supportsThinking`
无条件设 visible，覆盖了 `setVisible(false)` 设的不可见状态。当非活动标签页的
Provider 支持思考时，它的 `disableThinkingButton` 会错误显示，和活动标签页的
同位置按钮叠在一起。`LocalConfigPanel.onTabVisibilityChanged` 会调
`refreshFieldsFromState`，所以切到"服务端配置"标签页时 Local 的按钮会漏出来；
两个 Panel 的 `disableThinking` 值可能不同，玩家就看到"上面关、下面开"两个
按钮。

---

## 0.16.1　去除"固定在 1.21.1"的版本理由论证

**为什么去除版本理由论证**：0.16.0 移除世界空间字幕（WorldRenderEvents）后，
升级到更高 Minecraft 版本的最大技术障碍已经消除，README 里原本为"为什么固定在
1.21.1"所做的论证（1.21.x 生态兼容性、WorldRenderEvents 在 1.21.9+ 被移除的
版本兼容顾虑等）不再需要保留为面向玩家的说明。

**gradle.properties 注释精简的理由**：从"目标版本固定为 1.21.1 + 长篇理由"
精简为"1.21.1 仅作为当前发布基线，不作为长期锁定"，如实反映当前状态——升级
障碍已消除，未来可更自由地跟进新版本。

---

## 0.16.0　移除世界空间字幕 + AI 上下文改为完整对话组

**为什么移除世界空间字幕**：`WorldSubtitleRenderer` 代码存在但实测一直不显示，
根因始终未定位，不再作为当前版本功能保留。本项目不再依赖 `WorldRenderEvents`
——那个 API 在 1.21.9+ 因渲染管线重构被移除的版本兼容顾虑随之消除。

**VISIBLE 走聊天栏转正的决策历史**：早期版本（0.3.0~0.4.0）曾尝试用世界空间
渲染把字幕画到说话者模型旁边，但根因始终未定位、实测不显示。0.4.0 起临时把
VISIBLE 改走原版聊天栏作为绕开方案，0.16.0 正式确认这个绕开方案**转正**——
VISIBLE 走聊天栏成为正式行为而非临时降级。HearingResolver 仍然区分
VISIBLE/AUDIBLE 两档（距离 + 视线判定），只是 VISIBLE 的展示载体从"世界空间
悬浮字幕"变成"原版聊天栏"——近处说话走聊天框、远处喊话走物品栏字幕的语义
不变。

**`subtitleVisibleRange` 字段保留不改名的原因**：它承担的是 HearingResolver
区分"看得见/听得到"两档的距离阈值职责，与展示载体无关；改名会破坏旧
config.json 的向后兼容。字段注释已更新说明这一点。

**AI 上下文改为完整对话组的取舍**：完全去掉上下文截断后，超长对话（比如几十
分钟不停歇的讨论）的 prompt 会变长、token 消耗增加，可能超出某些 Provider
模型的上下文窗口导致请求失败。**这是用户知情接受的取舍**（用户在本次需求中
明确选择"完全去掉截断"而非软上限方案）。没有加软上限兜底——如果未来实测发现
这个问题，可以在 Provider 层单独加截断，不影响 Conversation /
ChatCompletionsSupport 的通用逻辑。

---

## 0.15.0　五家 Provider 独立"强制关闭思考"开关 + 新增智谱 AI Provider

**调研结论（各家"思考模式"现状，2026-08 核实）**：
- **DeepSeek**：V4 系列（默认 `deepseek-v4-flash`/`deepseek-v4-pro`，官方已在
  2026-07-24 停用旧的 `deepseek-chat`/`deepseek-reasoner`）默认开启思考，支持
  `"thinking":{"type":"disabled"}` 关闭。
- **Kimi**：默认模型 `kimi-k2.5`（K2.x 系列）默认开思考、支持同样的
  `thinking:{type:disabled}` 参数关闭；但 **K3 系列官方文档明确写"Reasoning is
  always on. There is no non-thinking mode."**——如果玩家把模型手动改成 K3，
  这个开关传参不会报错但也不会生效。
- **Claude**：默认模型 `claude-sonnet-4-6` 支持旧版 `thinking:{type:"disabled"}`
  参数（"extended thinking is deprecated on the Claude 4.6 models, requests
  using it still succeed"）；但 **Claude 4.7 及更新模型不再支持这个参数结构，
  会返回 400 错误**，新模型改用 `effort` 参数控制思考强度，无法简单"禁用"。
- **Gemini**：默认模型 `gemini-3.5-flash` 支持
  `generationConfig.thinkingConfig.thinkingBudget:0` 关闭思考；但 **Gemini 2.5
  Pro / Gemini 3 Pro 官方文档明确写思考无法关闭**（"Thinking can't be turned
  off"）。
- **智谱 GLM**：GLM-5/GLM-5.2 官方示例代码确认支持
  `thinking:{type:"disabled"}`（与 DeepSeek/Kimi 同款参数结构）。
- **没有任何一家的 `listModels()` 接口会返回"这个模型是否支持关闭思考"这个
  信息**——因此无法用联网查询自动判断某个具体模型是否真的支持，只能在打开开关
  时给出通用警告，交给玩家自己验证。
- OpenAI 默认模型 `gpt-4o-mini` 不是推理模型，本身没有"思考"概念，不在这次的
  处理范围内。

**为什么每个 Provider 独立一个开关**：应用户明确要求"只想关 DeepSeek 的思考，
不想关 Kimi 的"这类精细控制，不是全局一个开关影响所有 Provider。

**打开开关时弹出确认警告的设计**：开关默认关闭、可自由打开（不是"灰色禁用"）
——应用户最终确认的方案。打开（false → true）时用 `ConfirmScreen` 弹出警告：
说明部分新一代模型可能不支持强制关闭思考、可能导致该 Provider 的翻译请求完全
失败，同时说明关闭思考的好处（翻译更快、更省 token）。关闭（true → false）
不需要确认，直接生效——关掉一个"可能有风险的设置"永远是安全操作。

**已知限制**：判断"某个具体模型是否支持关闭思考"完全依赖玩家自己验证——没有
任何官方 API 提供这个信息，配置界面无法做到"选了不支持的模型就自动置灰开关"
这种智能提示，这是当前 AI 服务商生态的普遍限制，不是这个模组能绕过的技术
问题。参数名和支持范围可能随各家 API 版本更新而变化（本次调研截止
2026-08-01），如果日后某家调整了参数结构，需要重新调研并更新对应 Provider
实现类。

---

## 0.14.0　聊天历史记录支持筛选与排序

**筛选粒度是"按对话分组"而不是"按单条消息"的原因**：只要一个对话分组里有
任意一条消息同时满足三个维度的条件，就把这个分组完整保留展示（包括组内所有
消息和系统提示），不会只隐藏组内不满足条件的单条消息——那样容易让人看不懂
某条消息为什么突然消失，按对话分组展示更符合"看对话"而非"看碎片消息"的使用
场景。参与者筛选对没有服务端参与者名单的分组（CLIENT_ONLY 无归属消息）恒不
通过，因为这类消息没有"对话参与者"概念。

**关键词输入框不是"改了就立刻生效"的原因**：打字过程中每敲一个字符都重建列表
会很卡，也容易在打到一半时列表就跳来跳去。改为失去焦点（点击别处/收起面板）
或按回车时才应用筛选。

**修复 `rebuildList()` bug 的分析**：每次筛选/排序变化都会创建新的
`HistoryListWidget` 实例，旧实例之前没有从 Screen 的子控件集合里移除——会导致
每次交互后残留一个失效的列表在原地，不仅浪费内存，旧列表的裁剪区域和输入响应
还会跟新列表叠加，导致点击、滚动等交互错乱。修复为重建前先调用
`remove(listWidget)` 显式移除旧实例。

---

## 0.13.1　移除 CurseForge 相关说明 + 修正一处过期硬编码版本号

**为什么不回溯修改历史决策记录**：之前（见 0.8.0 记录）写工作流时 README 里
还提过"项目要发布到 GitHub 并上传 CurseForge / Modrinth"，以及"CurseForge /
Modrinth 的自动上传本次没有加入 workflow"——这两处是当时的真实决策记录，属于
历史事实，本次不做回溯修改，只在下载安装说明这类"面向当前用户的操作指引"里
去掉 CurseForge，避免用户去 CurseForge 找但根本没有发布过的困惑。

---

## 0.13.0　悬浮说明从顶部标题挪到左侧列表本身

**为什么挪动悬浮触发区域**：0.12.0 把 Provider 说明改成悬浮 tooltip 后，触发
区域只有顶部"当前选中查看"的标题一处，玩家想快速比较几个 Provider 的说明时，
得先点选中每一个才能看到对应说明，不够方便。这次把悬浮触发区域从顶部标题挪到
左侧 Provider 列表本身，悬浮到列表里任意一项（不需要先点中它）就能看到该项的
说明，等于顺手预览了整个列表。

**tooltip 必须在 scissor 解除之后画的原因**：必须在 `disableScissor()` 之后画，
否则 tooltip 内容如果比列表本身宽，会被列表的裁剪区域裁掉右侧/底部超出的部分
（scissor 只裁剪列表框内的绘制调用，tooltip 需要能画到列表框外面）。

---

## 0.12.0　提示区改为悬浮 tooltip + 聊天历史复用服务端 Conversation 分组 + 聊天栏可选显示原文

**提示区改为悬浮 tooltip 的根因**：`BOTTOM_HINT_AREA_HEIGHT` 之前固定预留 100px
给"Provider 说明 + 状态消息"常驻文字，但日常使用中 Provider 说明这类不紧急信息
完全占用不到这么多空间，导致界面下方出现大片空白。Provider 说明改为鼠标悬浮
在顶部 Provider 标题上时才弹出的 tooltip，不再常驻占用空间。状态消息（加载中/
超时未安装/保存成功失败）继续常驻显示——这些是玩家必须立刻看到的信息，不适合
藏进 tooltip。

**为什么手写 `PacketCodec.of` 而不是用 tuple 重载**：`SubtitlePayload` 新增
`conversationId`/`sourceLang`/`targetLang` 三个字段后字段数达到 8 个。由于项目
此前所有 payload 最多用到 5 字段的 `PacketCodec.tuple(...)` 重载，字段数继续
增加时是否有对应重载没有先例可核对，本次改为手写
`PacketCodec.of(encoder, decoder)` 实现编解码，不依赖任何不确定的重载数量。

**执行顺序调整的原因**：`Conversation` 的创建/归组这一步从"只在有听众时才执行"
提前到"无论有没有听众都先执行"，让说话者本人始终先被归入一个 Conversation
（哪怕周围没人、只有他自己）——这样自己的"自言自语"回显消息在历史记录里也能
正常归组，不需要特殊标识表示"不属于任何对话组"。

**第三者能否算"加入对话"的判定基准**：完全由服务端下发的数据决定。服务端只会
把 `ConversationRosterPayload` 发给"当时确实能收到这条对话消息的人"，所以 A
看不到 Alex、B 看得到 Alex 时，A 的历史记录里天然不会出现"Alex 加入了对话"
——这是客户端完全被动接收数据的自然结果，不需要客户端做任何额外判断。

**已知限制**：`SubtitlePayload` 协议改动意味着这个版本的客户端和服务端必须同时
更新才能正常通信——字段数变化会导致新旧版本之间的 `PacketCodec` 解码不兼容。
这不是新引入的限制（项目此前也没有做过跨版本协议兼容层），但值得记录：以后
每次修改这类核心 payload 的字段结构，都应该视为"不兼容变更"对待。

---

## 0.11.0　提示文字迁移到左下角空白区 + 修复两处误导性状态显示

**控件区与底部提示文字共用同一条基准线的根因**：`MCCFConfigScreen.init()` 里
`contentBottom = this.height - MARGIN`，这个值同时作为"右侧设置区最后一行控件
的下边界"（传给 Panel 的 `bottom` 参数）和"底部提示文字区域"的坐标基准（Panel
的 `renderExtra` 里 `screenBottom = screen.height - 20`，与 `contentBottom`
数值相同）——一个从上往下排列控件，一个从下往上排列文字，行数一多
（`LocalConfigPanel` 最多时是"Provider 说明 + 强制服务器模式警告（可能换行）
+ 检测状态 + 操作状态消息"四行）就会在中间区域视觉重叠。

**"强制服务器模式但未检测到服务器"从常驻警告改成拦截式确认弹窗的原因**：原
设计的常驻红色文字动态换行、行数不固定，容易被忽略，也是上面提到的重叠问题的
主要诱因之一（换行行数不确定，没法在排布其他行时预留出准确空间）。新设计只在
玩家点击"保存"、且当前选择结算为"强制服务器模式"、且客户端确实检测不到服务器
已安装 MCCF 时，弹出 `ConfirmScreen`。这不是"仅供参考"的风险提示，而是"确定
会出问题"的操作后果，弹窗拦截比常驻文字更能确保玩家真的看到并做出选择。

**为什么用 `ConfirmScreen` 的基础 3 参数构造函数**：1.21.1 环境下没有可核对的
本地反编译源码，`ConfirmScreen` 支持自定义按钮文字的重载在不同 Minecraft
版本间签名有过变化，贸然使用有编译失败风险，故未采用基础 3 参数版本
（`BooleanConsumer, Text, Text`）。

**移除死代码的分析**：`if (!tabVisible) return;` 经排查永远不会执行到——
`MCCFConfigScreen.render()` 只在 `activeTab` 匹配当前 Panel 时才会调用它的
`render()`/`renderExtra()`，非活动标签页的 `renderExtra` 根本不会被调用，
`tabVisible` 在这个方法体内恒为 `true`。清理这两句死代码，并在两个类的
`renderExtra` 开头加注释说明原因，避免以后又被人以为这个判断有意义而依赖它。

---

## 0.10.0　Provider 列表 DeepSeek 消失 + 移除"设为默认"按钮

**Provider 列表 DeepSeek 消失的根因**：`ProviderListWidget` 是纯静态渲染，8 个
Provider × 20px 行高需要 160px 总高度，但渲染逻辑里
`y + ENTRY_HEIGHT > maxY` 会直接 `break` 掉超出可视区域的条目，完全没有滚动
能力。一旦分配给列表的高度小于 160px（常见于较小分辨率，或者上一版本改动为
底部提示文字预留空间后进一步压缩了可用高度），排在数组末尾的 Provider
（`deepseek` 排第 7、`ollama` 排第 8）就会被直接截断、玩家完全看不到也点不到。

**为什么移除"设为默认"按钮**：原设计（见 0.6.0 记录）是有意设计的两步分离——
左侧列表点击只切换"选中查看"，需要额外点一次"保存并启用"/"设为本地默认"按钮
才会把当前查看的 Provider 记为待启用目标。但用户反馈这个流程不必要，多了一步
操作。新设计：点"保存"这一个动作现在同时完成"保存字段改动"和"设为默认"两件
事，不再需要额外的确认步骤。

---

## 0.9.0　配置界面提示文字位置 + 修复潜在的文字/控件重叠

（根因分析与 0.11.0 的"控件区与底部提示文字共用同一条基准线"相同，此处是首次
发现并修复。）

**"强制服务器模式但未检测到服务器"从常驻警告改成拦截式确认弹窗**：原因同
0.11.0，此处是首次实施。原设计的常驻红色文字容易被忽略，也是重叠问题的主要
诱因之一。新设计的弹窗拦截比常驻文字更能确保玩家真的看到并做出选择。

---

## 0.8.0　英文 README + GitHub Actions 自动发布工作流

**为什么英文 README 不是逐行对照翻译**：1000+ 行的技术细节、开发过程记录、
注释规范这些内容对英语玩家实际帮助有限，逐行翻译且长期双语同步维护的成本也
过高，容易两份文档越改越不一致。而是聚焦"这是什么 / 有什么好处 / 怎么用 /
几条必须知道的注意事项"这个玩家最需要的部分，完整认真地写（不是中文版的
缩水版），末尾链接回中文版作为完整技术参考。

**`extract_changelog.py` 的设计**：从 README 的"八、更新日志"章节里，按版本号
精确匹配对应的 `### YYYY-MM-DD　<版本号> ...` 标题行，截取到下一条日志之前的
内容，作为 Release Notes 自动填充。找不到对应版本号时会让 workflow 显式失败
（而不是发布一个空描述的 Release），提醒开发者：**打 tag 前必须先在 README
更新日志里补好对应版本号的条目**，这是本次新增的一条硬性前置步骤。

---

## 0.7.0　聊天历史界面行高 bug + 新增对话分组

**行高 bug 的根因和误判历史**：1.21.1 的 `AlwaysSelectedEntryListWidget` /
`EntryListWidget` 构造函数签名是 `(client, width, height, y, itemHeight)`
（yarn 1.21.1+build.3 实测 + 在线 javadoc 核对），第 5 参数是 **itemHeight
（行高）**，不是 bottom。但 `ChatHistoryScreen` 与 `ModelSelectionScreen`
误以为是老版本 `(client, width, height, top, bottom)`，把 `this.height - 40`
（本应是 bottom）传成了 itemHeight，导致每条记录行高 = 整个列表区域高度，一屏
只能看到一条消息。这是项目早期"1.21.1 itemHeight 不可配置"这一误判的延续：
反编译 jar 只能看到参数类型 `(client, int, int, int, int)` 看不到参数名，叠加
"1.20.x 是 6 参数 (top,bottom,itemHeight)"的先入为主，把第 5 参数想成了
bottom。实际上 itemHeight 一直可配置，只是被误用了。

**分组纯客户端按时间推断的取舍**：纯客户端模式没有服务端分组信息可用，统一用
时间聚类避免两套数据源/两套渲染逻辑。代价是边界不够精确（两个间隔不足 30 秒的
独立对话会被合并、一个沉默超 30 秒的长对话会被拆开），对"回看个大概"可接受，
不满意可调 `GROUP_GAP_MS`。

---

## 0.6.2　RateLimiter 单元测试 + 限流逻辑抽取

**为什么只测限流不测缓存**：`TranslationService` 的缓存逻辑需要 mock
`TranslationProvider` + `WorldDictionary`，mock 代码量可能超过测试价值。限流
逻辑是纯并发控制、不依赖任何 MC 类，抽取后可以直接测，收益比最高。并发竞争
场景（50 线程同时调用）是手动测试几乎无法覆盖的——如果 synchronized/双检查
有缺陷，这个测试会在 CI 里立刻报警。

---

## 0.6.1　暂停菜单入口 + 修复聊天历史记录找不到入口

**为什么不用 Mixin**：Fabric API 的 `ScreenEvents.afterInit` 事件已足够在
`GameMenuScreen` 初始化后动态追加按钮，Mixin 为加一个按钮属于杀鸡用牛刀。

---

## 0.6.0　纯客户端本地设置面板的"获取模型列表"功能

**`ModelSelectionScreen` 解耦 `ClientConfigState` 的原因**：原来这个 Screen
硬编码从 `ClientConfigState` 读写模型字段，导致用
`ClientOnlyTranslationConfig` 的本地面板无法复用。改造后通过 `currentModel`
参数 + `selectionCallback` 回调传值，不耦合任何配置类，两个面板都能用。

---

## 0.5.0　说话者收不到自己消息的回显 + Mock Provider 醒目警告 + 新增聊天历史记录

**说话者自己消息回显修复的根因**：`SpatialChatHandler` 计算候选听众时会排除
说话者本人（`!p.getUuid().equals(sender.getUuid())`），且拦截了原版聊天广播，
导致说话者永远收不到自己刚发消息的任何回显——无论 VISIBLE（聊天框）还是
AUDIBLE（物品栏字幕），自己都看不到自己说了什么。

**`displayMode` 跟随"本次发言时其他听众的主导模式"的设计**：VISIBLE 听众更多
时回显走聊天框，AUDIBLE 听众更多时回显走物品栏字幕，没有任何听众时默认
VISIBLE（聊天框更保险，不会一闪而过）。

**Mock Provider 醒目警告的原因**：Mock Provider 只是给原文加 `[语言代码]` 前缀
的占位符，不调用任何真实翻译 API——这是纯客户端模式和服务端配置的**默认值**，
玩家装完模组直接体验很容易把这个占位效果误认成"翻译没生效"（真实反馈案例）。

**历史记录不落盘的取舍**：这是有意为之的取舍（见 `ChatHistoryManager` 类
注释），不是遗漏。纯客户端模式（`ClientOnlyChatTranslator`）下的 CLIENT_ONLY
历史记录条目没有可靠的 `speakerName`（这条路径没有服务端下发的说话者展示名，
只有聊天原文），历史界面对这类条目显示 `?` 占位。

---

## 0.4.0　VISIBLE 模式字幕不显示，临时改回聊天框

**问题**：能看见对方（VISIBLE 模式）时，字幕不会显示在玩家模型旁边。早期记录
的"alpha 通道修复"（`0xFFFFFF` → `0xFFFFFFFF`）实测**无效**——字幕仍不显示，
说明 alpha 不是（或不是唯一的）根因。`WorldSubtitleRenderer` 的世界空间渲染
存在未定位的底层问题，暂不具备运行环境实测确认。

**为什么临时改回聊天框**：因为服务端已按距离/视线把听众拆成 visible /
audibleOnly 两批、只给看得到的人发 VISIBLE 包，所以聊天框里天然只出现"我看得
见的那几位"说的话——满足"聊天框内容只能是我看到的这几位"。AUDIBLE 模式
完全不变，对应用户"看不到的情况下还是按照原来的时间来"。

**`WorldSubtitleRenderer` 根因候选**（均未实测确认，按怀疑程度排序）：
1. `findEntity` 按 UUID 在 `client.world.getPlayers()` 匹配说话者实体，若匹配
   失败会直接跳过、什么都不画（理论上游程 UUID 应一致，但未实测过）。
2. `WorldRenderEvents.AFTER_ENTITIES` 阶段的 `context.consumers()` 是世界渲染
   管线的 Immediate，其 SEE_THROUGH 层缓冲可能在帧末统一 flush；若被其他渲染
   阶段提前 `end()` 或深度/混合状态异常，文字可能被画了但肉眼不可见。
3. 负 Y 缩放 `matrices.scale(-scale,-scale,scale)` + `camera.getRotation()` 的
   billboard 组合在某些视角下可能被背面剔除或被深度遮挡。

---

## 0.3.2　新增 7 种语言本地化

**各语言翻译习惯的考量**：各语言均遵循该语言区的 Minecraft 官方菜单翻译习惯
（如菜单路径 "Settings → Controls → Key Binds" 在日语为「設定 → 操作 →
キーバインド」、德语为「Einstellungen → Steuerung → Tastenbelegung」），避免
机翻味道。Provider 名称（OpenAI/Claude/Gemini 等）保留原名，括号里的公司名按
各语言习惯处理（中文保留公司中文名，其他语言统一用英文公司名）。德语保留英文
术语 Provider/Endpoint/Log（德国 IT 社区习惯）。

---

## 0.3.1　编译错误修复：TextFieldWidget 密码遮盖 API + 注释 Unicode 转义

**`setRenderPasswordReveal` 不存在的踩坑**：`TextFieldWidget
.setRenderPasswordReveal(boolean)` 在 1.20.x 及之前存在，1.21.1 已移除。改为
`setRenderTextProvider(BiFunction)`，传入一个把字符替换成 `•`（U+2022）的函数
实现同等效果。代价：失去原版"按住可短暂显示明文"的交互（本项目用不到）。

**注释里 `\uXXXX` 的踩坑**：Java 编译器在词法分析阶段（早于注释识别）就会处理
`\uXXXX` 序列，注释里的 `\uXXXX`（`X` 非法十六进制）会导致编译失败。改为
`U+XXXX` 形式。

---

## 0.3.0　字幕位置改造 + 强制客户端模式修复 + 性能与稳定性优化

**强制客户端模式 bug 的根因**：玩家在配置界面设置了"强制纯客户端模式"后，如果
服务器装了 MCCF，客户端仍按服务器模式运行（不翻译聊天栏），而不是只做本地
翻译。根因：服务端 `SpatialChatHandler` 只看"服务器是否装了 MCCF"，不知道玩家
的客户端模式偏好，依旧拦截原版聊天改发 `SubtitlePayload`，导致客户端收不到
原版 CHAT 事件、`ClientOnlyChatTranslator` 不触发。修复：新增
`ModePreferencePayload`（C2S 网络包），客户端在加入服务器和切换模式时通过它
通知服务端自己的模式偏好。

**翻译失败结果不再写入缓存的原因**：此前网络瞬断时失败结果被永久缓存，导致
网络恢复后仍无法翻译。缓存改为 LRU 策略，最大 5000 条，增加 TTL（1 小时）。

**为什么抽取 `translateAndAppend` 方法**：复用翻译逻辑（CHAT 事件监听器和
SubtitlePayload 退回方案两个调用点共用），避免重复代码。

---

## 2026-07-28　目标版本从 1.21.8 切换到 1.21.1

**为什么切到 1.21.1**：按项目目录命名（`MC-Conversation-Framework-1.21.1`）
和模组生态兼容性需求。1.21.1 是 1.21.x 系列的早期稳定版本，拥有最广泛的模组
生态兼容性（大量 1.21.x 模组以 1.21.1 为基线），同时 `WorldRenderEvents`、
`HudElementRegistry`、`KeyBinding` 字符串分类等本项目依赖的 API 在 1.21.1 和
1.21.8 之间完全一致，源代码无需任何改动。

**API 兼容性核对的过程和误判修正**：最初评估时认为 1.21.1 与 1.21.8 在本项目
用到的 API 上完全一致。本地编译验证后发现实际有 3 处差异，已全部修复
（`PacketCodecs.BOOLEAN` → `BOOL`、`HudElementRegistry.addLast` →
`HudRenderCallback.EVENT.register`、`getTickProgress` → `getTickDelta`）。
> ⚠️ 此处对 5 参数构造器的语义判断有误：1.21.1 的 5 参数是
> `(client, width, height, y, itemHeight)`，第 5 参数是 itemHeight 不是 bottom。
> 当时误当 bottom 导致行高 bug，已于 0.7.0 修正。

---

## 2026-07-28　本地编译报告修复的 3 处 API 差异

**API 签名核实方式**：直接用 PowerShell + `System.IO.Compression.ZipFile` 打开
`~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-clientonly-1.21.1-...-v2.jar`
反编译查看 `AlwaysSelectedEntryListWidget` 和 `RenderTickCounter` 的实际方法
签名，再用 `javap -p` 输出完整签名。比查 Fabric 在线文档（不同版本混在一起
容易看错）或问 AI（容易拿新版本答案套旧版本）都更可靠——本地 jar 就是项目
实际编译用的那一份，看到什么签名编译器就认什么签名。

---

## 2026-07-28　JAR 命名 + 版本号策略

**为什么改名为 MCConversationFramework**：之前 JAR 名是 `mccf-0.1.0.jar`，太
简略；现在是 `MCConversationFramework-0.2.0.jar`。

---

## 2026-07-17　包名修复 + 构建配置修复

**反馈"所有 `net.minecraft.*` 包都找不到"之后的逐一核对过程**：逐个核对了
项目里引用的每一个 Minecraft 类的包路径（对照 Yarn 官方 Maven 文档），修复
`ServerPlayerEntity`、`RaycastContext` 等包路径错误，以及 `build.gradle` 引用
未定义变量、Loom 插件版本浮动、mappings 选择等构建配置问题。

---

## 版本从 1.21.11 降到 1.21.8

**为什么降到 1.21.8**：实现"贴近说话人显示字幕"功能时发现 Fabric API 的
`WorldRenderEvents` 在 1.21.9/1.21.10 的移植中被官方整体移除（渲染管线重构，
无替代方案）。为保留这个核心体验，把目标版本从 1.21.11 降到 1.21.8（该 API
最后一个正常工作的版本）。

---

## Gradle 版本改回 9.2.0

**为什么改回 9.2.0**：Gradle 一度从验证过的 9.2.0 改成"官方文档写的
8.14/9.0"，结果实测报错：`Could not resolve net.fabricmc:fabric-loom:1.14.10`
（No matching variant）。原因是 **Loom 1.14.10 这个精确 patch 版本实际发布时
用的 Gradle Plugin API 是 9.2.0**，比"官方支持 8.14/9.0"这个粗粒度声明更
严格——不同 patch 版本对 Gradle 的实际要求可能比大版本号声明更新更快。

---

## 本地编译报告修复的 3 处 bug（1.21.8 时代）

**`PacketCodecs.BOOLEAN` vs `BOOL` 的版本差异发现过程**：确认 1.21.8+build.1
实际用的字段名是 `PacketCodecs.BOOLEAN`（此前记录中"应为 BOOL"的说法有误，以
本地实测为准）。

> **2026-07-28 修正**：这条结论只在 1.21.8 上成立。后续把目标版本切回 1.21.1
> 时本地编译验证发现，1.21.1 上 Yarn mappings 的字段名仍然是 `BOOL`，到 1.21.8
> 才改名为 `BOOLEAN`。也就是说**当年"应为 BOOL"那条更早的记录其实是对的**，
> 只是当时在 1.21.8 上验证成了 `BOOLEAN` 就以为是错的——同一段代码在不同 MC
> 版本上字段名不同。

---

## 崩溃修复：未连接服务器时调整窗口导致崩溃

**根本原因**：`MCCFConfigScreen.init()` 无条件调用
`ClientPlayNetworking.send(...)`，但 `init()` 不仅在首次打开 Screen 时执行，
Minecraft 在玩家**调整游戏窗口大小**时也会对所有当前打开的 Screen 重新走一遍
`init()`（原版 resize 流程的一部分）。若玩家尚未进入世界/连接服务器就打开了
配置界面，`send()` 在没有活跃网络连接时调用会抛异常，这个异常发生在 resize
流程内、未被捕获，直接导致客户端崩溃。修复：改用
`ClientPlayNetworking.canSend(payloadId)` 方法（专门用于判断"当前是否处于
游戏中、可以发送该 payload"，未在游戏中时返回 `false` 而不是抛异常）。

---

## 字幕不显示问题修复

**根本原因**：`WorldSubtitleRenderer.java`（VISIBLE 模式，字幕悬浮在说话者
上方）里有一处颜色值 bug：文字颜色用的是 `0xFFFFFF`，在 Minecraft 的 ARGB
颜色格式下，这个值缺少最高 8 位的 alpha 通道，等价于 `0x00FFFFFF`（alpha=0，
完全透明）——也就是说字幕确实被渲染了，只是渲染成了完全透明、肉眼不可见的
状态。已改为 `0xFFFFFFFF`（完全不透明的白色）。`HotbarSubtitleRenderer.java`
（物品栏上方模式）用的是官方 `Colors.WHITE` 常量，颜色值本身没有问题。

---

## 2026-07-22　README 整理

**发现的问题及修复考量**：第一次通读全部源码后发现 README 存在几处与代码
不符/内部矛盾的问题——章节编号混乱（"四"和"四"共用编号、顺序被打乱）；
目录结构过时（缺少整个 `config/` 包）；Fabric API 版本描述前后矛盾（开头写
0.130.0，编译章节写 0.141.5+1.21.11，是版本从 1.21.11 降到 1.21.8 时残留的
旧文字）；接入 Provider 示例代码与当前架构不符（原示例写
`translationService.registerProvider(...)`，但实际已统一收敛到
`ProviderFactory`）。另外发现 `RequestModelListPayload` /
`ModelListResponsePayload` 两个死代码类（不影响运行，当时没有主动删除，已注明
待确认是否需要清理，后于 1.0.0 删除）。

---

## 2026-07-22　新功能：纯客户端模式

**设计上刻意保持和现有服务端权威配置完全分离的原因**：本地翻译配置本来就只
影响玩家自己看到的结果，不需要、也不应该套用"仅 op 可改"这套是为"会影响所有
人"的服务端配置设计的规则。

**"从服务器同步"按钮为什么复用已有数据**：复用了已有的 `ConfigSnapshotPayload`
数据（`ClientConfigState`），没有新增网络包，只拷贝 Provider/模型名/Endpoint
这些公开字段，不拷贝 API Key。检测方式是
`ClientPlayNetworking.canSend(RequestConfigPayload.ID)`——复用了已有的配置
请求通道，不需要额外发一个"探测"包等服务端回应。
