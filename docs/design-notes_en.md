# MCCF Design Notes

> **Document purpose**: Decision analysis extracted from the README changelog, recording the "why" behind each version's changes — root cause analysis, design rationale, trade-off discussions, and known limitation arguments. The README changelog only retains "what changed" as pure version updates; the decision context is concentrated in this document.
> **[中文版本](design-notes.md)** | English (current)

---

## 1.1.4　Fix onSnapshotUpdated resetting selectedProvider + self-talk subtitle no translation + history show-translation toggle + title styling

**Root cause - onSnapshotUpdated resetting selectedProvider**: 1.1.3's init() rebuild preserving selectedProvider fix (via `preservedSelectedProvider`) only solved half the problem. The real root cause is `ServerConfigPanel` constructor calling `requestSnapshot()` to send a config request; after the new panel is created, the server replies with ConfigSnapshotPayload triggering `onSnapshotUpdated()`, where `selectedProvider = state.activeProvider` resets the preserved selection.

Full timeline: player selects B → clicks Fetch Models → ModelSelectionScreen → clicks Cancel → init() rebuild (preserves B ✓) → new panel constructor sends requestSnapshot() → server replies with snapshot → onSnapshotUpdated() → **selectedProvider = activeProvider = A** ← lost here.

**Why removing the reset line in onSnapshotUpdated is safe**:
- First open: selectedProvider initialized via `initialSelectedProvider() = activeProvider`, no need to set here
- init() rebuild: selectedProvider preserved via `preservedSelectedProvider`, no need to override here
- After save: onSave sets selectedProvider as pendingActiveProvider, after save activeProvider = selectedProvider, no need to reset here
- Another admin changes activeProvider: player keeps current viewed Provider without interruption — better behavior

The only behavior change is "selection doesn't follow after admin changes config" — but not interrupting the player's current view is reasonable.

**Why self-talk subtitle doesn't show translation line**: Self-talk has `originalText == translatedText` (server doesn't translate self-talk, sourceLang == targetLang). With showOriginal=true it shows "Name: original" + "⇄ translation" — but translation is the same as original, the "⇄ translation" line implies "what I said needs translation". User feedback: this is weird, obviously I understand what I said. Fix: HotbarSubtitleRenderer checks `speakerId == client.player.uuid`, isSelf shows only "Name: original" line.

**Why title uses scale 1.3x instead of larger itemHeight**: 1.21.1's `AlwaysSelectedEntryListWidget` itemHeight is list-level, doesn't support per-entry custom height (introduced in 1.21.8). Using `context.getMatrices().scale(1.3f, 1.3f, 1.0f)` in render to scale text is the only way without breaking list layout. After scale, coordinates must be divided by scale factor to land correctly.

**Why GroupTitleWidget and SystemEventWidget mouseClicked returns false**: AlwaysSelectedEntryListWidget entries are clickable by default. GroupTitleWidget is a group title, SystemEventWidget is "A new conversation started" type narration — they're pure display elements, selecting them is meaningless. Overriding mouseClicked to return false lets clicks pass through, won't be selected.

---

## 1.1.3　Fix switching Provider losing changes + disable-thinking "yes" not working + command localization

**Root cause - two bugs, same root cause**: User reported "switching Provider then fetching model list, Provider reverts to DeepSeek losing changes" and "disable-thinking warning screen clicking 'yes' still shows off". Superficially two bugs, same root cause: `MCCFConfigScreen.init()` is triggered to rebuild after `ModelSelectionScreen`/`ConfirmScreen` close, `new ServerConfigPanel(...)` creates a fresh instance, and the new instance's `selectedProvider` reads `state.activeProvider` via `initialSelectedProvider()`, losing the `selectedProvider` the player temporarily switched to in the old panel.

**Why ModelSelectionScreen and ConfirmScreen closing triggers init rebuild**: Both Screens return to MCCFConfigScreen via `setScreen(parentScreen)`. Minecraft's `setScreen` calls the target Screen's `init()` to reinitialize (window size may change, child widgets need rebuilding), so every setScreen back to MCCFConfigScreen triggers a full panel rebuild.

**Why 1.1.1's disable-thinking fix looked correct but didn't work**: 1.1.1 placed `state.getOrCreate(selectedProvider).disableThinking = true` before `setScreen`, with comment "must update state before setScreen, otherwise init rebuild reads old value". This analysis was half right — state was correctly updated. But missed: **new panel's `selectedProvider` ≠ old panel's `selectedProvider`**. New panel reads `state.activeProvider`; if player selected a non-activeProvider (e.g., activeProvider is mock, player selected DeepSeek), new panel shows mock's disableThinking (false), not DeepSeek's (true). Player sees "still off", thinks 1.1.1 fix didn't work.

**Why setModelFromSelection also needed fixing**: `onModelsResult` creates `ModelSelectionScreen` passing `this::setModelFromSelection` as callback. After ModelSelectionScreen closes triggering init rebuild, old panel's `this` reference still exists (lambda capture), but old panel's `modelField` is replaced by new panel. Old `setModelFromSelection` code `state.getOrCreate(selectedProvider).model = model` uses old panel's selectedProvider — although 1.1.3's preservedSelectedProvider fix makes old and new panel's selectedProvider consistent, using method parameter providerId is more explicit, not depending on panel instance state. `modelField.setText(model)` retained but with null check — after rebuild, old panel's modelField is discarded, new panel's modelField reads latest value from state via `refreshFieldsFromState`.

**Fix approach - why Screen-level preservation instead of ClientConfigState persistence**:
- Screen-level preservation (Option A): `MCCFConfigScreen` adds fields, reads old panel's `selectedProvider` before init rebuild, passes to new panel via `setPreservedSelectedProvider()`. Only valid within Screen lifecycle, resets to activeProvider on close-reopen.
- ClientConfigState persistence (Option B): state adds `lastViewedProvider` field, `initialSelectedProvider()` reads it, preserved across Screen close-reopen.

Option A chosen because: players closing and reopening config UI resetting selectedProvider to activeProvider is reasonable (new session), only need to preserve within Screen lifecycle. Option B pollutes state, and "last viewed Provider" is essentially UI temporary state, not configuration data.

**Command localization**: `/mccf status` and `/mccf stats reset` output was English hardcoded `Text.literal(...)`, changed to `Text.translatable()` in 1.1.3. Note `successRate` uses `String.format("%.1f", successRate)` to pre-format as string before passing to translatable — because `Text.translatable`'s `%s` placeholder doesn't support `%f` float formatting, must convert to string first.

**.codebuddy/memory exclusion**: AI working memory files were mistakenly committed to 1.1.2 commit, 1.1.3 soft-deletes (git rm --cached + .gitignore). Hard delete (filter-repo rewrite history to thoroughly clean) deferred to unified execution later, avoiding multiple force pushes.

---

