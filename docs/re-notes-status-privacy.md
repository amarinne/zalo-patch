# RE notes: seen / typing / online-status privacy hooks

This documents how the symbols behind `messages.block_seen_status`,
`messages.block_typing_status`, and `messages.always_see_online_status`
(`StatusPrivacyFeature.java`, `symbols.chat.*` in `symbol-schema.json`) were found for
Zalo 26.08.01 (`versionCode` 260801903), so the next update doesn't start from zero.

R8 renames obfuscated class/method names on basically every Zalo release, so
`send_seen_manager_class`, `send_typing_class`, `online_status_flag_class` and their
method names **will drift**. Unobfuscated names (packages, well-known Android/AndroidX
APIs, string resource names, log tags, tracking IDs) are much more stable and are what
the technique below re-anchors on each time.

## Toolchain

No emulator/decompiler needed beyond what's already free: `adb`, `apktool` (a single
jar, needs a JRE — `apk add openjdk17-jre-headless` on Alpine, or any JDK), `unzip`.
`jadx` also works but is much slower for this kind of grep-driven search; apktool's
smali output greps just as well and disassembles in seconds instead of minutes.

```sh
# 1. Pull the live, patched Zalo APK from a device (LSPatch or LSPosed doesn't matter).
adb shell pm path com.zing.zalo   # note the base.apk path
adb pull /data/app/.../base.apk .

# 2. If it's an LSPatch install, the REAL app is embedded whole and un-obfuscated by
#    the patch process at assets/lspatch/origin.apk — pull that out; don't bother
#    decompiling the tiny LSPatch loader stub in base.apk itself.
unzip -p base.apk assets/lspatch/origin.apk > origin.apk
# (Skip this step entirely for a plain, un-patched APK pulled from an LSPosed device
# or downloaded straight from Zalo/an APK mirror.)

# 3. Disassemble to smali (fast, all dex files, no resources needed for code search):
java -jar apktool.jar d --no-res -o smali-out origin.apk

# 4. If you need to resolve an R.string/R.id reference to its literal text (see the
#    online-status trace below), redo step 3 without --no-res to also get
#    res/values/strings.xml and res/values/public.xml (id -> name mapping). This is
#    much slower (a minute or two) since it decodes the whole resource table, so only
#    do it when the class-name/log-tag search alone doesn't converge.
```

Then it's just `grep -rn` over `smali-out/`. The three techniques that actually worked:

- **Grep for the feature in English/Vietnamese as a log tag or debug string.** Zalo's
  obfuscated classes almost always keep their **log tag** and any `Log`/crash-report
  strings as literal `const-string`, even when the class/method names are mangled.
  `"SendSeenManager"`, `"sendSeenToServer(). From group: "`, `"deliveredToServer: "`
  all survived obfuscation intact and were the entry point for every symbol below.
- **`packed-switch` tables read bottom-to-top.** R8 merges many lambdas/Runnables into
  one synthetic class dispatched by an int tag (`new-instance Lx/y; invoke-direct
  {v1, p0, N}`). The `.packed-switch 0x0` table's *first* listed label is case `N`,
  but the labels are very often written in **descending** order (`pswitch_1c` first,
  `pswitch_0` last) — index into the list from the top, don't assume label numbers
  match case numbers.
- **Resource IDs are a reliable bridge from "the UI text I can see in the app" to
  "the class that binds it."** `grep` the decoded `res/values/strings.xml` for the
  Vietnamese/English label as displayed, get its `res/values/public.xml` id, then
  `grep` smali for `sget v0, Lei/x2;->that_string_name:I` (the R-class field access) to
  find every class that touches that exact row/label.

## What was found (versionCode 260801903)

### Seen / delivered receipt — `messages.block_seen_status`

- Log tag `"SendSeenManager"` / debug string `"sendSeenToServer(). From group: "` led
  straight to `Lje0/k0;` (class), method `i(Ljava/util/ArrayList;Z)V`.
- This is the single funnel every queued read/delivered ack flushes through
  (`Lje0/k0;->g()` schedules the flush job, which eventually calls `i(...)`) before
  reaching the socket layer (`Ls00/x;->e(Ljava/util/List;ZIILf11/j0;)V`, packet types
  `0xcb`/individual `0x6b`).
