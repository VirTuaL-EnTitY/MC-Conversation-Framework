# MC Conversation Framework (MCCF)

面向 Minecraft 多人服务器的沉浸式跨语言交流基础设施。核心链路：
文字聊天拦截 → 空间化听觉判定（距离 + 射线遮挡）→ 动态对话上下文（Conversation）
→ 可插拔翻译 Provider（OpenAI/Claude/Gemini/DeepL/Kimi/DeepSeek/Ollama）
→ 客户端空间化字幕（悬浮头顶 / 物品栏上方）→ 游戏内配置界面（ModMenu 集成 +
按键呼出）。

目标版本：**Minecraft 1.21.1 · Yarn Mappings 1.21.1+build.3 · Fabric Loader 0.15.11 ·
Fabric API 0.116.15+1.21.1 · Fabric Loom 1.7.3 · Gradle 8.8 · Java 21**

> **为什么固定在 1.21.1？** 1.21.1 是 1.21.x 系列的早期稳定版本（2024-08
> 发布），Fabric API 的 `WorldRenderEvents`——本项目用它实现"字幕悬浮在
> 说话者附近"的效果——在该版本上工作正常。后续 1.21.9/1.21.10 的移植
> 过程中，因为 Minecraft 原版渲染管线发生了根本性重构，`WorldRenderEvents`
> 被官方**整体移除**，目前（截至本文写作时）还没有稳定的替代方案。1.21.1
> 既能保证"贴近说话人显示字幕"这个核心体验能够实现，又拥有最稳定的
> 1.21.x 模组生态兼容性（大量 1.21.x 模组以 1.21.1 为基线版本）。

> **关于 mappings 的说明**：本项目使用 **Yarn**（不是 Mojang 官方映射）。
> 如果你看到网上教程用 `net.minecraft.world.entity.Entity` 这类命名，
> 那是 NeoForge / Mojang mapping 风格，和本项目用的 Yarn 命名
> （`net.minecraft.entity.Entity`）是两套完全不同的体系，不要混用。

---

## 一、在你自己电脑上编译

### 1. 前置要求
- **JDK 21**（推荐 Temurin/Adoptium 或 Microsoft Build of OpenJDK）
- **Gradle 8.8**（本项目用的 Fabric Loom 1.7.3 实测要求 Gradle ≥ 8.6，
  用 8.8 是经过验证的稳定版本；wrapper 已锁定 8.8，正常情况下不需要
  你本机单独装 Gradle，见下）
- 稳定的网络连接（首次构建需要从 Fabric/Maven/Mojang 仓库下载依赖，几百 MB）

### 2. 关于 Gradle Wrapper
本项目**没有附带** `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` ——
这几个文件里的 `gradle-wrapper.jar` 是二进制文件，我在无网络的沙盒环境里
无法可靠生成一份真实可用的（伪造内容可能导致你本地校验失败或下载损坏的
Gradle）。`gradle/wrapper/gradle-wrapper.properties` 已经配置好指向
Gradle 8.8，你只需要在本地生成对应的 wrapper 脚本：

```bash
# 前提：本机已安装任意版本的 Gradle（哪怕版本不同也没关系，wrapper 任务
# 只是根据已有的 gradle-wrapper.properties 生成 gradlew/gradlew.bat）
gradle wrapper

# 之后就可以用 ./gradlew 了，它会自动下载并使用 8.8
./gradlew build
```

如果你本机完全没装过 Gradle，也可以直接下载 Gradle 8.8 一次性用：
https://gradle.org/releases/ → 找 8.8 → 解压后用它的 `bin/gradle` 执行
上面的 `gradle wrapper` 命令。

生成 wrapper 后，以后就可以用 `./gradlew build`（Windows 用 `gradlew.bat build`）了。

### 3. 构建
```bash
cd MC-Conversation-Framework-1.21.1
gradle build          # 或 ./gradlew build（如果已生成 wrapper）
```
构建产物在 `build/libs/MCConversationFramework-0.2.0.jar`，把它丢进服务器和客户端的 `mods/` 文件夹即可
（服务器和客户端都要装，因为这是一个同时含服务端逻辑和客户端渲染的模组）。