## 1.1.2　QA report batch fix: Zhipu unregistered + network packet security + translation failure awareness + multiple UX improvements

**Root cause - Zhipu not registered**: When Zhipu Provider was added in 0.15.0, `ProviderDefaults` / `ProviderFactory` / `MCCFConfig.defaultProviderConfigs()` / `ClientConfigState.PROVIDER_IDS` all correctly added zhipu, but `MCCF.registerAllProviders()` line 222 `List.of("openai", "claude", "gemini", "deepl", "kimi", "deepseek", "ollama")` was missing it. This caused:
- Players **could see and select** Zhipu AI in the config UI (because ClientConfigState.PROVIDER_IDS contains zhipu)
- Players **could fill in API Key and save** (because config.providers generates a config entry for zhipu by default)
- But `setActiveProvider("zhipu")` could not find this Provider in TranslationService, **silently falling back to Mock**
- The log only had one warn line: "Configured provider 'zhipu' not found, falling back to mock" — admins seeing it would think "config is wrong", not "code missed a line"

This kind of "looks configured but actually doesn't work" bug is worse than having no feature at all, because players would repeatedly modify the config thinking they made a mistake. The root cause is that Provider registration is scattered across two places (runtime `registerAllProviders()` and UI `ClientConfigState.PROVIDER_IDS`) without a single source of truth constraint.

**Why List.of literal instead of deriving from ProviderDefaults**: The ideal solution would be `for (String id : ProviderDefaults.all().keySet())` to automatically iterate all defined Providers, so adding a new Provider only requires one line in ProviderDefaults. But keeping the List.of literal has two considerations: (1) explicit listing makes review easier — you can see at a glance which Providers are registered; (2) iteration order of ProviderDefaults.all() under Map is unstable, which may affect the implicit convention of "Mock registered first for fallback". If Providers are still missed in the future, the derived approach should be considered.

**Root cause - missing network packet length validation**: `PacketCodecs.STRING` does not limit string length at the Minecraft protocol layer — it's up to the caller to validate. `SubtitlePayload` and `UpdateConfigPayload` added validation early (`MAX_TEXT_LENGTH = 4096` / `MAX_JSON_LENGTH = 65536`), but `RequestModelsPayload` / `LanguageReportPayload` / `ConversationRosterPayload` decoder all missed it. This is a typical "early Payloads got validation, later ones forgot" omission — the team lacked a unified rule that "new Payloads must add length validation".

**Why RequestModelsPayload's malicious packet risk is more severe**: This Payload carries apiKey + endpoint + providerId. A malicious client can construct a multi-MB JSON string transmitted via `PacketCodecs.STRING`. The server's `ConfigSyncHandler.handleModelsRequest` parses it with Gson, consuming large CPU and memory. **Key point**: Payload deserialization happens **before** op permission check — meaning malicious clients don't even need op permission to trigger server memory allocation. This is a real security vulnerability, not just an admin spam experience issue.

**Why ConversationRosterPayload decoder validation must be before ArrayList construction**: Old code `int idCount = buf.readVarInt(); List<UUID> ids = new ArrayList<>(idCount);` passes idCount directly to ArrayList constructor for capacity pre-allocation. `new ArrayList<>(Integer.MAX_VALUE)` attempts to pre-allocate ~16GB memory, OOM-ing even if elements are never written. Validation must complete **before** ArrayList construction, otherwise the validation itself is too late. The constructor's `MAX_PARTICIPANTS` check (line 50) exists, but it throws `IllegalArgumentException` after ArrayList has been constructed — OOM already happened.

**Why translation failure uses counting instead of directly telling players**: TranslationService.translate()'s exceptionally converts failures to source-text fallback returns, so callers always see successful futures — this is the design for "players at least see the original text instead of completely missing the message when translation fails". The cost is that callers cannot distinguish "translation succeeded and equals source" from "translation failed and fell back to source".

A complete fix would require a `TranslationResult(text, success)` wrapper type, but that changes all TranslationService caller signatures — too broad. This version takes a compromise:
1. Atomic counters let `/mccf status` show statistics — admins can perceive failure trends
2. Client path (ClientOnlyChatTranslator) calls `provider.translate()` directly bypassing TranslationService, so exceptionally still triggers — add deduplicated player notification

Server path players still cannot directly distinguish translated-equals-source from failure, but admins can check `/mccf status`. This is a three-tier solution: "admins perceive + client players perceive + server players rely on admin investigation".

**Why ClientOnlyChatTranslator failure notification needs 60-second deduplication**: If every message fails during chat spam (e.g., API Key expired), notifying the player for each one would flood the chat bar — worse than silent failure. The dedup strategy uses the reason string as key, notifying only once per 60 seconds for the same reason. When switching Provider / Key changes the failure reason, the new reason notifies immediately — letting players perceive "the current failure is a new problem".

**Why ClientOnlyTranslationConfig.save() invalidates Provider cache**: Old `ClientOnlyChatTranslator.getProvider()` cached Provider instances by providerId and never invalidated — players had to switch Provider and back to refresh after changing API Key. This limitation was too obscure; players would think the Key wasn't saved.

The simplest invalidation point is `ClientOnlyTranslationConfig.save()` — the save path is singular (player clicks Save in config UI), and invalidating the cache after successful file write ensures "save = refresh". Why not compare fields every time in getProvider: real-time config change detection requires ProviderConfig to support "field change notifications" or field-by-field diff in getProvider — not worth the complexity.

**Why RateLimiter upgraded from fixed window to sliding window**: The classic fixed-window problem is boundary spikes — under 5 req/sec limit, 5 at window end + 5 at new window start = 10 req/sec spike. For anomalous behavior like chat spam, the spike itself has limited impact; but for paid APIs like OpenAI / DeepL, a 2x spike can directly trigger upstream rate limits and temporarily ban the API Key — much worse than "occasional translation failure".

Sliding window uses ArrayDeque to track timestamps of admitted requests, cleaning expired timestamps before each tryAcquire and checking queue length. Complexity increases only ~10 lines, but precision improves from "window-level" to "any continuous window-level". Rejected requests don't enter the queue — avoiding the semantic confusion of old version's "rejected also incrementAndGet".

**Why first-join tip changed from in-memory static to persisted**: Old `tipped` was a static boolean, resetting on process restart — veteran players would be spammed by the same tip on every restart. Persisting to `client-mode.json`'s `tippedFirstJoin` field means veterans won't be prompted after restart. Upgrading to 1.1.2 will prompt **one more time** — intentional, to let upgrading users perceive the "tip now mentions ModMenu entry" improvement, then no more repeats.

**Why the tip text now includes ModMenu entry**: Old text only said "go to key binds", but default key is unbound (`InputUtil.UNKNOWN_KEY`) — players must first bind a key then come back, too steep for newcomers. ModMenu is a more intuitive entry (click gear icon in Mods list), and all 9 language texts were updated simultaneously.

