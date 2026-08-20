# RE notes: seen / typing / online-status privacy hooks

This documents how the symbols behind `messages.block_seen_status`,
`messages.block_typing_status`, and `messages.hide_online_status`
(`StatusPrivacyFeature.java`, `symbols.chat.*` in `symbol-schema.json`) were found for
Zalo 26.08.01 (`versionCode` 260801903), so the next update doesn't start from zero.

R8 renames obfuscated class/method names on basically every Zalo release, so
`send_seen_manager_class`, `send_typing_class`, `online_status_save_class` and their
method names **will drift**. Unobfuscated names (packages, well-known Android/AndroidX
APIs, string resource names, log tags, tracking IDs) are much more stable and are what
the technique below re-anchors on each time.

**Field-tested revision history, so the mistakes aren't repeated:**
1. First cut blocked the entire seen/delivered flush (`Lje0/k0;->i`) — this also hid
   *delivered* status from the other side, not just *seen*. Fixed by filtering the
   batch by entry type instead of blocking the whole call (see below).
2. First cut of online-status forced `Lyz/j;->j2()Z` to always return `true`, hoping
   that would only affect the "can I see friends' status" read path. It didn't — the
   same getter backs the UI that reports whether *you* are hidden, so the native
   toggle silently stopped reflecting reality and the user's own visibility could no
   longer be turned off at all. Fixed by not touching any getter and instead directly
   submitting the real privacy-save request Zalo's own UI sends (see below).

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

### Seen receipt (delivered left untouched) — `messages.block_seen_status`

- Log tag `"SendSeenManager"` / debug string `"sendSeenToServer(). From group: "` led
  straight to `Lje0/k0;` (class), method `i(Ljava/util/ArrayList;Z)V`.
- This is the single funnel every queued read/delivered ack flushes through
  (`Lje0/k0;->g()` schedules the flush job, which eventually calls `i(...)`) before
  reaching the socket layer (`Ls00/x;->e(Ljava/util/List;ZIILf11/j0;)V`, packet types
  `0xcb`/individual `0x6b`). **This one funnel carries both delivered and seen acks —
  do not block it wholesale**, that hides delivered too (confirmed by field testing).
- Each queued entry (`Ln00/b`) carries a type in instance field `a`: **`2` = delivered,
  `3` = seen**. Found by comparing the two enqueue methods: `Lje0/k0;->b(J,String)V`
  (called from `com/zing/zalo/ui/zviews/n`, an incoming-message handler — sets `a = 2`)
  vs. `Lje0/k0;->h(String,Lo00/q)V` (the per-conversation "user is now viewing this"
  trigger — hardcodes `a = 3`). `Lje0/k0;->j(ArrayList)V`, called right before the
  flush, confirms the scheme: it skips entries where `a == 2` (`goto` past them
  untouched) and only rewrites `a == 3` entries to `1` (a "sent" sentinel) after they
  go out.
- **Bug (found only by live field testing, not by re-reading the smali harder):** the
  first fix filtered the batch for `a == 3`, matching the value `h()`'s enqueue
  hardcodes for a seen entry. It never dropped anything — confirmed live: the flush
  fired with a real one-entry batch and the "dropped" self-check hit never appeared.
  Cause: `Lje0/k0;->j(ArrayList)V` **always runs immediately before `i()` on the exact
  same list** (same caller, same object references, `j(list)` then `i(list, isGroup)`
  back to back) and, as a side effect, rewrites every `a == 3` entry to `a = 1` *in
  place* before `i()` — and this hook on `i()` — ever inspects the batch. By the time
  the filter runs, no entry is ever still `3`.
- Fix: don't chase what value a seen entry ends up labeled after `j()`'s mutation.
  Instead keep only `a == 2` (delivered, the one value `j()` never touches) and drop
  everything else. Unambiguous regardless of whether a seen entry is currently `1`
  (the normal, already-relabeled case going through `j()`) or still `3` (would only
  happen if some other path fed `i()` directly, bypassing `j()` — not observed, but
  the delivered-allowlist approach is safe against it either way).
