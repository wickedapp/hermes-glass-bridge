# Fix Rokid TG Lazy History Stutter Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Fix the WickedMan chat stutter/freeze when scrolling upward through lazy-loaded message history, without bulk-loading old messages on chat entry.

**Architecture:** Keep the user's required history model: initial newest 5 messages, then prepend exactly 5 older messages when the active message selector moves above the oldest loaded row. The fix must remove UI-thread stalls and race conditions by setting pending focus **before** emitting repo updates, avoiding full adapter refresh jank, and using deterministic scroll/focus after layout.

**Tech Stack:** Android Kotlin, TDLib `GetChatHistory`, `RecyclerView`, `ListAdapter`/`DiffUtil` or targeted notify calls, ADB/UIAutomator/logcat verification on connected Rokid RG glasses.

---

## Current Diagnosis

User report: after entering WickedMan and scrolling upward, UI becomes stuck and appears to not move.

Relevant current code inspected:

- `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/data/MessageRepo.kt`
  - `loadInitial(limit = 5)` calls `load(0, 5)`.
  - `loadOlder(limit = 5)` calls `load(oldest, 5)`.
  - `load()` updates `_messages.value` inside `MessageRepo` before returning `inserted`.
- `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatFragment.kt`
  - On top boundary, `moveFocusInsideMessages()` launches coroutine, calls `repo?.loadOlder(5)`, and only **after** it returns sets `pendingMessagePositionAfterHistoryLoad = inserted - 1`.
  - But `MessageRepo.load()` emits `_messages.value` before `loadOlder()` returns, so `collect { rows -> ... }` can run before `pendingMessagePositionAfterHistoryLoad` is set. That means no post-prepend focus is applied.
  - `adapter.submit(rows)` currently uses `notifyDataSetChanged()` in `MsgAdapter`, causing full RecyclerView refresh each lazy prepend.
  - `focusMessageAt()` does `requestVisible()`, then returns early if *any* descendant of the message list has focus, even if the target row is offscreen. This can block scrolling to the intended target and make the screen appear stuck.

Likely freeze/stutter causes, in priority order:

1. **Race:** pending focus is set after repo emits rows.
2. **Full refresh jank:** every 5-message prepend calls full `notifyDataSetChanged()` and focus is lost/rebuilt.
3. **Wrong early return:** `focusMessageAt()` can skip `smoothScrollToPosition(target)` when a different message still has focus.
4. **No loading guard:** repeated Up inputs at top can launch multiple `loadOlder()` calls concurrently.
5. **No visible loading feedback:** if TDLib is slow, user sees no movement and thinks it froze.

---

## Non-Negotiable Requirements

- Do **not** preload 100 messages.
- Initial entry loads only latest 5 messages for a fresh repo.
- Each upward boundary event loads only 5 older messages.
- Up/Down in active message mode moves one message item at a time.
- Header Back/Pin/Mute remain locked out while message mode is active.
- Message cards remain selectable/openable for media/voice.
- Verify on physical Rokid glasses, specifically WickedMan.
- Do not commit/push until build + install + real-device verification pass.

---

## Proposed Fix

### A. Move lazy-prepend coordination out of the current race

Change `MessageRepo.loadOlder()` so caller can know how many rows will be prepended without relying on a post-emission flag set too late.

Safer approach:

- Add a loading guard in `ChatFragment`: `private var isLoadingOlderMessages = false`.
- When user presses Up at `selectedMessagePosition == 0`:
  1. If already loading, ignore additional Up inputs.
  2. Set `isLoadingOlderMessages = true`.
  3. Set `pendingMessagePositionAfterHistoryLoad = null` initially.
  4. Call new repo method `loadOlderBatch(limit = 5): LoadOlderResult` that returns:
     - `insertedCount`
     - `oldSize`
     - `newSize`
  5. After return, if `insertedCount > 0`, call `adapter.submit(...)` indirectly via flow has already happened, but explicitly post focus to `insertedCount - 1` after next layout using `RecyclerView.post { focusMessageAt(insertedCount - 1, forceScroll = true) }`.
  6. Clear `isLoadingOlderMessages` in `finally`.