---

## 1.1.1　Fix disable-thinking toggle + show-original-text changed to client-side personal preference

**Bug 1: Root cause of disable-thinking toggle showing "off" after clicking "yes"**:
The `ConfirmScreen` callback had the code order wrong — it called
`setScreen(screen)` to return to the config screen first, then updated
`disableThinking = true`. `setScreen` triggers `MCCFConfigScreen.init()` which
rebuilds all panel widgets, and during rebuild `refreshFieldsFromState` reads
`disableThinking` from state to set the button display. Calling setScreen before
updating state means init rebuild reads the old value false, so the button shows
"off"; by the time state is updated to true, the button is already drawn and
won't refresh. Fix: move the state update before `setScreen`. `LocalConfigPanel`
had the exact same bug.

**Why the cancel branch doesn't need `button.setValue(false)`**: init rebuild
creates a brand new button widget; the `button` captured in the lambda is the old
widget, operating on it is meaningless; the new button will correctly read from
state (still false) and display "off".

**Bug 2: Decision to change show-original-text from server op config to client-side
personal preference**: Since 0.16.2, these two toggles were set to greyed-out
non-selectable (`active=false`), only modifiable by editing `config.json`, and
were server-side op config — all players shared the same value. User feedback
indicated this didn't meet the requirement: each player should be able to
independently decide whether to see the original text, without server/op
restrictions. 1.1.1 migrated `showOriginalText` / `showOriginalTextInChat` from
`MCCFConfig` (server config) to `ClientOnlyTranslationConfig` (client-side
personal preference, stored in `client-only-config.json`).

**Why the toggles stay in the "Server Config" tab**: Per user's explicit request.
Although these toggles are semantically already client-side preferences, they
belong to the same group of "translation display settings" as Provider config,
and keeping them in their original position better matches the user's usage
habits. The toggle's `active` only depends on `tabVisible` (not `canEdit`), so
all players can toggle them, and changes take effect immediately and persist
without needing to click "Save".

**Root cause of AUDIBLE subtitles not showing original text**:
`HotbarSubtitleRenderer.render()` only concatenated `translatedText()` and
completely ignored `originalText()` — the server correctly sent the original
text, `SubtitleManager` correctly stored it, but the renderer ignored it. 1.1.1
fix: during rendering, decide whether to draw an additional line of original
text based on `ClientOnlyTranslationConfig.showOriginalText` (format consistent
with VISIBLE chat: `<name> original` + `⇄ translation`).

**Design of server always sending original text**: `SpatialChatHandler.dispatchTo`
no longer decides whether to fill `originalText` based on
`config.showOriginalText` / `config.showOriginalTextInChat`; instead, it always
fills in the original text. Whether to display it is entirely up to the client —
this way different players can have different display preferences, and the server
doesn't need to judge separately for each listener.

---

## 1.1.0　Release workflow changed to fully manual trigger + GitHub Release restored to attach jar and sources jar

**Why changed to fully manual trigger**: Releasing is an explicit action of "confirming this version is ready to ship," and should be executed by a person manually clicking "Run workflow" in the GitHub Actions interface, to avoid accidental triggers producing meaningless Releases or accidentally triggering compile validation while code is half-modified. Both triggers — push to main only doing compile validation, and push tag auto-publishing — were removed.

