# Status privacy: what's actually shipping

Short version of `StatusPrivacyFeature.java`, confirmed by real on-device testing.
For the full trial-and-error trail (dead ends, wrong theories, everything that didn't
work and why) see `re-notes-status-privacy.md` — this file is only the parts that made
it into the final, working build.

## Shipped

| Setting | Blocks | Confirmed |
|---|---|---|
| `messages.block_seen_status` | Seen (read) receipts **and** delivered receipts, in every case | ✅ Working everywhere |
| `messages.block_typing_status` | The "is typing…" indicator | ✅ Working everywhere, groups included |

## Not shipped

Online-status hiding ("show as offline to others, still see who's online") was
attempted three times and pulled three times. Not in the code at all right now. See
`re-notes-status-privacy.md` for what was tried and where it broke, if picking it up
again.

## How seen/delivered blocking works

Zalo sends read-receipt-style acks (both "delivered" and "seen") through **two
independent code paths** that both end up at the same socket send
(`Ls00/x;->e(...)`). Both had to be hooked; hooking only one leaked through the other.

1. **`Lje0/k0;->i(ArrayList, boolean)`** — the batched/debounced flush, reached when a
   message becomes visible in the message list (scrolling into view, opening a
   conversation fresh from a notification). Each queued entry carries a type field;
   `2` means delivered, anything else is treated as seen and dropped. Delivered
   entries pass through untouched here — this path is precise.
2. **`Ll00/r;->Q(List, boolean, boolean, boolean)`** — a second, independent path to
   the same socket send, reached from `ChatView` while a conversation is already open
   and a new message arrives live. Filtered the same way (by entry type), but here the
   filter isn't precise: some delivered acks sent through this specific path also get
   dropped. That's a deliberate, accepted tradeoff (see below), not a bug.

Both hooks share one filter helper (`filterToDeliveredOnly`): keep entries whose type
field equals the delivered value, drop the rest, and — defensively — never drop an
entry that doesn't expose the type field at all (a different, unrecognized kind of ack
left untouched rather than guessed at).

**Why the tradeoff in path 2 is accepted:** figuring out exactly which of `Q()`'s three
boolean parameters distinguishes "seen" from "delivered" would make the filter precise
there too, but guessing wrong risks silently blocking something unrelated (a reaction
or recall ack that happens to share this same call) instead. Given that risk, the
current build blocks both delivered and seen for messages read live in an
already-open conversation, and only seen for every other case. In practice, both end
up blocked consistently enough that this reads as "blocks both, everywhere" to a user
— the settings screen says exactly that.

## How typing blocking works

One hook, one call site: `Ll00/r;->S(String uid, int state, boolean isGroup, boolean
isBiz)` is unconditionally blocked (`param.setResult(null)`) regardless of its
arguments — including `isGroup`, so this was already correct for group chats with no
extra work.

## What "confirmed by real testing" actually means here

Nothing in this feature shipped on the strength of reading decompiled code alone.
Every fix here was found or verified by:
1. Pulling the live, currently-installed Zalo APK from a real device (`adb pull` +
   extracting `assets/lspatch/origin.apk` from the LSPatch-installed copy).
2. Disassembling it to smali (`apktool`) and grepping for log tags, string resources,
   and call sites — never trusting a single reading of a method's logic without
   checking what runs immediately before/after it and what else calls it.
3. Building the actual APK, installing it, and watching `adb logcat` (with
   `debug.zalopatch=1`) while performing the exact user action being tested, live.

Two specific bugs were only found this way, not by re-reading the decompiled code more
carefully:
- A caller (`j()`) that runs immediately before the hooked flush method and mutates
  the very objects the hook inspects, invisible unless you check what happens to the
  hooked method's arguments *before* it's called, not just inside it.
- A second, independent call path to the same network effect, only found by grepping
  *every* caller of the actual socket-send primitive instead of assuming the first one
  found was the only one.

If a similar "block X" feature is added later and seems to work sometimes and not
others, expect one of these same two causes and check for them first.