同时确保服务器和客户端都装了对应版本的 **Fabric API**（`0.116.15+1.21.1` 或更高的
1.21.1 兼容版本），可以在 Modrinth / CurseForge 上下载。装了
[ModMenu](https://modrinth.com/mod/modmenu) 11.0.4（1.21.1 对应版本）的话还能获得
游戏内配置界面的图标入口，非必需。

### 4. IDE 推荐
用 IntelliJ IDEA 打开项目根目录，Gradle 插件会自动识别。首次导入较慢（下载
Minecraft 反混淆源码 + 依赖），耐心等待即可。跑 `genSources` 后能看到带注释的
Minecraft 源码，便于后续开发调试。

---

## 二、当前功能范围

- ✅ 拦截原版聊天，替换为空间化点对点分发
- ✅ 距离 + 射线遮挡判定"谁能听到/看到"（`HearingResolver`）
- ✅ 动态 Conversation 合并/拆分/超时释放（`ConversationManager`）
- ✅ 可插拔翻译 Provider 架构，内置 **7 个真实 Provider** + Mock：
  OpenAI、Claude (Anthropic)、Gemini、DeepL、Kimi (Moonshot AI)、
  DeepSeek、Ollama（本地）
- ✅ **游戏内配置界面**：ModMenu 集成 + 独立按键呼出，可视化切换 Provider、
  填写 API Key / 模型名 / API Endpoint，仅 op 可编辑，普通玩家只读查看
- ✅ 世界词典（专有名词占位替换，保证跨 Provider 翻译一致性）
- ✅ 客户端自动上报 Minecraft 语言设置作为目标语言
- ✅ 双模式字幕：VISIBLE（显示在说话者模型旁边、靠近相机的一侧）/ AUDIBLE（物品栏上方，多人堆叠布局）
- ✅ `/mccf` 管理命令（status / provider / dictionary / reload）
- ✅ API Endpoint 可自定义（接自建反代 / 兼容网关）+ 一键"恢复默认"
- ✅ 配置界面一键拉取 Provider 可用模型列表
- ✅ 配置界面一键导出日志（`logs/latest.log` 中 MCCF 相关行 + 完整日志）
- ✅ 配置界面多语言（`Text.translatable()`，跟随客户端语言，已提供简体中文翻译）
- ✅ **纯客户端模式**：服务器没装 MCCF 时自动降级为"仅本地翻译聊天栏文字"
  （无空间判定、无字幕），也可手动强制切换，详见"三之二、纯客户端模式"
- ⏳ 语音识别（STT）—— 本版本以文字聊天为输入，语音识别是下一步扩展方向
  （建议对接 Simple Voice Chat 的音频 API 作为输入源，替换 SpatialChatHandler
  的触发源即可，其余流程不需要改动）

---

## 三、配置界面怎么用

**两个入口**：
1. 装了 [ModMenu](https://modrinth.com/mod/modmenu) 的话，Mods 列表里
   MCCF 旁边会出现齿轮图标，点击打开。
2. 不装 ModMenu 也能用——游戏内默认绑定了一个按键（默认未指定具体键位，
   在 `设置 → 控制 → 按键绑定 → MC Conversation Framework` 分类下可以
   自己绑定一个键，绑定后按键直接呼出配置界面）。

**权限规则**：只有服务器 op 能修改 Provider / API Key / 模型 / Endpoint，
提交后由服务端二次校验权限（不是仅凭客户端界面判断），非 op 玩家打开
界面能看到当前生效的是哪个 Provider，但所有输入框禁用、看不到真实
API Key。

**每个 Provider 需要填什么**：
- OpenAI / Claude / Gemini / Kimi / DeepSeek：填 API Key + 模型名（有默认值，
  通常不用改）+ 可选的自定义 API Endpoint（默认走各家官方地址）
- DeepL：只需要 API Key（免费版 Key 以 `:fx` 结尾，界面会自动识别走免费端点）
- Ollama：不需要 API Key（本地部署默认无鉴权），需要填 Endpoint（默认
  `http://localhost:11434`）和模型名（默认 `llama3.2`，换成你本地已拉取的模型名）
- Mock：调试用，无需任何配置

**一键获取模型**：点"获取模型"按钮会调用对应 Provider 的模型列表接口
（OpenAI/Kimi/DeepSeek/Claude 用 `GET /v1/models`，Gemini 用
`GET /v1beta/models`，Ollama 用 `GET /api/tags`），结果打印到聊天栏方便复制。
DeepL（固定引擎无模型概念）和 Mock 不支持，按钮自动置灰。可以用输入框里
**尚未保存**的 Key 直接测试，不需要先保存一次。

**导出日志**：点"导出日志"按钮，从 `logs/latest.log` 提取 MCCF 相关行 /
复制完整日志，两者都输出到 `<游戏目录>/mccf-exports/`，带时间戳文件名，纯本地操作。

保存后服务端会立即重建对应的 Provider 实例并生效，无需重启服务器。

---

## 三之二、纯客户端模式（服务器没装 MCCF 时）

如果你连的服务器没有安装 MCCF（比如公共服/别人的服），模组没法拦截聊天做
点对点分发、也没法做距离/遮挡这类需要服务端参与的判定——所有人还是会照常
收到原版广播的聊天。这种情况下 MCCF 会自动降级为**纯客户端模式**：不做
空间判定、不渲染悬浮/物品栏字幕，只是把收到的每一条聊天消息在本地翻译成
你自己客户端设置的语言，追加显示在聊天栏里（原文照常显示，译文是追加的
一行，前面带 `⇄` 标记），比如：
```
<Steve> こんにちは
⇄ Hello
```

**怎么进入这个模式**：
- **自动检测**（默认）：进入服务器时会检测对方是否声明了 MCCF 的网络通道，
  没检测到就自动切换，不需要你做任何操作。
- **手动强制**：主配置界面（按键呼出或 ModMenu）里点"纯客户端本地翻译设置"，
  进"运行模式"选择"强制纯客户端模式"——即使服务器装了 MCCF，也只用本地
  翻译；反过来选"强制服务器模式"可以在自动检测误判时手动纠正。这个选择
  保存在本地（`config/mccf/client-mode.json`），跟随你的客户端，不是针对
  某个服务器的设置。

**配置管理**：纯客户端模式用的翻译 Provider 配置是一份完全独立于服务端的
本地文件（`config/mccf/client-only-config.json`），任何人都能自由编辑，
不需要 op 权限——毕竟这份配置只影响你自己本地看到的翻译结果，不会分发给
任何其他玩家。填法和主配置界面一样（Provider + API Key + 模型名 +
Endpoint）。如果当前连的服务器恰好装了 MCCF，还可以点"从服务器同步"一键
把服务器正在用的 Provider/模型名/Endpoint 拷贝过来，省得重新选一遍——但
**不会**拷贝 API Key（即使你是 op、快照里带真实 Key 也不拷贝），密钥必须
你自己填。

**已知限制**：
- 没有对"是否已经是目标语言"做检测，每条消息都会真的发一次翻译请求
  （Mock Provider 除外，它本身会在同语言时直接透传，不消耗额度）。
- 不会给自己发的消息做二次翻译（服务器回显自己说的话时会跳过），但其他
  语言相同的消息目前没有去重/节流，人多的服务器可能会比较费 API 额度。
- 由于没有服务端参与，这只是"翻译 + 展示美化"，**不是**真正的信息隔离——
  所有人依然会收到原版广播的完整聊天，纯客户端模式改变的只是你自己客户端
  怎么显示它。

---

## 四、如何接入其他翻译 Provider（内置 7 个之外的）

打开 `net.mccf.mod.translation.provider.TranslationProvider` 接口，参照
`MockTranslationProvider` 写一个新的实现类，例如：

```java
public class MyTranslationProvider implements TranslationProvider {
    @Override public String getId() { return "myprovider"; }
    @Override public String getDisplayName() { return "My Provider"; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
        // 1. 用 java.net.http.HttpClient 异步调用目标 API
        //    （可参照 HttpProviderSupport 里的通用请求/超时/错误处理逻辑）
        // 2. 把 request.contextMessages() 作为对话历史一起传给模型，
        //    帮助它理解代词指代、术语一致性等
        // 3. 返回 CompletableFuture<TranslationResult>
    }
}
```

然后在 `ProviderFactory` 里注册对应的创建逻辑，并在 `ProviderDefaults` 里
补上默认的 endpoint / model 值（供"恢复默认"按钮使用）。

API Key 等敏感信息建议放在 `config/mccf/config.json` 之外单独的、不提交到版本库
的文件里（比如环境变量或 `secrets.json` 并加入 `.gitignore`）。

---

## 五、目录结构

```
src/main/java/net/mccf/mod/
├── MCCF.java                        模组入口（服务端）：注册网络包、事件监听、命令
├── config/
│   ├── MCCFConfig.java              服务端配置（距离阈值、超时时间、当前 Provider 等）
│   ├── ProviderConfig.java          单个 Provider 的配置项（apiKey / model / endpoint）
│   └── ProviderDefaults.java        每个 Provider 的默认 endpoint / model，"恢复默认"的数据源
├── context/
│   ├── Conversation.java            单个对话组：参与者 + 近期消息
│   └── ConversationManager.java     合并/拆分/超时释放
├── dictionary/WorldDictionary.java  世界词典 + 占位符替换逻辑
├── translation/
│   ├── TranslationService.java      词典 -> Provider -> 结果还原 的调度层
│   └── provider/
│       ├── TranslationProvider.java          可插拔接口
│       ├── ProviderFactory.java              根据配置创建对应 Provider 实例
│       ├── HttpProviderSupport.java          各 Provider 共用的 HTTP 请求/超时/错误处理
│       ├── MockTranslationProvider.java      调试用示例实现
│       ├── OpenAiTranslationProvider.java
│       ├── ClaudeTranslationProvider.java
│       ├── GeminiTranslationProvider.java
│       ├── DeepLTranslationProvider.java
│       ├── KimiTranslationProvider.java
│       ├── DeepSeekTranslationProvider.java
│       └── OllamaTranslationProvider.java
├── spatial/
│   ├── HearingResolver.java          距离 + 射线遮挡判定
│   ├── SpatialChatHandler.java       核心调度：拦截聊天 -> 判定 -> 翻译 -> 分发
│   └── PlayerLanguageRegistry.java   玩家语言运行时注册表
├── network/                          C2S/S2C 网络包定义（见下方说明）
└── command/
    ├── MCCFCommand.java              /mccf 管理命令（status / provider / dictionary / reload）
    └── ConfigSyncHandler.java        配置界面的服务端数据同步：MCCFConfig <-> JSON 快照，op 权限校验

src/client/java/net/mccf/mod/client/
├── MCCFClient.java                   客户端入口：注册渲染器、按键绑定、网络接收器
├── config/
│   ├── MCCFConfigScreen.java         配置界面主 Screen
│   ├── ModelSelectionScreen.java     "获取模型"结果的子列表 Screen
│   ├── MCCFModMenuIntegration.java   ModMenu 集成入口（仅 ModMenu 安装时加载）
│   ├── ClientConfigState.java        客户端本地配置状态单例（含未提交的编辑，来自服务端快照）
│   ├── ClientProviderConfig.java     客户端内存中单个 Provider 配置副本（服务端/本地两处复用）
│   ├── ClientOnlyTranslationConfig.java  纯客户端模式：玩家自己的本地翻译配置，持久化到
│   │                                      config/mccf/client-only-config.json，任何人可编辑
│   └── ClientOnlyConfigScreen.java   纯客户端模式的"本地翻译设置"子界面（从主界面按钮打开）
├── mode/ClientOnlyModeManager.java   判断当前是否该走纯客户端模式：自动检测 + 手动强制覆盖，
│                                      持久化到 config/mccf/client-mode.json
├── chat/ClientOnlyChatTranslator.java  纯客户端模式下的本地聊天翻译：监听收到的聊天消息，
│                                        异步翻译后追加显示，不做空间判定
├── subtitle/
│   ├── ActiveSubtitle.java           客户端内存态字幕数据
│   ├── SubtitleManager.java          接收、超时管理、多人去重排序
│   ├── WorldSubtitleRenderer.java    VISIBLE 模式：说话者模型旁边（靠近相机侧）
│   └── HotbarSubtitleRenderer.java   AUDIBLE 模式：物品栏上方堆叠显示
└── util/LogExporter.java             日志导出逻辑（提取 MCCF 相关行 + 完整日志复制）
```

**关于 `network/` 包**：目前实际被 `MCCF.java` / `MCCFClient.java` 注册使用的网络包是
`SubtitlePayload`、`LanguageReportPayload`、`RequestConfigPayload`、
`ConfigSnapshotPayload`、`UpdateConfigPayload`、`RequestModelsPayload`、
`ModelsResultPayload` 共 7 个。包内还有 `RequestModelListPayload` /
`ModelListResponsePayload` 两个类——审查代码时发现它们没有被任何地方注册或引用，
应该是"获取模型"功能早期迭代时留下的死代码（后来改用了
`RequestModelsPayload`/`ModelsResultPayload` 这条路径）。这两个文件目前**不影响
运行**，只是冗余，我没有主动删除，如果你确认不需要保留可以告诉我直接清掉。

---

## 六、配置文件（首次运行后自动生成）

`config/mccf/config.json`：
```json
{
  "subtitleVisibleRange": 32.0,
  "hearingRange": 48.0,
  "conversationRange": 48.0,
  "conversationIdleTimeoutSeconds": 120,
  "enableOcclusionCheck": true,
  "activeProvider": "mock",
  "showOriginalText": true
}
```

`config/mccf/dictionary.json`：世界词典，格式为
`{ "词条": { "语言代码": "该语言下的译文", ... }, ... }`，也可以用
`/mccf dictionary add <term> <lang> <translation>` 命令在游戏内添加。

---

## 七、已知限制 / 后续可扩展方向

- 语音识别未实现（见"二、当前功能范围"）。
- `HearingResolver` 目前只做"说话者→听众"单向射线检测一次，性能上对大量玩家
  同时说话的场景（比如几十人的活动服）没有做批量优化，如果后续遇到性能问题，
  可以考虑给听众按区块分桶、或限制每 tick 处理的聊天消息数。
- 字幕的显示时长按文本长度粗略估算，可以后续做成可配置项。
- Conversation 的合并策略目前是"简单并集"，没有处理"两个大对话组因为一个
  中间人同时能听到而被强行合并"这种边界情况的精细化处理（比如设置合并的
  最小重叠人数阈值），如果实测中出现不合理的合并，可以在
  `ConversationManager.recordUtterance` 里加更细的判定逻辑。
- 世界广播、NPC 对话、剧情事件等在设计文档中提到的"未来扩展"尚未实现，
  但整体架构（Provider 可插拔 + Conversation 上下文隔离）是为它们预留的。
- `network/` 包里有两个未注册使用的死代码类，见"五、目录结构"末尾说明。
- VISIBLE 模式的字幕位置已于 0.3.0 改为"说话者模型旁边、靠近相机的一侧"
  （腰部高度，水平偏移），不再是早期版本的"头顶上方一点"。长文本支持自动
  换行，远距离时有距离衰减。如果后续想进一步做成基于屏幕投影坐标的贴身定位，
  World 渲染管线在新版本上不稳定，需要单独一轮谨慎处理。
- **【已知 Bug / 0.4.0 临时绕开】** 上述"说话者旁边"的世界空间字幕在
  `WorldSubtitleRenderer` 中实测**仍不显示**（早期 alpha 修复无效，根因未定位，
  候选见"八、更新日志"0.4.0 条目）。0.4.0 起 VISIBLE 模式临时改走原版聊天框
  （`<名字> 译文` 一行），AUDIBLE 模式不受影响。待根因定位修复后再考虑切回
  世界空间字幕。
- **纯客户端模式（`ClientOnlyChatTranslator`）用到的 `ClientReceiveMessageEvents.CHAT`
  事件签名**：此前 README 里标注为"凭印象写、未本地编译验证"的风险点，在
  1.21.1 上经本地编译验证**签名正确**（5 参数：`message, signedMessage, sender, params, receptionTimestamp`），
  该风险点已消除。`CyclingButtonWidget.setValue(...)`（在"从服务器同步"按钮里
  用来刷新下拉框显示）此前同样标注为"凭印象写"，也已在 1.21.1 上编译通过，
  运行时行为正常。

---

## 八、更新日志

### 2026-07-29　0.4.1 修复：配置界面一打开就崩 Rendering screen（serverPanel is null）

**问题**：按按键绑定呼出 `MCCFConfigScreen`（或经 ModMenu 打开）后立即崩溃，崩溃
报告为 `NullPointerException: Cannot invoke "...ServerConfigPanel.render(...)"
because "this.serverPanel" is null`，发生在 `MCCFConfigScreen.render()`。

**根因**：经典的 Java "构造器里调用可重写方法"陷阱。`ProviderConfigPanel` 的构造器
里调用了 `this.selectedProvider = initialSelectedProvider()`，而 `initialSelectedProvider()`
是 abstract、由子类实现——子类实现里访问的是子类自己的实例字段
（`ServerConfigPanel.state`、`LocalConfigPanel.config`，二者都是字段初始化器赋值）。
Java 的实例字段初始化器在**父类构造器返回之后**才执行，因此父类构造器回调子类
override 时 `state` / `config` 仍是默认值 `null`，直接 NPE。

该 NPE 发生在 `MCCFConfigScreen.init()` 的 `new ServerConfigPanel(...)`（构造期
`super()` 内），赋值未完成、`serverPanel` 保持 `null`；`init()` 抛出的异常被上层
吞掉，界面仍被设为当前 Screen，下一帧 `render()` 解引用 `serverPanel` 时硬崩。
`LocalConfigPanel` 存在同样的 bug，只是 `ServerConfigPanel`（第 79 行）先崩。

旁证：`ModelSelectionScreen.render()` 同样直接解引用在 `init()` 里赋值的
`listWidget` 且不做 null 检查，却从不崩溃——说明本代码库 / MC 1.21.1 下 `init()`
确实在 `render()` 之前同步执行；因此 `serverPanel` 为 null 只能是 `init()` 抛异常
未完成赋值所致，而非"render 跑在 init 之前"的时序问题。

**修复**：把 `initialSelectedProvider()` 的调用从 `ProviderConfigPanel` 构造器移到
`init()` 方法开头。此时子类构造已全部完成、`state` / `config` 字段已初始化，override
能正常返回值。`selectedProvider` 在"构造完成到 `init()`"之间短暂为 `null`，但该窗口
内无任何代码读取它（`MCCFConfigScreen.init()` 是构造完立刻 `init()`），故安全。

**改动文件**：`ProviderConfigPanel.java`（构造器移除 override 调用 + 注释说明陷阱，
`init()` 里 deferred 赋值）、`gradle.properties`（`0.4.0` → `0.4.1`）。

版本号 `0.4.0` → `0.4.1`（按 9.1 规则升 patch：bug 修复，不改功能/构建）。

### 2026-07-29　0.4.0 修复：VISIBLE 模式字幕不显示，临时改回聊天框

**问题**：能看见对方（VISIBLE 模式）时，字幕不会显示在玩家模型旁边。早期记录
的"alpha 通道修复"（`0xFFFFFF` → `0xFFFFFFFF`）实测**无效**——字幕仍不显示，
说明 alpha 不是（或不是唯一的）根因。WorldSubtitleRenderer 的世界空间渲染存在
未定位的底层问题（候选根因见下），暂不具备运行环境实测确认。

**临时方案**（本轮改动）：
- VISIBLE 模式（看得到说话者）的消息**改走原版聊天框**，格式为 `<Steve> 译文`
  （仅译文一行，翻译由服务端已完成）。因为服务端已按距离/视线把听众拆成
  visible / audibleOnly 两批、只给看得到的人发 VISIBLE 包，所以聊天框里天然只
  出现"我看得见的那几位"说的话——满足"聊天框内容只能是我看到的这几位"。
- AUDIBLE 模式（看不到对方）**完全不变**：依旧走 `HotbarSubtitleRenderer`
  在物品栏上方堆叠显示字幕，原时长（2.5s + 60ms/字符，上限 8s）保持不变，
  对应用户"看不到的情况下还是按照原来的时间来"。
- `WorldSubtitleRenderer.java` 代码**保留不删**，类 Javadoc 顶部诚实标注了
  "当前不显示 + alpha 修复无效 + 根因未定位"及候选根因，待后续具备运行环境时
  定位修复后再决定是否切回世界空间字幕。

**WorldSubtitleRenderer 根因候选**（均未实测确认，按怀疑程度排序）：
1. `findEntity` 按 UUID 在 `client.world.getPlayers()` 匹配说话者实体，若匹配失败
   会直接跳过、什么都不画（理论上游程 UUID 应一致，但未实测过）。
2. `WorldRenderEvents.AFTER_ENTITIES` 阶段的 `context.consumers()` 是世界渲染管线
   的 Immediate，其 SEE_THROUGH 层缓冲可能在帧末统一 flush；若被其他渲染阶段提前
   `end()` 或深度/混合状态异常，文字可能被画了但肉眼不可见。
3. 负 Y 缩放 `matrices.scale(-scale,-scale,scale)` + `camera.getRotation()` 的
   billboard 组合在某些视角下可能被背面剔除或被深度遮挡。

**改动文件**：`MCCFClient.java`（VISIBLE 路由到聊天框 + 新增
`addVisibleToChatHud`）、`WorldSubtitleRenderer.java`（类 Javadoc 标注根因候选）、
`gradle.properties`（`0.3.2` → `0.4.0`）。

版本号 `0.3.2` → `0.4.0`（按 9.1 规则升 minor：功能性行为变更，字幕渲染目标
从世界空间改为聊天框）。

### 2026-07-29　0.3.2 新增 7 种语言本地化

在原有 `en_us` / `zh_cn` 基础上，新增 7 种常用语言的完整本土化翻译：

| 语言文件 | 语言 | 本土化要点 |
|---------|------|-----------|
| `zh_tw.json` | 繁体中文（台湾） | 用台湾习惯用语：伺服器/設定/金鑰/匯出/除錯 |
| `ja_jp.json` | 日语 | Minecraft 日语官方风格：サーバー/設定/キーバインド/プロバイダー |
| `ko_kr.json` | 韩语 | 설정/서버/프로바이더/키 바인딩 |
| `es_es.json` | 西班牙语 | Proveedor/Clave API/Asignación de teclas |
| `fr_fr.json` | 法语 | Fournisseur/Clé API/Assignation des touches |
| `de_de.json` | 德语 | 保留英文术语 Provider/Endpoint/Log（德国 IT 社区习惯） |
| `ru_ru.json` | 俄语 | Провайдер/API-ключ/Назначение клавиш |

各语言均遵循该语言区的 Minecraft 官方菜单翻译习惯（如菜单路径
"Settings → Controls → Key Binds" 在日语为「設定 → 操作 → キーバインド」、
德语为「Einstellungen → Steuerung → Tastenbelegung」），避免机翻味道。
Provider 名称（OpenAI/Claude/Gemini 等）保留原名，括号里的公司名按
各语言习惯处理（中文保留公司中文名，其他语言统一用英文公司名）。

版本号 `0.3.1` → `0.3.2`（按 9.1 规则升 minor：新增功能，虽然只是资源文件）。

### 2026-07-29　0.3.1 编译错误修复：TextFieldWidget 密码遮盖 API + 注释 Unicode 转义

0.3.0 本地编译验证时发现两处编译错误，均为 API 适配遗漏：

1. **`TextFieldWidget.setRenderPasswordReveal(boolean)` 在 1.21.1 上不存在**：
   `MCCFConfigScreen.java` 和 `ClientOnlyConfigScreen.java` 用了这个方法开启
   API Key 输入框的密码遮盖。该方法在 1.20.x 及之前存在，1.21.1 已移除。
   改为 `setRenderTextProvider(BiFunction)`，传入一个把字符替换成 `•`（U+2022）
   的函数实现同等效果。代价：失去原版"按住可短暂显示明文"的交互（本项目用不到）。
2. **`HttpProviderSupport.java` 注释里的 `\uXXXX` 触发"非法的 Unicode 逃逸"**：
   Java 编译器在词法分析阶段（早于注释识别）就会处理 `\uXXXX` 序列，注释里的
   `\uXXXX`（`X` 非法十六进制）会导致编译失败。改为 `U+XXXX` 形式。

两条踩坑已补入 9.2.5 版本兼容性踩坑汇总。版本号 `0.3.0` → `0.3.1`（patch）。

### 2026-07-29　0.3.0 重大更新：字幕位置改造 + 强制客户端模式修复 + 性能与稳定性优化

本轮更新基于 QA 审查报告，修复了多个功能与性能问题。版本号 `0.2.1` → `0.3.0`
（按 9.1 规则升 minor：涉及功能修复 + 新增网络包 + 渲染逻辑改造）。

**1. 字幕渲染位置改造（VISIBLE 模式）**
- 字幕从"说话者头顶上方一点"改为"说话者模型旁边、靠近相机的一侧"（腰部高度，
  水平偏移）。计算方式：取"说话者→相机"的水平方向向量并归一化，字幕位置 =
  实体位置 + 水平偏移 + 腰部高度偏移。相机几乎在说话者正上方时默认偏右侧。
- 长文本支持自动换行（按字符数估算宽度，超出时分行），避免单行过长遮挡画面。
- 远距离字幕有距离衰减（alpha 随距离降低），避免远处字幕干扰视线。
- 背景色统一为半透明黑色，保证不同亮度环境下可读性。
- 涉及文件：`WorldSubtitleRenderer.java`。

**2. 强制客户端模式 bug 修复（核心问题）**
- **问题**：玩家在配置界面设置了"强制纯客户端模式"后，如果服务器装了 MCCF，
  客户端仍按服务器模式运行（不翻译聊天栏），而不是只做本地翻译。
- **根因**：服务端 `SpatialChatHandler` 只看"服务器是否装了 MCCF"，不知道
  玩家的客户端模式偏好，依旧拦截原版聊天改发 `SubtitlePayload`，导致客户端
  收不到原版 CHAT 事件、`ClientOnlyChatTranslator` 不触发。
- **修复**：新增 `ModePreferencePayload`（C2S 网络包），客户端在加入服务器
  和切换模式时通过它通知服务端自己的模式偏好。服务端 `SpatialChatHandler`
  收到后，对 client-only 玩家跳过聊天拦截和字幕分发，让原版聊天广播通过，
  客户端 `ClientOnlyChatTranslator` 正常触发本地翻译。
- **退回方案**：旧服务端不认识 `ModePreferencePayload`（`canSend` 返回 false），
  客户端会从 `SubtitlePayload` 里提取原文走本地翻译，保证向下兼容。
- 新增文件：`ModePreferencePayload.java`、`ClientOnlyModeRegistry.java`。
- 涉及文件：`SpatialChatHandler.java`、`ClientOnlyModeManager.java`、
  `MCCFClient.java`、`ClientOnlyChatTranslator.java`、`MCCF.java`。

**3. 翻译服务缓存与稳定性优化**
- `TranslationService`：翻译失败的结果不再写入缓存（此前网络瞬断时失败结果被
  永久缓存，导致网络恢复后仍无法翻译）。
- 缓存改为 LRU 策略（`LinkedHashMap` access-order），最大 5000 条，超出自动
  淘汰最久未访问的条目。
- 缓存条目增加 TTL（1 小时），过期条目下次访问时自动删除。
- 涉及文件：`TranslationService.java`。

**4. ClientOnlyChatTranslator 优化**
- 抽取 `translateAndAppend` 方法复用翻译逻辑（CHAT 事件监听器和 SubtitlePayload
  退回方案两个调用点共用），避免重复代码。
- 新增 Provider 实例缓存（按 providerId 缓存，避免高频聊天时每条消息都 new
  Provider 短命对象）。
- 新增固定窗口限流（每秒最多 5 条翻译请求），超出丢弃并记警告日志，防止
  聊天刷屏时 API Key 被封。
- 涉及文件：`ClientOnlyChatTranslator.java`。

**5. 配置界面优化**
- `ModelSelectionScreen` 调用修复：此前模型选择界面未被正确调用（死代码），
  现在获取模型成功后会正确打开 `ModelSelectionScreen` 供玩家选择。
- `ClientOnlyConfigScreen` 新增强制服务器模式警告：当玩家选择"强制服务器模式"
  但服务器未检测到 MCCF 时，显示红色警告提示。
- 新增 Provider 提示文字（`mccf.config.provider_hint.*`），帮助玩家了解每个
  Provider 的配置要求。
- 涉及文件：`MCCFConfigScreen.java`、`ClientOnlyConfigScreen.java`。

**6. 首次加入提示**
- 客户端首次进入游戏世界时，在聊天栏显示一条提示（`mccf.tip.first_join`），
  引导玩家到"设置→控制→按键绑定"为 MCCF 绑定配置按键。整个客户端生命周期
  只提示一次，不随换服务器重置。
- 涉及文件：`MCCFClient.java`、`zh_cn.json`、`en_us.json`。

**7. 其他优化**
- `WorldDictionary`：缓存编译后的 `Pattern` 对象，词条变更时清除缓存，
  避免每次翻译都重新编译正则。
- `ProviderFactory`：未知 Provider ID 静默 fallback 到 Mock 时，新增
  `LOGGER.warn` 日志提示可能存在配置错误。
- `HttpProviderSupport`：用 Gson 替代手写 JSON 转义方法，提高可靠性。
- `LogExporter`：改为流式读取日志文件（逐行过滤），避免大日志文件 OOM。

### 2026-07-28　目标版本从 1.21.8 切换到 1.21.1
按项目目录命名（`MC-Conversation-Framework-1.21.1`）和模组生态兼容性需求，
把目标版本从 1.21.8 降到 1.21.1。1.21.1 是 1.21.x 系列的早期稳定版本，
拥有最广泛的模组生态兼容性（大量 1.21.x 模组以 1.21.1 为基线），
同时 `WorldRenderEvents`、`HudElementRegistry`、`KeyBinding` 字符串分类
等本项目依赖的 API 在 1.21.1 和 1.21.8 之间完全一致，源代码无需任何改动。

配套修改（只动构建配置，不改 Java 源码逻辑）：
- `gradle.properties`：`minecraft_version` 改为 `1.21.1`，
  `yarn_mappings` 改为 `1.21.1+build.3`（1.21.1 最新 Yarn 构建），
  `loader_version` 改为 `0.15.11`（1.21.1 时代稳定 Loader），
  `fabric_version` 改为 `0.116.15+1.21.1`（1.21.1 最新 Fabric API），
  `modmenu_version` 改为 `11.0.4`（1.21.1 对应的 ModMenu 版本）。
- `build.gradle`：Fabric Loom 从 `1.14.10` 降到 `1.7.3`
  （1.21.1 时代对应的 Loom 版本，2024-07-31 发布）。
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 从 `9.2.0` 降到 `8.8`
  （Loom 1.7.x 要求 Gradle ≥ 8.6，8.8 是 1.21.1 时代经过验证的稳定版本）。
- `fabric.mod.json`：`depends.minecraft` 从 `~1.21.8` 改为 `~1.21.1`，
  `depends.fabricloader` 从 `>=0.16.14` 改为 `>=0.15.11`。
- `MCCFClient.java`：类注释中关于"目标版本固定在 1.21.8"的说明
  改为"目标版本固定在 1.21.1"。

**API 兼容性核对（初次评估，后续本地编译发现 3 处判断有误，已修正）**：
最初评估时认为 1.21.1 与 1.21.8 在本项目用到的 API 上完全一致，源代码无需改动。
本地编译验证后发现实际有 3 处差异，已全部修复（详见下一条"本地编译报告修复的 3 处 API 差异"）：
- `PacketCodecs.BOOLEAN`（1.21.8）→ `PacketCodecs.BOOL`（1.21.1）✅ 已修
- `HudElementRegistry.addLast`（1.21.8 才有）→ `HudRenderCallback.EVENT.register`（1.21.1）✅ 已修
- `RenderTickCounter.getTickProgress(boolean)`（1.21.8）→ `getTickDelta(boolean)`（1.21.1）✅ 已修

其余 API（`Identifier.of()`、`PacketCodecs.STRING`、`WorldRenderContext.tickCounter()`、
`WorldRenderEvents.AFTER_ENTITIES`、`KeyBinding` 字符串分类 + `wasPressed()`、
`ClientReceiveMessageEvents.CHAT` 5 参数签名、`AlwaysSelectedEntryListWidget` 构造器
——后者在 1.21.1 上是 5 参数，1.21.8 扩为 6 参数，也已修复）均核对完毕。

### 2026-07-28　记入注释风格规范 + 新增"已确认规则"章节
- 用户要求参考 Claude 的注释风格，把以下 6 条规则记入项目规则：
  1. 注释写"为什么"不写"是什么"（叙述性长句解释决策动机）。
  2. 把决策历史写进注释（谁反馈的、旧版本错在哪、为什么改成现在这样、
     保留为特性的部分显式声明"不再处理"）。
  3. 类级别声明职责边界（Javadoc 开头明确"只管 X，不管 Y"）。
  4. 对比论证（非显然的 API 选择说明为什么选 A 不选 B，包括 A 的代价）。
  5. 版本兼容性踩坑留在注释里（方便未来升级时少踩坑）。
  6. 诚实承认不确定性（没查清楚的直接写明"索性完全绕开"，不要假装完全理解了）。
- 同时要求 README 维护独立的"已确认规则"章节，和更新日志区分开：
  更新日志是按时间的事件流，规则是稳定的约束。
- **已完成**：
  - 规则同步记入项目记忆文件 `project_memory.md`（供 AI 跨会话记忆）。
  - README 新增"九、已确认规则"章节，包含 9.1（版本号与 README 更新规则）和
    9.2（注释风格规范，含 6 条子规则），并在 9.2.5 汇总了本项目已记入的 4 处
    版本兼容性踩坑（`BOOL`/`getTickDelta`/`HudRenderCallback`/`AlwaysSelectedEntryListWidget`）。
- **版本号**：本次属于工程规范重大变更（影响后续所有代码风格），按 9.1 规则
  升 minor：`0.2.1` → `0.3.0`。

### 2026-07-28　注释修正 + 版本号策略记入项目规则
- `HotbarSubtitleRenderer.java` 类注释里还残留 "由 MCCFClient 通过 `HudElementRegistry.addLast` 注册"
  的旧描述（上一轮 API 适配时漏改），更新为 "通过 `HudRenderCallback.EVENT.register` 注册
  （1.21.1 上用旧 API；1.21.6+ 才有 `HudElementRegistry.addLast`）"。
- 全项目扫描 `TODO/FIXME/XXX/HACK` 标记：无残留。
- 全项目扫描源码中的过时注释：仅上述 1 处，已修复。
- **记入项目规则**：以后每次完成功能或更改后，视情况更新 `gradle.properties` 的 `mod_version`
  并在 README 更新日志写明。版本号策略：patch=bug/注释修复，minor=功能/版本切换/API 适配，
  major=重大重写。
- 本次属于"只改注释"，按规则升 patch：`0.2.0` → `0.2.1`。

### 2026-07-28　本地编译报告修复的 3 处 API 差异
切到 1.21.1 后本地编译验证，发现 3 处 1.21.1 ↔ 1.21.8 之间的 API 差异
（初次评估时误判为"完全一致"，详见上一条的修正说明），全部已修复：

1. `RequestConfigPayload.java`：`PacketCodecs.BOOLEAN` → `PacketCodecs.BOOL`
   （Yarn mappings 在 1.21.1 上 boolean codec 字段名为 `BOOL`，1.21.8 改名 `BOOLEAN`）。
2. `MCCFClient.java` + `HotbarSubtitleRenderer.java`：`HudElementRegistry.addLast(...)`
   → `HudRenderCallback.EVENT.register(...)`。`HudElementRegistry` 是 Fabric API
   后续版本（约 1.21.6+）才引入的新 API，1.21.1 上只有旧的 `HudRenderCallback`。
   方法签名从 `addLast(Identifier, BiConsumer<DrawContext, RenderTickCounter>)`
   改为 `EVENT.register(BiConsumer<DrawContext, RenderTickCounter>)`，
   `HotbarSubtitleRenderer.render` 方法签名不变，兼容新回调。
3. `WorldSubtitleRenderer.java`：`context.tickCounter().getTickProgress(boolean)`
   → `getTickDelta(boolean)`。`RenderTickCounter` 接口在 1.21.1 上方法名是
   `getTickDelta`，1.21.8 改名 `getTickProgress`，签名一致。
4. `ModelSelectionScreen.java`：`AlwaysSelectedEntryListWidget` 构造器从 6 参数
   `(client, w, h, top, bottom, itemHeight)` 改为 5 参数 `(client, w, h, top, bottom)`。
   1.21.1 上没有 `itemHeight` 参数，1.21.8 才加进来；本类的构造器签名保留
   `itemHeight` 参数（API 兼容），只是 `super(...)` 调用不再传它。

**API 签名核实方式**：直接用 PowerShell + `System.IO.Compression.ZipFile`
打开 `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-clientonly-1.21.1-...-v2.jar`
反编译查看 `AlwaysSelectedEntryListWidget` 和 `RenderTickCounter` 的实际方法签名，
再用 `javap -p` 输出完整签名。比查 Fabric 在线文档（不同版本混在一起容易看错）
或问 AI（容易拿新版本答案套旧版本）都更可靠——本地 jar 就是项目实际编译用的
那一份，看到什么签名编译器就认什么签名。

### 2026-07-28　JAR 命名 + 版本号策略
- `gradle.properties`：`archives_base_name` 从 `mccf` 改为 **`MCConversationFramework`**
  （之前 JAR 名是 `mccf-0.1.0.jar`，太简略；现在是 `MCConversationFramework-0.2.0.jar`）。
- `mod_version` 从 `0.1.0` 升到 **`0.2.0`**（minor 版本升级，对应本次
  1.21.8 → 1.21.1 版本切换 + 多处 API 适配修复）。
- **后续版本号策略**：小 bug 修复升 patch（0.2.1、0.2.2...），
  功能变更/版本切换升 minor（0.3.0...），重大重写升 major（1.0.0...）。

### 2026-07-17　包名修复 + 构建配置修复
反馈"所有 `net.minecraft.*` 包都找不到"之后，逐个核对了项目里引用的每一个
Minecraft 类的包路径（对照 Yarn 官方 Maven 文档），修复：

1. `HearingResolver.java`：`ServerPlayerEntity` 误写成
   `net.minecraft.server.level.ServerPlayerEntity`，正确包是
   `net.minecraft.server.network.ServerPlayerEntity`。
2. `HearingResolver.java`：`RaycastContext` 误写成
   `net.minecraft.util.math.RaycastContext`，正确包是
   `net.minecraft.world.RaycastContext`。
3. `build.gradle` 引用了未定义的 `${project.modmenu_version}`，已在
   `gradle.properties` 补上。
4. Loom 插件版本从浮动的 `1.14-SNAPSHOT` 改为锁定的 `1.14.10`。
5. `mappings` 从 `loom.officialMojangMappings()` 改回官方验证最充分的 Yarn。
6. Gradle wrapper 版本一度锁定为 `9.0.0`。
7. 移除了未使用对应插件的 `fabricApi { configureDataGeneration() }` 配置。

### 同日　版本从 1.21.11 降到 1.21.8
实现"贴近说话人显示字幕"功能时发现 Fabric API 的 `WorldRenderEvents` 在
1.21.9/1.21.10 的移植中被官方整体移除（渲染管线重构，无替代方案）。为保留
这个核心体验，把目标版本从 1.21.11 降到 1.21.8（该 API 最后一个正常工作的
版本）。配套修改：

- `gradle.properties`：`minecraft_version`/`yarn_mappings`/`loader_version`/
  `fabric_version` 全部改为 1.21.8 对应版本。
- `MCCFClient.java`：`KeyBinding` 构造函数在 1.21.9 起改用
  `KeyBinding.Category` 对象、按键检测方法改名为 `consumeClick()`；
  1.21.8 上改回旧写法（字符串分类 + `wasPressed()`）。
- `HudElementRegistry`、`WorldRenderEvents.AFTER_ENTITIES`、
  `Entity.getLerpedPos` 等其余客户端渲染 API 在 1.21.8 上签名不变，无需调整。

### 同日　Gradle 版本改回 9.2.0
Gradle 一度从验证过的 9.2.0 改成"官方文档写的 8.14/9.0"，结果实测报错：
`Could not resolve net.fabricmc:fabric-loom:1.14.10`（No matching variant）。
原因是 **Loom 1.14.10 这个精确 patch 版本实际发布时用的 Gradle Plugin API 是
9.2.0**，比"官方支持 8.14/9.0"这个粗粒度声明更严格——不同 patch 版本对
Gradle 的实际要求可能比大版本号声明更新更快。已把
`gradle/wrapper/gradle-wrapper.properties` 改回 **9.2.0**（2025-10-29 发布的
正式版本）。

### 本地编译报告修复的 3 处 bug
1. `gradle.properties`：`modmenu_version` 从 1.21.11 对应的
   `17.0.0-beta.1` 改为 1.21.8 对应的 **15.0.2**（经 Modrinth 核实）。
2. `fabric.mod.json`：`depends` 里的 `minecraft`/`fabricloader` 版本号
   仍是 1.21.11 时代残留值，改为 `~1.21.8` / `>=0.16.14`。
3. `RequestConfigPayload.java`：确认 1.21.8+build.1 实际用的字段名是
   `PacketCodecs.BOOLEAN`（此前记录中"应为 BOOL"的说法有误，以本地实测为准）。

   > **2026-07-28 修正**：这条结论只在 1.21.8 上成立。后续把目标版本切回
   > 1.21.1 时本地编译验证发现，1.21.1 上 Yarn mappings 的字段名仍然是
   > `BOOL`，到 1.21.8 才改名为 `BOOLEAN`。也就是说**当年"应为 BOOL"
   > 那条更早的记录其实是对的**，只是当时在 1.21.8 上验证成了 `BOOLEAN`
   > 就以为是错的——同一段代码在不同 MC 版本上字段名不同。详见
   > "2026-07-28 目标版本从 1.21.8 切换到 1.21.1"一节。

### 新功能：API Endpoint 可配置 + 一键获取模型 + 日志导出 + 多语言
- **API Endpoint 可配置**：除 Mock 外全部 Provider 现在都支持在配置界面里
  自定义 API 基础地址（例如接自建反代/兼容网关），默认值集中在
  `config/ProviderDefaults.java`，配置界面有"恢复默认"按钮一键清空自定义值。
  `ProviderConfig.host` 字段重命名为语义更准确的 `endpoint`。
- **一键获取模型**：配置界面新增"获取模型"按钮（详见"三、配置界面怎么用"）。
- **日志导出**：配置界面新增"导出日志"按钮（详见"三、配置界面怎么用"）。
- **多语言支持**：配置界面所有文字改用 `Text.translatable()`，跟随玩家
  Minecraft 客户端语言设置自动切换，已提供完整简体中文翻译（`zh_cn.json`）。

### 崩溃修复：未连接服务器时调整窗口导致崩溃
根本原因：`MCCFConfigScreen.init()` 无条件调用
`ClientPlayNetworking.send(...)`，但 `init()` 不仅在首次打开 Screen 时执行，
Minecraft 在玩家**调整游戏窗口大小**时也会对所有当前打开的 Screen 重新走一遍
`init()`（原版 resize 流程的一部分）。若玩家尚未进入世界/连接服务器就打开了
配置界面，`send()` 在没有活跃网络连接时调用会抛异常，这个异常发生在 resize
流程内、未被捕获，直接导致客户端崩溃。

修复：改用 Fabric 官方提供的 `ClientPlayNetworking.canSend(payloadId)` 方法
（专门用于判断"当前是否处于游戏中、可以发送该 payload"，未在游戏中时返回
`false` 而不是抛异常），所有会发包的地方（`init()`、`onSave()`、
`onFetchModels()`）都加了这层判断。

### 字幕不显示问题修复
排查后发现 `WorldSubtitleRenderer.java`（VISIBLE 模式，字幕悬浮在说话者上方）
里有一处颜色值 bug：文字颜色用的是 `0xFFFFFF`，在 Minecraft 的 ARGB 颜色格式下，
这个值缺少最高 8 位的 alpha 通道，等价于 `0x00FFFFFF`（alpha=0，完全透明）——
也就是说字幕确实被渲染了，只是渲染成了完全透明、肉眼不可见的状态。已改为
`0xFFFFFFFF`（完全不透明的白色）。`HotbarSubtitleRenderer.java`（物品栏上方
模式）用的是官方 `Colors.WHITE` 常量，颜色值本身没有问题。

（按要求，"字幕显示在人物旁边而非头顶"这个体验层面的调整当时没有做——见
"七、已知限制"最后一条。）

### 2026-07-22　README 整理
第一次通读全部源码后发现 README 存在几处与代码不符/内部矛盾的问题，本轮只动
文档，不改代码：

1. **章节编号混乱修复**：原文件里"四、如何接入其他翻译 Provider"和
   "四、目录结构"共用同一个编号"四"；"六、已知限制"实际排在"七之三"
   之后、"八"之前，顺序被打乱。现在按 一~八 顺序重新排列成
   在你自己电脑上编译 / 功能范围 / 配置界面 / 接入 Provider / 目录结构 /
   配置文件 / 已知限制 / 更新日志。
2. **目录结构过时**：原文档里的目录树是很早期的版本，缺少整个 `config/`
   包（`ProviderConfig`/`ProviderDefaults`）、`command/ConfigSyncHandler`、
   客户端的 `config/`（配置 Screen 相关 5 个类）和 `util/LogExporter`，
   已对照实际源码重写。
3. **Fabric API 版本描述前后矛盾**：文档开头写"目标版本 Fabric API 0.130.0"，
   但"二、编译"章节里却让用户装"Fabric API 0.141.5+1.21.11"——这是版本从
   1.21.11 降到 1.21.8 时残留的旧文字，没有跟着改。已统一为
   `0.130.0+1.21.8`（与 `gradle.properties` 实际值一致），并顺带把
   ModMenu 版本号也标注清楚（15.0.2）。
4. **发现死代码，记录在案**：审查 `network/` 包时发现
   `RequestModelListPayload` / `ModelListResponsePayload` 两个类没有被
   `MCCF.java` 或 `MCCFClient.java` 的任何地方注册或引用，应是"一键获取
   模型"功能早期迭代时的遗留代码。不影响运行，没有主动删除，已在"五、
   目录结构"和"七、已知限制"里注明，等你确认是否需要清理。
5. **接入 Provider 示例更新**：原文档的示例代码写的是
   `translationService.registerProvider(new OpenAiTranslationProvider(apiKey))`，
   但实际项目里 Provider 的创建/注册逻辑已经统一收敛到 `ProviderFactory`，
   原示例不再对应当前架构，已改为对照 `ProviderFactory` + `ProviderDefaults`
   的说法。

### 2026-07-22　新功能：纯客户端模式
按你的要求新增：服务器没装 MCCF 时，自动（也可手动强制）降级为"仅本地翻译
聊天栏文字"，不依赖任何服务端组件。详细使用说明见"三之二、纯客户端模式"。

新增文件（全部在 `src/client`，不涉及服务端代码改动，也没有新增网络包）：
- `client/mode/ClientOnlyModeManager.java`：模式判定（自动检测 + 手动覆盖），
  检测方式是 `ClientPlayNetworking.canSend(RequestConfigPayload.ID)`——复用了
  已有的配置请求通道，不需要额外发一个"探测"包等服务端回应。手动覆盖持久化到
  `config/mccf/client-mode.json`。
- `client/config/ClientOnlyTranslationConfig.java`：玩家自己的本地翻译配置，
  持久化到 `config/mccf/client-only-config.json`，与服务端权威配置完全独立、
  任何人可编辑（复用了已有的 `ClientProviderConfig` 结构，没有重复定义）。
- `client/config/ClientOnlyConfigScreen.java`：对应的配置子界面，从主配置
  界面新增的"纯客户端本地翻译设置"按钮打开。
- `client/chat/ClientOnlyChatTranslator.java`：监听 `ClientReceiveMessageEvents.CHAT`，
  在纯客户端模式下把收到的每条聊天消息异步翻译后追加显示，不替换原文、
  不做任何空间判定。

`MCCFClient.java` 改动：`onInitializeClient()` 里加载模式管理器 + 注册聊天
翻译器；`JOIN`/`DISCONNECT` 事件里分别刷新/重置服务器检测结果。

设计上刻意保持和现有服务端权威配置（op-only）完全分离，没有去改动
`ConfigSyncHandler` 的权限模型——本地翻译配置本来就只影响玩家自己看到的
结果，不需要、也不应该套用"仅 op 可改"这套是为"会影响所有人"的服务端配置
设计的规则。"从服务器同步"按钮复用了已有的 `ConfigSnapshotPayload` 数据
（`ClientConfigState`），没有新增网络包，只拷贝 Provider/模型名/Endpoint
这些公开字段，不拷贝 API Key。

**这一轮里曾有两处 API 用法是凭对 Fabric API 的既有印象写的，没有联网核实
也没有本地编译验证，当时标注为风险点**——这两处（`ClientReceiveMessageEvents.CHAT`
事件签名、`CyclingButtonWidget.setValue(...)`）后续在 1.21.1 上经本地编译
验证均通过，风险已消除，详见"七、已知限制"最后一条。

---

## 九、已确认规则

> 本章节记录与团队/用户对齐过的**稳定规则**，供实现对照，避免后续改动时逻辑漂移。
> 和"八、更新日志"不同：更新日志是按时间的事件流（谁在什么时候改了什么），
> 本章节是稳定的约束（代码必须长期遵循的规范）。新规则记入这里之后，
> 只有在显式声明废弃时才能移除，不能因为某次改动"看起来不需要了"就悄悄省略。

### 9.1 版本号与 README 更新规则
- **触发条件**：每次完成一个功能或更改后，视情况更新项目版本号，并在 README 更新日志写明本次更改。
- **版本号位置**：`gradle.properties` 的 `mod_version` 字段。
- **版本号策略**（语义化版本）：
  - `patch`（x.y.**Z**）：小 bug 修复、注释修正、文档调整等不影响功能/构建的改动。
  - `minor`（x.**Y**.0）：新功能、版本切换（如 MC 版本适配）、API 适配修复、构建配置重大调整、工程规范重大变更。
  - `major`（**X**.0.0）：重大重写、架构变更。
- **README 更新位置**："八、更新日志"章节，按日期倒序新增条目。
- **"视情况"判断标准**：
  - 纯探索性查询、仅读不改 → 不更新版本号，可记可不记 README。
  - 改了源代码/构建配置/资源文件 → **必须**更新版本号 + 写 README 更新日志。
  - 只改了 README/注释 → 可只升 patch 并在 README 记一笔，也可不升版本号只记日志。
- **记入时间**：2026-07-28。

### 9.2 注释风格规范
本节的规则适用于**所有新写的代码注释和 Javadoc**，改老代码时如果碰到不符合规范的注释
也应该顺手修正。参考 Claude 的注释风格，核心原则：**注释的价值在于解释"为什么"，
而不是重复"是什么"**——代码本身已经说明了"是什么"。

#### 9.2.1 注释写"为什么"，不写"是什么"
- 用**叙述性长句**解释决策动机，而不是干巴巴描述代码行为。
- 反例：`// 重置血量`
- 正例：`// 玩家重生后必须显式 setHealth(20.0) 重置血量，因为死亡时残留的"当前血量"
  会被 RespawnScreen 复用到新角色身上；不能用 heal() 或治疗指令，那会触发 Regen
  效果的事件链，和"重生瞬间满血"的语义不符；这一步必须在 teleport(toSpawn)
  之前调用，否则复活点坐标更新后 PlayerEvent.Respawn 已经触发，血量会被
  respawn 逻辑二次覆盖。`

#### 9.2.2 把决策历史写进注释
- 遇到曾经改过的逻辑，注释里保留**决策脉络**：谁反馈的、旧版本错在哪、为什么改成现在这样。
- 团队决定**保留为特性**的部分（不是 bug），要显式声明
  "此行为经讨论后保留，不再处理"，防止后续接手的人当成 bug 再"修"一遍。
- 目的：让后人能还原决策过程，而不只是看到当前状态。

#### 9.2.3 类级别声明职责边界
- 每个**类的 Javadoc 开头**必须明确写："这个类只管 X，不管 Y（那是 Z 类的职责）"。
- 目的：防止越界改动。如果某个类的方法开始调用它"不该管"的东西，评审时一眼能看出来。
- 模板：
  ```java
  /**
   * <职责的一句话描述>
   *
   * 职责边界：
   * - 只管：X、Y
   * - 不管：A（那是 B 类的职责）、C（那是 D 类的职责）
   */
  ```

#### 9.2.4 对比论证：非显然的 API 选择必须说明
- 遇到非显然的 API 选择（比如 `setHealth` vs `damage()`、`teleport` vs 手动构造
  `TeleportTarget`），在注释里说明**为什么选 A 不选 B**，包括 A 的代价。
- 模板：`// 这里用 setHealth 而不是 damage()：damage 会触发 LivingEntityDamage 事件，
  被护甲/药水/模组拦截后扣血量不可控；setHealth 是直接写入，代价是绕过了所有
  "伤害处理"逻辑（包括死亡判断），所以调用前必须手动检查 currentHealth > 0。`
- 目的：避免后人"优化"代码时把 A 换成 B，触发当初已经踩过的坑。

#### 9.2.5 版本兼容性踩坑留在注释里
- 任何因为 MC / Fabric / Yarn 版本差异踩过的坑，都要在**对应代码位置**留注释。
- 格式：`// 1.21.1 的 DynamicRegistryManager 用 get() 而不是 getOrThrow()
  （后者是 1.21.5+ 才改的名字）。升级到 1.21.5+ 时这里要同步改名。`
- 已记入的版本踩坑（供查阅，代码位置也有对应注释）：
  - `PacketCodecs.BOOL`（1.21.1）↔ `PacketCodecs.BOOLEAN`（1.21.8+）——`RequestConfigPayload.java`
  - `RenderTickCounter.getTickDelta(boolean)`（1.21.1）↔ `getTickProgress(boolean)`（1.21.8+）——`WorldSubtitleRenderer.java`
  - `HudRenderCallback.EVENT.register`（1.21.1）↔ `HudElementRegistry.addLast`（1.21.6+）——`MCCFClient.java` / `HotbarSubtitleRenderer.java`
  - `AlwaysSelectedEntryListWidget` 构造器 5 参数（1.21.1）↔ 6 参数含 `itemHeight`（1.21.8+）——`ModelSelectionScreen.java`
  - `TextFieldWidget.setRenderTextProvider(BiFunction)`（1.21.1）替代已移除的 `setRenderPasswordReveal(boolean)`（1.20.x 及之前）——`MCCFConfigScreen.java` / `ClientOnlyConfigScreen.java`。1.21.1 没有"一行开启密码框"的开关，需自己传一个把字符替换成圆点的 `BiFunction`。
  - Java 源码注释中**不要写 `\uXXXX`**：Java 编译器在词法分析阶段（早于注释识别）就会处理 Unicode 转义，注释里的 `\uXXXX` 若 `X` 不是合法十六进制会报"非法的 Unicode 逃逸"。写 Unicode 码点请用 `U+XXXX` 形式——`HttpProviderSupport.java`。

#### 9.2.6 诚实承认不确定性
- 遇到没查清楚的底层行为，**直接写明**"没查清楚""索性完全绕开"，不要假装完全理解了。
- 模板：`// 清零 timeUntilRegen 仍不足以保证扣血生效，具体是哪个环节吞掉的没有查清楚
  （疑似 LivingEntity.damage 里有额外的 invulnerability 检查），索性完全绕开：
  用 setHealth 直接写入，不依赖 damage 管线。`
- 目的：让后人知道"这里还有未解之谜，不要盲目重构"，而不是误以为代码已经完全验证过。