**Why release.py was deleted**: After the workflow was changed to manual trigger, the version number and tag are both read by the workflow from gradle.properties and created automatically (specified by the `tag_name` parameter of softprops/action-gh-release, which auto-creates the tag on the current commit if it doesn't exist), so a local tag-scripting script is no longer needed. The release flow was simplified from "run a script locally to create the tag" to "go to the GitHub Actions web page and click Run workflow."

**Why jar attachment was restored**: Starting from 0.16.4, it had been changed to "GitHub Release contains only source code, jars uniformly go through Modrinth." 1.1.0 changed it back so that GitHub Release also carries jar + sources jar, making multi-channel downloads more convenient for players. The sources jar comes from the existing `withSourcesJar()` configuration in build.gradle, for developers to view source code / debug decompiled code in their IDE. The build job added an existence check for the sources jar, which can expose problems in the edge case of "compilation appears to succeed but the artifact wasn't produced."

---

## 1.0.0　First official release

**Rationale for jumping the version number from 0.x.x to 1.0.0**: Per semantic versioning rules, 1.0.0 means "the first public stable release," while the 0.x.x phase indicates "still in early development, may change at any time." This project has gone through iterations from 0.3.0 to 0.16.4, and the core features (spatialized translation, Conversation context, pluggable Providers, pure client-side mode, chat history, 9-language localization) are already stable and qualify for a 1.0.0 release.

**The lesson of the missing field in reload**: `showOriginalTextInChat` was a field added in 0.12.0, and at the time reload missed this line, causing `/mccf reload` to be unable to reload this config item — administrators who finished editing `config.json` and ran reload found it had no effect and had to restart the server. This violated the contract of the reload command ("complete reload"). Lesson: when adding new config fields, you must synchronously update reload's copy list, otherwise a subtle "changed but doesn't take effect" bug will appear.

**Background of dead code cleanup**: `RequestModelListPayload` and `ModelListResponsePayload` were dead code left over from the early iterations of the "get models" feature (later switched to the `RequestModelsPayload`/`ModelsResultPayload` path), never registered or used by `MCCF.java` / `MCCFClient.java`. `ModelListResponsePayload` also internally carried 70 lines of hand-written JSON parsing utility methods, all dead code.

**Argument for the scan conclusion**: Before release, a full-project scan was performed (dead code / TODO markers / exception handling / config consistency / language file completeness / network packet completeness), and everything passed except for the fixes mentioned above — no TODO/FIXME/XXX/HACK markers remained; no empty catch blocks, no swallowed exceptions, all futures had `.exceptionally` handling; the 9 language files had completely identical keys (102 keys each); exception handling was thorough, with all failure paths logged; known limitations were all reasonable feature boundaries (STT not implemented, subtitle duration not configurable, etc.), not defects; all hardcoded values had detailed comments arguing for them (cache TTL, rate limit thresholds, ring buffer capacity, etc.).

---

## 0.16.4　GitHub Release changed to source-only Release + new one-click release script

**Why GitHub Release was changed to source-only**: Per user request. This way the GitHub Release is purely a version record + changelog, and won't have the "both sides out of sync" problem with the jar on Modrinth. Old Releases from earlier versions (0.8.0~0.16.3) still contain historical version jars, but new versions no longer do this.

**Design considerations for release.py**: It does not auto-bump the version number, does not auto-commit — releasing is an explicit action of "confirming this version is ready to ship," and the version number is manually maintained by the author during development per the 9.1 rules. Pre-checks: clean working tree (to prevent releasing while code is half-modified), tag doesn't exist (to prevent duplicate releases), current branch has been pushed to remote. Supports `--check` dry-run mode.

---

## 0.16.3　Config screen input field placeholder text exceeds input field width

**Root cause analysis**: The width of `apiKeyField` is `panelWidth - 44` (44px narrower than the model/endpoint input fields, to leave room for clearApiKeyButton), but its placeholder is the longest. The length differences across language versions are huge — Chinese is about 18 characters, English about 36 characters, while German/French/Spanish/Russian versions are as long as 50+ characters, which will inevitably exceed the field on narrow screens or small GUI scales. `TextFieldWidget` on 1.21.1 does not perform `trimToWidth` truncation when rendering placeholders, and the text is drawn directly outside the input field. The endpoint's placeholder has the same problem in German/French and other languages.

**Why simplified**: Removed the lengthy "leave blank to keep current value unchanged" explanation, keeping only the core hint. The "leave blank to keep current value" behavior is intuitive to users, and `clearApiKeyButton` already distinguishes between "save with blank" and "actively clear" operations, so there's no need to repeat the explanation in the placeholder.

---

## 0.16.2　Config screen "show original text" toggle layout + greyed-out non-selectable + force-disable thinking button displayed twice

**Misjudgment process**: In the first round, it was thought that a spacing formula miscalculation caused controls to crowd together, with overlapping text mistaken for two buttons (changed 140→160). After the user explicitly pointed out "I'm not joking, that really is the force-disable thinking button," the true root cause was located — not spacing, but actually two `disableThinkingButton`s displaying simultaneously.

**True root cause**: `ServerConfigPanel` and `LocalConfigPanel` use the same `left/top/right/bottom` coordinates, and each one's `disableThinkingButton` position completely overlaps. In `refreshFieldsFromState`, `disableThinkingButton.visible = supportsThinking` unconditionally sets visible, overriding the invisible state set by `setVisible(false)`. When the inactive tab's Provider supports thinking, its `disableThinkingButton` will incorrectly display, overlapping with the active tab's button at the same position. `LocalConfigPanel.onTabVisibilityChanged` calls `refreshFieldsFromState`, so when switching to the "Server Config" tab, the Local button leaks out; the two Panels' `disableThinking` values may differ, and the player sees two buttons — "off above, on below."

---

## 0.16.1　Removing the "pinned to 1.21.1" version rationale argument

**Why the version rationale argument was removed**: After 0.16.0 removed world-space subtitles (WorldRenderEvents), the biggest technical obstacle to upgrading to a higher Minecraft version had been eliminated, and the argument originally made in the README for "why pinned to 1.21.1" (1.21.x ecosystem compatibility, version compatibility concerns about WorldRenderEvents being removed in 1.21.9+, etc.) no longer needs to be retained as player-facing documentation.

**Rationale for streamlining gradle.properties comments**: Streamlined from "target version pinned to 1.21.1 + lengthy rationale" to "1.21.1 serves only as the current release baseline, not as a long-term lock," truthfully reflecting the current state — the upgrade obstacle has been eliminated, and the project can follow new versions more freely in the future.

---

## 0.16.0　Removing world-space subtitles + AI context changed to complete conversation groups

**Why world-space subtitles were removed**: The `WorldSubtitleRenderer` code existed but never displayed in testing, the root cause was never identified, and it was no longer retained as a current-version feature. This project no longer depends on `WorldRenderEvents` — and the version compatibility concern about that API being removed in 1.21.9+ due to rendering pipeline restructuring was thereby eliminated.

**Decision history of VISIBLE going through the chat bar being formalized**: Early versions (0.3.0~0.4.0) tried to use world-space rendering to draw subtitles next to the speaker model, but the root cause was never identified and testing showed nothing. From 0.4.0, VISIBLE was temporarily switched to use the vanilla chat bar as a workaround, and 0.16.0 formally confirmed this workaround as **formalized** — VISIBLE going through the chat bar became official behavior rather than a temporary downgrade. HearingResolver still distinguishes between the VISIBLE/AUDIBLE tiers (distance + line-of-sight judgment), only the display carrier for VISIBLE changed from "world-space floating subtitle" to "vanilla chat bar" — the semantics of "nearby speech goes to the chat box, distant shouting goes to hotbar subtitles" remain unchanged.

**Why the `subtitleVisibleRange` field was retained without renaming**: It carries the responsibility of the distance threshold for HearingResolver's "visible / audible" tiers, and is unrelated to the display carrier; renaming would break backward compatibility with old config.json. The field comment has been updated to explain this.

**Trade-off of changing AI context to complete conversation groups**: After completely removing context truncation, the prompt for very long conversations (such as discussions that go on for dozens of minutes without stopping) will grow longer and token consumption will increase, potentially exceeding some Provider models' context windows and causing requests to fail. **This is a trade-off the user knowingly accepted** (the user explicitly chose "completely remove truncation" over a soft-cap scheme in this requirement). No soft-cap fallback was added — if future testing reveals this problem, truncation can be added separately at the Provider layer without affecting the generic logic of Conversation / ChatCompletionsSupport.

---

## 0.15.0　Five Providers get independent "force-disable thinking" toggles + new Zhipu AI Provider

**Research conclusions (current state of each vendor's "thinking mode," verified 2026-08)**:
- **DeepSeek**: The V4 series (default `deepseek-v4-flash`/`deepseek-v4-pro`; the official deprecated the old `deepseek-chat`/`deepseek-reasoner` on 2026-07-24) has thinking enabled by default and supports `"thinking":{"type":"disabled"}` to turn it off.
- **Kimi**: The default model `kimi-k2.5` (K2.x series) has thinking enabled by default and supports the same `thinking:{type:disabled}` parameter to turn it off; however, **the K3 series official documentation explicitly states "Reasoning is always on. There is no non-thinking mode."** — if a player manually changes the model to K3, this toggle's parameter won't error but also won't take effect.
- **Claude**: The default model `claude-sonnet-4-6` supports the legacy `thinking:{type:"disabled"}` parameter ("extended thinking is deprecated on the Claude 4.6 models, requests using it still succeed"); however, **Claude 4.7 and newer models no longer support this parameter structure and will return a 400 error**, with new models switching to the `effort` parameter to control thinking intensity, which cannot be simply "disabled."
- **Gemini**: The default model `gemini-3.5-flash` supports `generationConfig.thinkingConfig.thinkingBudget:0` to turn off thinking; however, **Gemini 2.5 Pro / Gemini 3 Pro official documentation explicitly states that thinking cannot be turned off** ("Thinking can't be turned off").
- **Zhipu GLM**: GLM-5/GLM-5.2 official sample code confirms support for `thinking:{type:"disabled"}` (same parameter structure as DeepSeek/Kimi).
- **None of the vendors' `listModels()` interfaces return information about "whether this model supports disabling thinking"** — so it's impossible to use a networked query to automatically determine whether a specific model actually supports it; you can only give a generic warning when the toggle is turned on and leave it to the player to verify.
- OpenAI's default model `gpt-4o-mini` is not a reasoning model and has no concept of "thinking" itself, so it's out of scope for this round of handling.

**Why each Provider gets an independent toggle**: Per the user's explicit request for fine-grained control like "I only want to turn off DeepSeek's thinking, not Kimi's" — not a single global toggle affecting all Providers.

**Design of the confirmation warning when the toggle is turned on**: The toggle is off by default and can be freely turned on (not "greyed-out and disabled") — the scheme ultimately confirmed by the user. When turned on (false → true), a `ConfirmScreen` pops up a warning: explaining that some next-generation models may not support force-disabling thinking, which may cause that Provider's translation requests to fail entirely, while also explaining the benefits of disabling thinking (faster translation, fewer tokens). Turning off (true → false) requires no confirmation and takes effect immediately — turning off a "potentially risky setting" is always a safe operation.

**Known limitations**: Determining "whether a specific model supports disabling thinking" relies entirely on the player's own verification — no official API provides this information, and the config screen cannot achieve smart hints like "automatically greying out the toggle when an unsupported model is selected." This is a common limitation of the current AI service provider ecosystem, not a technical problem this mod can bypass. Parameter names and support ranges may change as each vendor's API version updates (this research is current as of 2026-08-01); if a vendor later adjusts its parameter structure, the research will need to be redone and the corresponding Provider implementation class updated.

---

## 0.14.0　Chat history supports filtering and sorting

**Why the filter granularity is "by conversation group" rather than "by individual message"**: As long as any one message in a conversation group satisfies the conditions across all three dimensions, the entire group is retained and displayed (including all messages and system prompts within the group), rather than only hiding individual messages within the group that don't satisfy the conditions — that would make it hard to understand why a particular message suddenly disappeared. Grouping by conversation fits the "reading conversations" use case better than "reading fragmented messages." Participant filtering always fails for groups without a server-side participant list (CLIENT_ONLY messages with no attribution), because such messages have no concept of "conversation participants."

**Why the keyword input field isn't "apply immediately on change"**: Rebuilding the list on every keystroke while typing would be very laggy, and the list would jump around while you're only halfway through typing. It was changed to apply the filter only when focus is lost (clicking elsewhere / collapsing the panel) or when Enter is pressed.

**Analysis of the `rebuildList()` bug fix**: Every filter/sort change creates a new `HistoryListWidget` instance, and the old instance was previously not removed from the Screen's child widget collection — this would leave a stale list in place after each interaction, not only wasting memory, but the old list's scissor region and input response would overlap with the new list, causing click, scroll, and other interactions to go wrong. The fix explicitly removes the old instance by calling `remove(listWidget)` before rebuilding.

---

## 0.13.1　Removing CurseForge-related references + fixing one stale hardcoded version number

**Why historical decision records are not retroactively modified**: Previously (see the 0.8.0 record), when writing the workflow, the README still mentioned "the project will be published to GitHub and uploaded to CurseForge / Modrinth," as well as "auto-upload to CurseForge / Modrinth was not added to the workflow this time" — these two are authentic decision records from that time and are historical facts. This round does not retroactively modify them; it only removes CurseForge from "operation guides for current users" like the download and installation instructions, to avoid the confusion of users going to CurseForge to look for something that was never published there.

---

## 0.13.0　Moving the hover description from the top title to the left-side list itself

**Why the hover trigger area was moved**: In 0.12.0, after the Provider description was changed to a hover tooltip, the trigger area was only the top "currently selected for viewing" title. When players wanted to quickly compare the descriptions of several Providers, they had to first click to select each one to see the corresponding description, which was not convenient enough. This time the hover trigger area was moved from the top title to the left-side Provider list itself — hovering over any item in the list (without needing to first click it) shows that item's description, effectively giving a preview of the entire list.

**Why the tooltip must be drawn after scissor is released**: It must be drawn after `disableScissor()`, otherwise if the tooltip content is wider than the list itself, the parts extending beyond the right/bottom will be clipped by the list's scissor region (scissor only clips drawing calls within the list box; the tooltip needs to be able to draw outside the list box).

---

## 0.12.0　Hint area changed to hover tooltip + chat history reuses server-side Conversation grouping + chat bar optionally shows original text

**Root cause of the hint area changing to a hover tooltip**: `BOTTOM_HINT_AREA_HEIGHT` previously reserved a fixed 100px for the "Provider description + status messages" persistent text, but in daily use, non-urgent information like Provider descriptions never took up that much space, resulting in large empty areas at the bottom of the interface. Provider descriptions were changed to a tooltip that only pops up when the mouse hovers over the top Provider title, no longer persistently occupying space. Status messages (loading / timed out not installed / save success or failure) continue to be displayed persistently — these are messages the player must see immediately and are not suitable for hiding in a tooltip.

**Why `PacketCodec.of` was hand-written instead of using the tuple overload**: After `SubtitlePayload` added the three fields `conversationId`/`sourceLang`/`targetLang`, the field count reached 8. Since all previous payloads in the project used at most the 5-field `PacketCodec.tuple(...)` overload, there was no precedent to verify whether a corresponding overload existed when the field count kept increasing. This time, the encoding/decoding was hand-written using `PacketCodec.of(encoder, decoder)`, not relying on any uncertain number of overloads.

**Reason for the execution order adjustment**: The creation/grouping step of `Conversation` was moved up from "only executed when there are listeners" to "executed regardless of whether there are listeners," so that the speaker themselves is always first assigned to a Conversation (even if no one is around, only themselves) — this way, the speaker's own "talking to themselves" echo messages can also be properly grouped in the history, without needing a special marker to indicate "doesn't belong to any conversation group."

**Criterion for determining whether a third party can be counted as "joining the conversation"**: This is entirely determined by the data sent down by the server. The server only sends `ConversationRosterPayload` to "people who can actually receive this conversation message at that time," so when A can't see Alex but B can, A's history naturally won't show "Alex joined the conversation" — this is a natural result of the client being completely passive in receiving data, and the client doesn't need to make any additional judgments.

**Known limitations**: The `SubtitlePayload` protocol change means that the client and server of this version must be updated simultaneously to communicate properly — the field count change will cause `PacketCodec` decoding incompatibility between old and new versions. This is not a newly introduced limitation (the project had never done cross-version protocol compatibility before), but it's worth recording: every future modification to the field structure of such core payloads should be treated as a "breaking change."

---

## 0.11.0　Hint text migrated to the bottom-left blank area + fixed two misleading status displays

**Root cause of the control area and bottom hint text sharing the same baseline**: In `MCCFConfigScreen.init()`, `contentBottom = this.height - MARGIN`, and this value serves simultaneously as "the lower boundary of the last row of controls in the right-side settings area" (passed to the Panel's `bottom` parameter) and as the coordinate baseline for the "bottom hint text area" (in the Panel's `renderExtra`, `screenBottom = screen.height - 20`, which has the same value as `contentBottom`) — one arranges controls from top to bottom, the other arranges text from bottom to top, and when there are many rows (`LocalConfigPanel` at most has "Provider description + force-server-mode warning (may wrap) + detection status + operation status message" — four rows), they will visually overlap in the middle area.

**Why "forced server mode but no server detected" was changed from a persistent warning to an intercepting confirmation dialog**: The original design's persistent red text had dynamic wrapping with an unfixed number of lines, making it easy to ignore, and was also one of the main causes of the overlap problem mentioned above (the number of wrapped lines was uncertain, so accurate space couldn't be reserved when laying out other lines). The new design only pops up a `ConfirmScreen` when the player clicks "Save," and the current selection resolves to "forced server mode," and the client indeed can't detect that the server has MCCF installed. This is not a "for reference only" risk hint, but a "will definitely cause problems" operational consequence — a popup interception is better than persistent text at ensuring the player actually sees it and makes a choice.

**Why the basic 3-parameter constructor of `ConfirmScreen` was used**: In the 1.21.1 environment, there's no locally decompiled source code to verify against, and the overload of `ConfirmScreen` that supports custom button text has had signature changes across different Minecraft versions; using it rashly carries a compilation failure risk, so the basic 3-parameter version (`BooleanConsumer, Text, Text`) was not adopted.

**Analysis of dead code removal**: `if (!tabVisible) return;` was found to never execute after investigation — `MCCFConfigScreen.render()` only calls a Panel's `render()`/`renderExtra()` when `activeTab` matches the current Panel, and the `renderExtra` of an inactive tab is never called at all; `tabVisible` is always `true` within this method body. These two lines of dead code were cleaned up, and comments explaining the reason were added at the beginning of both classes' `renderExtra`, to prevent anyone later from thinking this check was meaningful and relying on it.

---

## 0.10.0　DeepSeek disappearing from the Provider list + removing the "set as default" button

**Root cause of DeepSeek disappearing from the Provider list**: `ProviderListWidget` is purely statically rendered; 8 Providers × 20px row height requires 160px total height, but the rendering logic's `y + ENTRY_HEIGHT > maxY` directly `break`s entries that exceed the visible area, with no scrolling capability at all. Once the height allocated to the list is less than 160px (common at smaller resolutions, or after the previous version's changes reserved space for bottom hint text and further compressed available height), the Providers at the end of the array (`deepseek` at position 7, `ollama` at position 8) are directly truncated, and the player can't see or click them at all.