Alternative even cleaner:

- Keep repo as data source only.
- In `ChatFragment.collect`, detect prepend by comparing previous first message id to new first message id and previous size:
  - `previousFirstId != null`
  - `rows.size > previousRowsSize`
  - `rows.indexOfFirst { it.id == previousFirstId } == insertedCount`
- If the active user action requested older load, focus `insertedCount - 1` after adapter update.

Use this detection-based approach because it avoids relying on repo timing.

### B. Prevent full RecyclerView refresh jank

Replace `MsgAdapter.submit(list)` implementation from full clear + `notifyDataSetChanged()` to `DiffUtil` or stable IDs.

Minimum safe implementation:

- In `MsgAdapter`:
  - `setHasStableIds(true)` in `init`.
  - override `getItemId(position): Long = rows[position].id`.
  - Use `DiffUtil.calculateDiff(...)` with row ids and content equality.
  - Update `rows`, then `diff.dispatchUpdatesTo(this)`.

This preserves item identity and prevents visible stalls on prepend.

### C. Fix `focusMessageAt()` so it always scrolls to the requested target

Current early return is wrong:

```kotlin
requestVisible()
if (view?.findFocus()?.let { isDescendantOf(it, list) } == true) return
list.smoothScrollToPosition(target)
```

Replace with:

```kotlin
private fun focusMessageAt(position: Int, forceScroll: Boolean = false) {
    val list = view?.findViewById<RecyclerView>(R.id.messages) ?: return
    val target = position.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
    selectedMessagePosition = target

    fun requestIfLaidOut(): Boolean {
        val holder = list.findViewHolderForAdapterPosition(target) ?: return false
        val item = holder.itemView
        val targetView = findFocusableMessageChild(item) ?: item
        targetView.isFocusableInTouchMode = true
        targetView.requestFocusFromTouch()
        return true
    }

    val visibleNow = requestIfLaidOut()
    if (!visibleNow || forceScroll) {
        list.smoothScrollToPosition(target)
        list.postDelayed({ requestIfLaidOut() }, 120)
        list.postDelayed({ requestIfLaidOut() }, 260)
    }
}
```

Key change: do not return just because some message currently has focus.

### D. Add a lazy-load guard and no-op behavior during load

In `ChatFragment`:

```kotlin
private var isLoadingOlderMessages = false
private var requestedOlderLoad = false
private var lastRenderedFirstMessageId: Long? = null
private var lastRenderedSize: Int = 0
```

In `moveFocusInsideMessages(delta)`:

```kotlin
if (delta < 0 && selectedMessagePosition <= 0) {
    if (isLoadingOlderMessages) return
    isLoadingOlderMessages = true
    requestedOlderLoad = true
    modeHint?.text = getString(R.string.chat_hint_loading_older) // optional if string exists/added
    viewLifecycleOwner.lifecycleScope.launch {
        try {
            repo?.loadOlder(limit = 5)
        } finally {
            // Do not clear requestedOlderLoad here; collector needs it.
            isLoadingOlderMessages = false
        }
    }
    return
}
```

In collector after `adapter.submit(rows)`:

```kotlin
val previousFirst = lastRenderedFirstMessageId
val previousSize = lastRenderedSize
val newFirst = rows.firstOrNull()?.id
val insertedBeforePreviousFirst = if (previousFirst != null) {
    rows.indexOfFirst { it.id == previousFirst }.takeIf { it > 0 } ?: 0
} else 0

adapter.submit(rows) {
    if (requestedOlderLoad && insertedBeforePreviousFirst > 0) {
        requestedOlderLoad = false
        selectedMessagePosition = insertedBeforePreviousFirst - 1
        list.post { focusMessageAt(selectedMessagePosition, forceScroll = true) }
    }
}

lastRenderedFirstMessageId = newFirst
lastRenderedSize = rows.size
```