- **Re-find next time:** grep for `"SendSeenManager"` or `"sendSeenToServer"` to
  relocate the manager class, then grep that class for the two enqueue methods (one
  called from a message-arrival class, one from a conversation-open/mark-read call
  site) and read the `iput ... ->a:I` int literal each one uses for "delivered" —
  that's the value to allowlist. Don't trust which value means "seen" without
  re-reading whatever runs immediately before the flush method (`j()` this release)
  for a same-list mutation like this one; **the only way this bug surfaced was
  instrumenting the actual hook with unconditional logging and testing on a real
  device with a real conversation** — reading the decompiled logic correctly still
  missed it, because the flush method's *own* code was faithfully reproduced, just the
  runtime state arriving at it (already mutated by its caller) wasn't accounted for.
  If a future "block X" hook based on an entry-type filter doesn't work, suspect this
  same pattern first: check whether anything runs immediately before the hooked method
  and mutates the objects being passed in.
- **Second bug, also only found live:** the fix above blocked seen status for
  conversations opened fresh (e.g. from a notification), but messages read while
  *staying* in an already-open conversation still reached the other side as seen.
  There is a **second, independent path to the same socket send**:
  `Ll00/r;->Q(Ljava/util/List;ZZZ)V`, called directly from `ChatView`'s live-message
  handling via `Lje0/k0;->a(Ljava/lang/String;)V` (found by grepping every caller of
  `Ls00/x;->e(...)`, the actual socket send — there were two, not one: `Lje0/k0` and
  `Ll00/r`). `Q()` picks between two packet-cmd families (`0xca`/`0x66` vs
  `0x27e5`/`0x2781`) based on its third boolean parameter, but that parameter's exact
  semantics were never nailed down with confidence — reading a handful of call sites
  wasn't conclusive, and guessing wrong risks blocking an unrelated ack (reaction,
  recall, who knows) that happens to share this call.
- Fix: don't gate on `Q()`'s parameters at all. Filter its `List` argument by entry
  type exactly like the flush above (extracted into a shared `filterToDeliveredOnly`
  helper) — defensively: any entry that doesn't expose the type field at all is left
  in the batch untouched rather than dropped, since at that point this module can't
  tell what kind of ack it actually is.
- **Re-find next time:** don't assume `Lje0/k0`'s flush is the only sender. Grep every
  caller of the actual socket-send method (`Ls00/x;->e(...)` this release, found via
  `grep -rl` for its full signature across the whole tree, not just within the classes
  already suspected) whenever a "block X network thing" hook seems to work sometimes
  and not others — that pattern (works from a fresh entry point, not from continued
  live use, or vice versa) is a strong signal there's more than one call site feeding
  the same wire protocol, not that the one hook found is broken.

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

### Online-status visibility — removed, trail kept for reference