**Why the "set as default" button was removed**: The original design (see the 0.6.0 record) was an intentionally designed two-step separation — clicking the left-side list only switched the "selected for viewing," and you needed an additional click on the "Save and Enable" / "Set as Local Default" button to mark the currently viewed Provider as the target to be enabled. But user feedback said this flow was unnecessary and added an extra step. The new design: clicking "Save" now simultaneously accomplishes both "save field changes" and "set as default," without needing an additional confirmation step.

---

## 0.9.0　Config screen hint text position + fix potential text/control overlap

(The root cause analysis is the same as 0.11.0's "control area and bottom hint text sharing the same baseline"; this is where it was first discovered and fixed.)

**"Forced server mode but no server detected" changed from a persistent warning to an intercepting confirmation dialog**: The reason is the same as 0.11.0; this is where it was first implemented. The original design's persistent red text was easy to ignore and was also one of the main causes of the overlap problem. The new design's popup interception is better than persistent text at ensuring the player actually sees it and makes a choice.

---

## 0.8.0　English README + GitHub Actions auto-release workflow

**Why the English README is not a line-by-line translation**: The 1000+ lines of technical details, development process records, and annotation conventions are of limited practical help to English-speaking players, and the cost of line-by-line translation plus long-term bilingual sync maintenance is too high, making it easy for the two documents to drift apart over time. Instead, it focuses on "what it is / what benefits it has / how to use it / a few must-know caveats" — the parts players need most — written completely and carefully (not a watered-down version of the Chinese version), with a link back to the Chinese version at the end as complete technical reference.

**Design of `extract_changelog.py`**: From the "Section 8, Changelog" of the README, it precisely matches the corresponding `### YYYY-MM-DD　<version number> ...` heading line by version number, captures content up to the next changelog entry, and auto-fills it as Release Notes. When the corresponding version number can't be found, it makes the workflow fail explicitly (rather than publishing a Release with an empty description), reminding the developer: **before creating a tag, you must first add the entry for the corresponding version number in the README changelog** — this is a newly added mandatory prerequisite step.

---

## 0.7.0　Chat history screen row height bug + new conversation grouping

**Root cause and misjudgment history of the row height bug**: In 1.21.1, the constructor signature of `AlwaysSelectedEntryListWidget` / `EntryListWidget` is `(client, width, height, y, itemHeight)` (verified on yarn 1.21.1+build.3 + cross-checked with online javadoc); the 5th parameter is **itemHeight (row height)**, not bottom. But `ChatHistoryScreen` and `ModelSelectionScreen` mistakenly thought it was the old version's `(client, width, height, top, bottom)`, passing `this.height - 40` (which should have been bottom) as itemHeight, causing each record's row height to equal the entire list area height, so only one message could be seen per screen. This is a continuation of the project's earlier misjudgment that "1.21.1 itemHeight is not configurable": the decompiled jar only shows parameter types `(client, int, int, int, int)` without parameter names, combined with the preconception that "1.20.x uses 6 parameters (top, bottom, itemHeight)," the 5th parameter was taken as bottom. In reality, itemHeight has always been configurable; it was just being misused.

**Trade-off of pure client-side grouping by time inference**: Pure client-side mode has no server-side grouping information available, so time clustering is used uniformly to avoid two data sources / two rendering logics. The trade-off is that boundaries are less precise (two independent conversations less than 30 seconds apart will be merged; one long conversation silent for more than 30 seconds will be split), which is acceptable for "looking back at roughly what happened." If unsatisfied, you can tune `GROUP_GAP_MS`.

---

## 0.6.2　RateLimiter unit tests + rate-limiting logic extraction

**Why only rate limiting was tested, not caching**: `TranslationService`'s caching logic requires mocking `TranslationProvider` + `WorldDictionary`, and the mock code volume might exceed the test value. The rate-limiting logic is pure concurrency control, doesn't depend on any MC classes, and can be tested directly after extraction — the highest return on investment. Concurrency race scenarios (50 threads calling simultaneously) are almost impossible to cover with manual testing — if synchronized / double-checked locking has flaws, this test will immediately raise an alarm in CI.

---

## 0.6.1　Pause menu entry + fix chat history having no entry point

**Why Mixin was not used**: The Fabric API's `ScreenEvents.afterInit` event is sufficient to dynamically append a button after `GameMenuScreen` is initialized; using Mixin to add a single button is overkill.

---

## 0.6.0　Pure client-side local settings panel's "get model list" feature

**Why `ModelSelectionScreen` was decoupled from `ClientConfigState`**: Originally, this Screen hardcoded reading/writing model fields from `ClientConfigState`, making it unusable by the local panel that uses `ClientOnlyTranslationConfig`. After refactoring, values are passed via the `currentModel` parameter + `selectionCallback` callback, not coupled to any config class, so both panels can use it.

---

## 0.5.0　Speaker doesn't receive echo of their own messages + prominent Mock Provider warning + new chat history

**Root cause of the speaker's own message echo fix**: `SpatialChatHandler` excludes the speaker themselves when calculating candidate listeners (`!p.getUuid().equals(sender.getUuid())`), and intercepts the vanilla chat broadcast, causing the speaker to never receive any echo of their own just-sent message — whether VISIBLE (chat box) or AUDIBLE (hotbar subtitle), the speaker couldn't see what they themselves said.

**Design of `displayMode` following "the dominant mode of other listeners at the time of this utterance"**: When there are more VISIBLE listeners, the echo goes to the chat box; when there are more AUDIBLE listeners, the echo goes to the hotbar subtitle; when there are no listeners at all, it defaults to VISIBLE (the chat box is safer and won't flash by).

**Reason for the prominent Mock Provider warning**: Mock Provider is just a placeholder that adds a `[language code]` prefix to the original text and doesn't call any real translation API — this is the **default value** for both pure client-side mode and server-side configuration. Players who install the mod and try it out directly can easily mistake this placeholder effect for "translation isn't working" (a real feedback case).

**Trade-off of history not being persisted to disk**: This is an intentional trade-off (see the `ChatHistoryManager` class comment), not an oversight. CLIENT_ONLY history entries under pure client-side mode (`ClientOnlyChatTranslator`) have no reliable `speakerName` (this path has no server-sent speaker display name, only the raw chat text), and the history screen displays a `?` placeholder for such entries.

---

## 0.4.0　VISIBLE mode subtitles not displaying, temporarily switched back to chat box

**Problem**: When the other party is visible (VISIBLE mode), subtitles don't appear next to the player model. The "alpha channel fix" recorded in early versions (`0xFFFFFF` → `0xFFFFFFFF`) was tested and found **ineffective** — subtitles still didn't display, indicating that alpha is not (or not the only) root cause. `WorldSubtitleRenderer`'s world-space rendering has an unidentified underlying problem, and there's currently no runtime environment to test and confirm.

**Why temporarily switched back to the chat box**: Because the server had already split listeners into visible / audibleOnly batches by distance/line-of-sight, and only sent VISIBLE packets to those who could see, the chat box naturally only shows what "the few people I can see" said — satisfying "the chat box content can only be these few that I can see." The AUDIBLE mode is completely unchanged, corresponding to the user's "in cases where you can't see, still follow the original timing."

**`WorldSubtitleRenderer` root cause candidates** (none confirmed by testing, ordered by suspicion level):
1. `findEntity` matches the speaker entity by UUID in `client.world.getPlayers()`; if the match fails, it skips and draws nothing (in theory the runtime UUID should be consistent, but this has never been tested).
2. The `context.consumers()` in the `WorldRenderEvents.AFTER_ENTITIES` phase is the Immediate of the world rendering pipeline, and its SEE_THROUGH layer buffer may be uniformly flushed at the end of the frame; if it's prematurely `end()`ed by other rendering phases, or if the depth/blend state is abnormal, the text may be drawn but invisible to the eye.
3. The negative Y scaling `matrices.scale(-scale,-scale,scale)` + `camera.getRotation()` billboard combination may be back-face culled or depth-occluded under certain viewing angles.

---

## 0.3.2　Adding 7 new languages for localization

**Considerations for each language's translation conventions**: All languages follow the official Minecraft menu translation conventions of their respective language regions (e.g., the menu path "Settings → Controls → Key Binds" in Japanese is「設定 → 操作 → キーバインド」, in German is「Einstellungen → Steuerung → Tastenbelegung」), to avoid a machine-translated feel. Provider names (OpenAI/Claude/Gemini, etc.) are kept in their original form; the company names in parentheses are handled per each language's conventions (Chinese keeps the Chinese company names, other languages uniformly use the English company names). German retains the English terms Provider/Endpoint/Log (as is customary in the German IT community).

---

## 0.3.1　Compile error fix: TextFieldWidget password masking API + comment Unicode escape

**The `setRenderPasswordReveal` pitfall**: `TextFieldWidget.setRenderPasswordReveal(boolean)` existed in 1.20.x and earlier, and was removed in 1.21.1. It was changed to `setRenderTextProvider(BiFunction)`, passing in a function that replaces characters with `•` (U+2022) to achieve the same effect. Trade-off: lost the vanilla "hold to briefly show plaintext" interaction (which this project doesn't use).

**The `\uXXXX` in comments pitfall**: The Java compiler processes `\uXXXX` sequences during the lexical analysis phase (before comment recognition), and `\uXXXX` in comments (where `X` is an invalid hexadecimal) will cause compilation failure. Changed to the `U+XXXX` form.

---

## 0.3.0　Subtitle position rework + forced client-side mode fix + performance and stability optimizations

**Root cause of the forced client-side mode bug**: After a player sets "forced pure client-side mode" in the config screen, if the server has MCCF installed, the client still runs in server mode (not translating the chat bar) rather than only doing local translation. Root cause: the server-side `SpatialChatHandler` only checks "whether the server has MCCF installed," doesn't know the player's client-side mode preference, and still intercepts the vanilla chat broadcast to send `SubtitlePayload` instead, causing the client to never receive the vanilla CHAT event and `ClientOnlyChatTranslator` to not trigger. Fix: added `ModePreferencePayload` (a C2S network packet); the client notifies the server of its mode preference via it when joining a server and when switching modes.

**Why translation failure results are no longer written to the cache**: Previously, when the network briefly dropped, failure results were permanently cached, causing translations to remain impossible even after the network recovered. The cache was changed to an LRU policy, max 5000 entries, with a TTL added (1 hour).

**Why the `translateAndAppend` method was extracted**: To reuse translation logic (shared by the CHAT event listener and the SubtitlePayload fallback), avoiding code duplication.

---

## 2026-07-28　Target version switched from 1.21.8 to 1.21.1

**Why switched to 1.21.1**: Per the project directory naming (`MC-Conversation-Framework-1.21.1`) and mod ecosystem compatibility requirements. 1.21.1 is an early stable version of the 1.21.x series, with the broadest mod ecosystem compatibility (many 1.21.x mods use 1.21.1 as their baseline). At the same time, the APIs this project depends on — `WorldRenderEvents`, `HudElementRegistry`, `KeyBinding` string categories, etc. — are completely identical between 1.21.1 and 1.21.8, so the source code requires no changes at all.

**API compatibility verification process and misjudgment correction**: The initial assessment was that 1.21.1 and 1.21.8 were completely identical on the APIs this project uses. After local compilation verification, 3 differences were actually found, and all were fixed (`PacketCodecs.BOOLEAN` → `BOOL`, `HudElementRegistry.addLast` → `HudRenderCallback.EVENT.register`, `getTickProgress` → `getTickDelta`).
> ⚠️ The semantic judgment of the 5-parameter constructor here was wrong: in 1.21.1, the 5 parameters are `(client, width, height, y, itemHeight)`, and the 5th parameter is itemHeight, not bottom. It was mistakenly taken as bottom at the time, causing the row height bug, which was fixed in 0.7.0.

---

## 2026-07-28　3 API differences fixed per local compilation report

**API signature verification method**: Used PowerShell + `System.IO.Compression.ZipFile` to directly open `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-clientonly-1.21.1-...-v2.jar` and decompile to view the actual method signatures of `AlwaysSelectedEntryListWidget` and `RenderTickCounter`, then used `javap -p` to output the full signatures. This is more reliable than checking Fabric's online docs (where different versions are mixed together and easy to misread) or asking an AI (which easily applies new-version answers to old versions) — the local jar is the exact one the project actually compiles against, and whatever signature it shows is what the compiler accepts.

---

## 2026-07-28　JAR naming + version number strategy

**Why renamed to MCConversationFramework**: The previous JAR name was `mccf-0.1.0.jar`, too brief; now it's `MCConversationFramework-0.2.0.jar`.

---

## 2026-07-17　Package name fix + build configuration fix

**The one-by-one verification process after the feedback "all `net.minecraft.*` packages cannot be found"**: Verified the package path of every Minecraft class referenced in the project one by one (against the Yarn official Maven documentation), fixing package path errors for `ServerPlayerEntity`, `RaycastContext`, etc., as well as build configuration issues like `build.gradle` referencing undefined variables, Loom plugin version floating, and mappings selection.

---

## Version downgraded from 1.21.11 to 1.21.8

**Why downgraded to 1.21.8**: When implementing the "display subtitle near speaker" feature, it was discovered that Fabric API's `WorldRenderEvents` was officially removed entirely in the 1.21.9/1.21.10 port (rendering pipeline restructuring, no replacement). To preserve this core experience, the target version was downgraded from 1.21.11 to 1.21.8 (the last version where that API worked properly).

---

## Gradle version reverted to 9.2.0

**Why reverted to 9.2.0**: Gradle was at one point changed from the verified 9.2.0 to "the 8.14/9.0 written in the official docs," which then errored in testing: `Could not resolve net.fabricmc:fabric-loom:1.14.10` (No matching variant). The reason is that **the exact patch version Loom 1.14.10 actually used the Gradle Plugin API 9.2.0 at release time**, which is stricter than the coarse-grained "officially supports 8.14/9.0" statement — different patch versions may have actual Gradle requirements that update faster than the major version number declarations.

---

## 3 bugs fixed per local compilation report (1.21.8 era)

**Discovery process of the `PacketCodecs.BOOLEAN` vs `BOOL` version difference**: Confirmed that 1.21.8+build.1 actually uses the field name `PacketCodecs.BOOLEAN` (the earlier record's "should be BOOL" was wrong; local testing prevails).

> **2026-07-28 correction**: This conclusion only holds on 1.21.8. When the target version was later switched back to 1.21.1, local compilation verification found that on 1.21.1, the Yarn mappings field name is still `BOOL`, and it was only renamed to `BOOLEAN` in 1.21.8. In other words, **the earlier record "should be BOOL" was actually correct** — it was just that when `BOOLEAN` was verified on 1.21.8 at the time, it was mistakenly thought to be wrong — the same code has different field names on different MC versions.

---

## Crash fix: resizing window while not connected to a server causes crash

**Root cause**: `MCCFConfigScreen.init()` unconditionally calls `ClientPlayNetworking.send(...)`, but `init()` is executed not only when a Screen is first opened — Minecraft also re-runs `init()` for all currently open Screens when the player **resizes the game window** (part of the vanilla resize flow). If the player opens the config screen before entering a world / connecting to a server, `send()` throws an exception when called with no active network connection; this exception occurs within the resize flow and is not caught, directly causing the client to crash. Fix: changed to use the `ClientPlayNetworking.canSend(payloadId)` method (specifically used to determine "whether currently in-game and able to send that payload"; returns `false` instead of throwing when not in-game).

---

## Subtitle not displaying fix

**Root cause**: In `WorldSubtitleRenderer.java` (VISIBLE mode, subtitle floating above the speaker), there was a color value bug: the text color used `0xFFFFFF`, which in Minecraft's ARGB color format is missing the top 8 bits of the alpha channel, equivalent to `0x00FFFFFF` (alpha=0, fully transparent) — that is, the subtitle was indeed rendered, just rendered fully transparent and invisible to the eye. Changed to `0xFFFFFFFF` (fully opaque white). `HotbarSubtitleRenderer.java` (hotbar-above mode) uses the official `Colors.WHITE` constant, and the color value itself had no problem.

---

## 2026-07-22　README cleanup

**Issues found and fix considerations**: After the first full read-through of all source code, several issues were found in the README that didn't match the code / were internally contradictory — section numbering was chaotic ("四" and "四" shared the same number, order was disrupted); the directory structure was outdated (the entire `config/` package was missing); the Fabric API version description was self-contradictory (the beginning said 0.130.0, the compilation section said 0.141.5+1.21.11, leftover old text from when the version was downgraded from 1.21.11 to 1.21.8); the Provider integration example code didn't match the current architecture (the original example wrote `translationService.registerProvider(...)`, but it had actually been unified into `ProviderFactory`). Additionally, two dead code classes `RequestModelListPayload` / `ModelListResponsePayload` were found (didn't affect runtime, weren't actively deleted at the time, were noted as pending confirmation on whether cleanup was needed, and were deleted in 1.0.0).

---

## 2026-07-22　New feature: pure client-side mode

**Why the design deliberately keeps it completely separate from the existing server-authoritative config**: Local translation config only affects the result the player themselves sees, and doesn't need to — and shouldn't — apply the "op-only can edit" rules designed for server-side config that "affects everyone."

**Why the "sync from server" button reuses existing data**: It reuses the existing `ConfigSnapshotPayload` data (`ClientConfigState`), with no new network packets added; it only copies public fields like Provider/model name/Endpoint, not the API Key. The detection method is `ClientPlayNetworking.canSend(RequestConfigPayload.ID)` — reusing the existing config request channel, without needing to send an extra "probe" packet and wait for the server to respond.