- Hooking `i(ArrayList, boolean)` and returning immediately means the local UI still
  marks messages read (that update already happened before this call); only the
  packet telling the other side is dropped.
- **Re-find next time:** grep for `"SendSeenManager"` or `"sendSeenToServer"`.

### Typing indicator — `messages.block_typing_status`

- Found via `Ll00/r;->S(Ljava/lang/String;IZZ)V`, which is called directly from
  `ChatView`/`Led1/u7` (the un-obfuscated `com/zing/zalo/ui/chat/ChatView` class) when
  the chat input's text changes. `S` wraps `Ls00/x;->f(Ljava/lang/String;IZZ)V`, which
  builds the `0xce` typing-state packet.
- No stable log string for this one — it was found by first locating the *receiving*
  side (`AnimTypingTextView`, `ChatView.u1` field, found by grepping for `"typing"`
  case-insensitively — those are unobfuscated class names and stayed easy to find),
  then working outward: `Ls00/x` (the socket dispatch class) already known from the
  seen-status trace, enumerate its `.method` list, and the odd-one-out signature
  `f(String, int, boolean, boolean)` matching a state parameter was the typing send.
  Its only caller (`Ll00/r;->S`) is what we hook, one level above the raw packet
  builder, since that keeps the hook stable even if `Ls00/x`'s internal packet layout
  changes.
- **Re-find next time:** grep smali for `"chat_typing"` R-string usage in
  `com/zing/zalo/ui/chat/ChatView.smali` to relocate the `AnimTypingTextView` field,
  find the `Ls00/x`-equivalent socket class from the seen-status trace (same class,
  reused), and look for a `(String, int, boolean, boolean)` method on it.

### Online-status visibility — `messages.always_see_online_status`

This one is architecturally different from the other two — see the doc comment on
`StatusPrivacyFeature.installOnlineStatusBypass()` for *why* it's a getter-bypass
rather than a send-block. Trace, for next time:

1. `res/values/strings.xml`: `setting_privacy_online_status` = "Hiện trạng thái truy
   cập", `setting_sub_online_status` = the two-way description. This needed the
   resources decode (step 4 above), not just `--no-res`.
2. `res/values/public.xml`: resolves that string name to an R id
   (`0x7f1007ec` this release — **do not hardcode this hex value, it moves every
   build**; always look it up fresh).
3. Grep smali for the **string name** `setting_privacy_online_status` (not the hex id
   — R8 keeps `sget v0, Lei/x2;->setting_privacy_online_status:I`, a stable textual
   reference, even though the numeric id itself is unstable) to find
   `SettingViewBottomSheet` and the row-tracking id `"recently_online_status"` in
   `SettingPrivateV2View`.
4. In `SettingPrivateV2View`, the row's click listener (`Lwi1/a2`, a per-view
   synthetic click dispatcher — same packed-switch-reversed-order trick as above)
   reads the current value via `Lyz/j;->j2()Z`.
5. Grepped every caller of `Lyz/j;->j2()Z` across the whole app (`grep -rl`, ~8 hits)
   to confirm what it actually gates: it's checked before *querying friends' online
   status* (`Le70/p;->U()/S()`) and in the post-login sync (`Loq1/w1;->w()`), not
   before broadcasting anything of our own. That's what confirmed there's no outbound
   "I'm online" packet to intercept — visibility is purely a stored server
   preference, submitted through whatever the real save flow is (never fully traced —
   wasn't needed once this was understood).
- **Re-find next time:** grep decoded `res/values/strings.xml` for
  `setting_privacy_online_status`/`recently_online_status`, follow the R-string field
  access into smali, then grep the whole tree for callers of whatever getter reads
  it (name will have changed, but it'll be a static no-arg `()Z` method called from
  a friend-status-query class and a login/session class).

## If a hook stops firing after a Zalo update

`SelfCheckRegistry` will show the feature as `stale` (symbol not found) rather than
silently doing nothing — check the module's self-check screen first. If the class
name in `symbol-schema.json` still resolves (`XposedHelpers.findAndHookMethod`
succeeded) but the packet/toggle no longer visibly blocks anything, the *call site*
inside that method likely changed shape (e.g. seen/typing got merged into one manager,
or the funnel method got split) — re-run the log-string grep for that feature above
before assuming the whole architecture changed; these features have stayed
structurally identical across at least the three bundled `versionCode` profiles in
`symbol-schema.json` so far.
