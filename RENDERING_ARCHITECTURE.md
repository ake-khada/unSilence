# Unified Rendering Architecture

## Overview

All event rendering across feed, profile, thread, search, and notification
screens now flows through a shared set of abstractions instead of per-screen
copy-paste. The architecture follows an Amethyst-inspired pattern of
normalized event handling with context-driven rendering.

## Layer Stack

```
Screen (FeedScreen, ProfileScreen, ...)
  └── eventFeedItems()              ← shared LazyListScope extension
        ├── EventActionCallbacks    ← bundles all action lambdas
        ├── EngagementSnapshot      ← bundles reactedIds/repostedIds/zappedIds
        ├── VideoPlaybackScope      ← shared video state (replaces 80+ dup lines)
        └── NoteCard / ArticleCard  ← existing card composables (unchanged API)

NotificationsScreen
  └── NotificationEventRow          ← shared notification renderer
        └── CompactNotePreview      ← embedded note content in notification style
```

## Key Files

| File | Purpose |
|------|---------|
| `ui/shared/RenderContext.kt` | Enum: Feed, Thread, Profile, Search, Notification, Quote |
| `ui/shared/VideoPlaybackScope.kt` | Consolidated video state: claim/release, lifecycle, mute, active detection |
| `ui/shared/EventFeedItems.kt` | `eventFeedItems()` LazyListScope extension + `EventActionCallbacks` + `EngagementSnapshot` |
| `ui/shared/NotificationEventRow.kt` | Unified notification row with compact embedded note preview |

## RenderContext Controls

| Context | Inline Video | Actions | Media Grid | Compact |
|---------|-------------|---------|------------|---------|
| Feed | Yes | Full | Full | No |
| Profile | Yes | Full | Full | No |
| Thread | No | Full | Full | No |
| Search | No | Full | Full | No |
| Notification | No | No | No | Yes |
| Quote | No | No | No | Yes |

## Video Architecture

Single `SharedPlayerHolder` (Hilt singleton) owns one ExoPlayer instance.
`VideoPlaybackScope` manages per-screen state:

1. **Ownership**: `claim(ownerId)` / `releaseOwnership(ownerId)` via DisposableEffect
2. **Lifecycle**: Pause on ON_PAUSE, resume on ON_RESUME
3. **Mute sync**: Global per-scope, synced to ExoPlayer.volume
4. **Active detection**: snapshotFlow on LazyListState, debounced 300ms
5. **Playback transitions**: Media source swap (no PlayerView recreation)
6. **Fullscreen**: Reuses same ExoPlayer, preserves position and mute state

### SurfaceView Stability

The `key(videoUrl)` wrapper that previously wrapped `AndroidView(PlayerView)`
has been removed. This was the root cause of surface churn — every URL change
destroyed and recreated the SurfaceView. Now the PlayerView instance is stable
and media sources are swapped via `ExoPlayer.setMediaItem()`.

## Hydration Architecture

Single visibility-driven hydration path via `snapshotFlow` in FeedScreen.
The duplicate `collectLatest` hydration in FeedViewModel has been removed.
`CardHydrator.hydrateVisibleCards()` is idempotent via `hydratedIds` set.

## Migration Status

| Screen | Before | After |
|--------|--------|-------|
| FeedScreen | 80 lines video state + inline items | `rememberVideoPlaybackScope` + `eventFeedItems` |
| ProfileScreen | 80 lines video state + inline items | `rememberVideoPlaybackScope` + `eventFeedItems` |
| UserProfileScreen | 80 lines video state + inline items | `rememberVideoPlaybackScope` + `eventFeedItems` |
| SearchScreen | Inline items block | `eventFeedItems` (no video) |
| ThreadScreen | Direct NoteCard calls | Unchanged (depth indentation is screen-specific) |
| NotificationsScreen | Custom `NotificationItem` | `NotificationEventRow` with `CompactNotePreview` |