If not adding a submit callback, use `list.post { ... }` immediately after `adapter.submit(rows)`, but a callback is better if using `ListAdapter`/`DiffUtil` async.

### E. Add minimal instrumentation logs for the next verification only

Keep logs concise:

- `MsgRepo`: `GetChatHistory chat=... from=... limit=5 -> N msgs inserted=M`
- `ChatFragment`: `olderLoad start selected=0 size=5`
- `ChatFragment`: `olderLoad rendered insertedBeforePreviousFirst=5 target=4 size=10`

Do not spam per focus move unless needed.

---

## Step-by-Step Implementation Plan

### Task 1: Add stable IDs and DiffUtil to MsgAdapter

**Objective:** Stop full RecyclerView refresh jank during lazy prepend.

**Files:**
- Modify: `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatFragment.kt` inside `class MsgAdapter`

**Steps:**

1. Add `init { setHasStableIds(true) }` to `MsgAdapter`.
2. Override `getItemId(position: Int): Long = rows[position].id`.
3. Replace `submit(list)` implementation with `DiffUtil.calculateDiff`.
4. Import `androidx.recyclerview.widget.DiffUtil`.
5. Build with `./gradlew :app:assembleDebug`.

**Expected:** Build passes; no UI verification yet.

---

### Task 2: Fix `focusMessageAt()` target scrolling

**Objective:** Ensure focus/scroll always moves to the requested adapter position.

**Files:**
- Modify: `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatFragment.kt:603-617`

**Steps:**

1. Add optional parameter `forceScroll: Boolean = false`.
2. Set `selectedMessagePosition = target` inside method.
3. Replace current early-return logic with target-specific visibility detection.
4. Keep descendants focusable.
5. Build.

**Expected:** Build passes; Up/Down still moves messages.

---

### Task 3: Replace pending flag race with prepend detection

**Objective:** Focus the correct newly prepended message after older rows render.

**Files:**
- Modify: `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatFragment.kt`

**Steps:**

1. Remove or stop using `pendingMessagePositionAfterHistoryLoad`.
2. Add state:

```kotlin
private var isLoadingOlderMessages = false
private var requestedOlderLoad = false
private var lastRenderedFirstMessageId: Long? = null
private var lastRenderedSize: Int = 0
```

3. In `collect { rows -> ... }`, before submit, compute:

```kotlin
val previousFirst = lastRenderedFirstMessageId
val insertedBeforePreviousFirst = previousFirst?.let { firstId ->
    rows.indexOfFirst { it.id == firstId }.takeIf { it > 0 } ?: 0
} ?: 0
```

4. After `adapter.submit(rows)`, if `requestedOlderLoad && insertedBeforePreviousFirst > 0`, post focus to `insertedBeforePreviousFirst - 1` with `forceScroll = true`.
5. Update `lastRenderedFirstMessageId` and `lastRenderedSize` after each render.
6. Build.

**Expected:** No race; focus after lazy prepend happens after rows exist.

---

### Task 4: Add load guard in `moveFocusInsideMessages()`

**Objective:** Prevent repeated Up inputs from launching concurrent TDLib history requests.

**Files:**
- Modify: `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatFragment.kt:556-580`

**Steps:**

1. At top boundary, if `isLoadingOlderMessages`, return immediately.
2. Set `isLoadingOlderMessages = true` and `requestedOlderLoad = true` before launching coroutine.
3. Call `repo?.loadOlder(limit = 5)`.
4. Clear `isLoadingOlderMessages = false` in `finally`.
5. If no rows inserted and no collector-based prepend happened, keep focus at 0.
6. Build.

**Expected:** Holding/swiping Up repeatedly no longer queues multiple loads.

---

### Task 5: Improve repo logging but keep lazy behavior

**Objective:** Make device verification unambiguous without changing behavior.

**Files:**
- Modify: `rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/data/MessageRepo.kt`

**Steps:**