**Not shipped.** This was implemented and then pulled at the user's request: even with
the submit-the-real-request approach below (rather than the earlier, worse getter-bypass
attempt), there was an unresolved risk that once your own account's now-hidden
`online_status` preference synced back down from the server on a later
login/reconnect, Zalo's own client would start enforcing its usual symmetric
restriction locally (the same `Lyz/j;->j2()Z` flag gates both "do I show mine" and "can
I see others'"), silently blinding you to everyone else's status too. Fixing that
cleanly needed a second, narrower hook that was never field-verified, and the user
decided it wasn't worth shipping half-verified. The trace below is kept because the
generic "save a privacy setting over the socket" mechanism it found
(`Lpn/h0;->q3(settingId, value, extra)`, socket cmd `0x111`, shared by ~200 different
save calls in that one class) is reusable for other privacy-setting features later,
even though this particular feature isn't built on it anymore.

Online-status visibility is a **stored server-side privacy preference**, not a live
per-connection broadcast — there is no outbound "I'm online" packet to block. The
fix submits the exact same privacy-save request Zalo's own UI sends when you flip its
toggle, directly from the module, for accounts where that native UI isn't reachable.
It never reads or touches the "can I see others" path, so that stays exactly as-is.

Full trace, UI down to the wire request:

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
   calls `SettingPrivateV2View;->I6(ListItemSetting, boolean)`, which — for the row
   matching field `Lf40/b;->n` (the one tagged `"recently_online_status"`) — opens
   `BottomSheetSettingView`/`SettingViewBottomSheet` with Bundle extra
   `"EXTRA_SETTING_ID" = 0x1b` (27). `SettingViewBottomSheet.S4` reads that extra
   straight into instance field `u1`, which every other method in that class then
   switches on to know which row's dialog it's building/saving for. **`0x1b` is this
   release's id for the online-status row specifically — it's assigned by Zalo per
   privacy-setting row and could renumber on a rewrite; re-derive it from the
   `"recently_online_status"` id-tracking string via `I6`, don't hardcode `0x1b`.**
5. `SettingViewBottomSheet.C6(int newValue)` is the actual save trigger: it builds
   `new Lpn/h0()`, and calls `Lpn/h0;->q3(u1, newValue, "")` — i.e.
   `q3(settingId, value, extra)`. `C6` is called (with `newValue` 0 or 1) from the
   on/off tap handlers further down the same class.
6. `Lpn/h0;->q3(IILjava/lang/String;)V` is a generic "save one privacy setting over
   the socket" method (shared by every row, not just online-status): guarded by
   `Lpn/h0;->p()Z` (checks a global `AtomicBoolean` login/session-ready flag — safe to
   call before this returns true, it just no-ops), it writes `settingId` and `value`
   as raw big-endian ints into a `RequestPacket` (cmd `0x111`/273) and sends it via
   `Loq1/l1;->c(Lbz/b0;)V`. The instance's `Lpn/h0;->b:Lf52/a` callback field is safe
   to leave `null` — every path that touches it null-checks first.
7. The module calls this the same way: `new Lpn/h0().q3(27, 0, "")` to hide, no UI,
   no Bundle, no `SettingViewBottomSheet` instance needed — `q3` is self-contained
   once you have a bare `Lpn/h0` instance.
- **Re-find next time:** grep decoded `res/values/strings.xml` for
  `setting_privacy_online_status`/`recently_online_status` as in step 1-4 above (that
  part is stable — it's the same technique used for every row in this settings
  screen), then from the row's click handler follow one more hop to the class with a
  `p()Z` login-ready guard, a `RequestBase`/`RequestPacket` builder, and a
  `Loq1/l1`-or-similar final send call — that generic-request class (`Lpn/h0` this
  release) and its `q3`-equivalent method are reused for saving *every* privacy
  setting, not just this one, so once relocated it's worth grepping its other ~200
  overloads for other privacy toggles this module might want later.

## Known separate bug: self-check reporting is unreliable

Independent of whether the hooks themselves work, live testing found
`SelfCheckRegistry`'s reports frequently never become queryable via
`content://com.ez.zalopatch.config/self_check` — `ConfigProvider.recordSelfCheck`
logged `SelfCheck provider rejected update code=-2` (an artifact-generation/profile
hash mismatch) for some features immediately after a module reinstall, and after that
the whole self-check table can read back empty even though `logcat` clearly shows
`[SelfCheck] ... installed`/`active hits=N` lines being generated locally. **Don't
trust an empty or stale self-check table as evidence a hook failed to install** — the
authoritative signal is the `[SelfCheck]`/feature debug log lines in `logcat` (enable
with `adb shell setprop debug.zalopatch 1`, relaunch Zalo), which log unconditionally
on the debug flag regardless of whether the ConfigProvider round trip succeeds. This
reporting bug hasn't been root-caused yet — likely `ZaloArtifactState` needing a fresh
reconcile after a same-versionCode module reinstall, since dev builds here didn't bump
`versionCode` between rebuilds.

## Group chats use the same funnel as 1-on-1 (confirmed live)

`Lje0/k0;->i()` takes an `isGroup` boolean and the same instance handles both; nothing
group-specific needs to be added for `messages.block_seen_status`. Live testing (with
the diagnostic logging added in the fix above) confirmed a real flush firing for a
1-on-1 conversation; group should reach the same method through the same enqueue path
(`TouchListView`'s `dispatchDraw` → `Lje0/k0;->h()`, gated by conversation uid already
being a key in `Lje0/k0;->c` — that SortedMap's population source wasn't traced; this
only matters if group conversations turn out to differ from 1-on-1 in whether/when
they get added to it, which hasn't been observed but also hasn't been ruled out).

`messages.block_typing_status` hooks `Ll00/r;->S(String, int, boolean, boolean)`
unconditionally, ignoring the `isGroup` third parameter entirely — it was already
group-agnostic by construction, no fix needed.

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