1. Keep `loadInitial(limit = 5)` and `loadOlder(limit = 5)`.
2. Add inserted count to existing log after insertion:

```kotlin
Timber.tag("MsgRepo").i(
    "GetChatHistory chat=%d from=%d limit=%d -> %d msgs inserted=%d totalCount=%d",
    chatId, fromMessageId, limit, msgs.size, inserted, result.totalCount
)
```

3. Ensure no path loads 100 messages.
4. Build.

**Expected:** Logs show `limit=5` only for initial/older history loads.

---

### Task 6: Real-device verification on WickedMan

**Objective:** Prove the stutter is fixed on the connected Rokid glasses.

**Commands:**

```bash
cd /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native
./gradlew :app:testDebugUnitTest :app:assembleDebug
export PATH="$HOME/Library/Android/sdk/platform-tools:/opt/homebrew/bin:$PATH"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.wickedapp.rokidtg
adb shell logcat -c
adb shell am start -W -n com.wickedapp.rokidtg/.MainActivity
```

Then interact with WickedMan:

1. Enter WickedMan chat.
2. Confirm log has initial load only:

```text
GetChatHistory ... limit=5 -> ... inserted=5
```

3. Enter message active mode.
4. Press/swipe Up until crossing top boundary.
5. Confirm logs show one older load per boundary:

```text
olderLoad start selected=0 size=5
GetChatHistory ... limit=5 -> ... inserted=5
olderLoad rendered insertedBeforePreviousFirst=5 target=4 size=10
```

6. Use UIAutomator dumps after each Up:

```bash
adb shell uiautomator dump /sdcard/wm-scroll.xml
adb pull /sdcard/wm-scroll.xml /tmp/wm-scroll.xml
```

Expected:

- Native app remains foreground.
- Focus is on a message card, not Back/Pin/Mute.
- Visible messages change one item at a time.
- No multi-second freeze.
- No `limit=50` or `limit=100` history loads.

---

### Task 7: Commit and push only after verification

**Objective:** Preserve a known-good state.

**Commands:**

```bash
cd /Volumes/DATA/Development/hermes-glass-bridge
git status --short
git diff --stat
git add rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/data/MessageRepo.kt \
        rokid-telegram-native/app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatFragment.kt
git commit -m "Fix lazy message history scrolling on Rokid TG"
git push
```

---

## Acceptance Criteria

- [ ] Fresh chat entry does not load 100 messages.
- [ ] Fresh chat entry loads at most 5 new history rows, aside from already cached service data.
- [ ] Every upward boundary load requests exactly 5 older messages.
- [ ] WickedMan upward scrolling no longer freezes or appears stuck.
- [ ] Focus remains on message item in active message mode.
- [ ] Pin/Mute/Back are not focusable during message active mode.
- [ ] Media message Enter action still works after lazy prepend.
- [ ] Build and unit tests pass.
- [ ] APK installed on Rokid and verified with logcat + UIAutomator evidence.

---

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| TDLib returns fewer than 5 because no older history exists | Treat as end-of-history; keep focus at first loaded row and do not loop. |
| Existing service-scoped repo already has more than 5 cached rows | Do not clear cache destructively; lazy model applies to new repo sessions. If exact latest-5 on every entry is mandatory, add a separate bounded viewport layer instead of clearing TDLib cache. |
| DiffUtil content equality is too broad/narrow | Use row `id` for identity and Kotlin data class equality for content. |
| `smoothScrollToPosition` still feels slow | After proving correctness, consider `scrollToPositionWithOffset(target, desiredOffset)` instead of smooth scroll. Do not change both at once. |
| Coroutine timing still races collector | Use collector-based prepend detection, not post-return flags. |

---

## Do Not Do

- Do not reintroduce 50/100 bulk loads.
- Do not use fixed pixel `smoothScrollBy` for message movement.
- Do not infer selected message from visible rows.
- Do not commit before physical-device verification.
- Do not change Dictate/Reply/Header behavior in this fix.
