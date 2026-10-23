# Universal Music DJ Playbook

## Trigger
- "Play [Song/Artist] on Spotify", "Gaana bajao", "Play music on YouTube"

## Goal
Hands-free music search and autoplay across preferred music services (Spotify, YouTube Music, Gaana, or YouTube).

## Execution Sequence
1. Extract artist or track title from user utterance.
2. Check user's preferred music app in `YouTubePreferences` or default to Spotify/YouTube.
3. Dispatch `android.media.action.MEDIA_PLAY_FROM_SEARCH` or deep-link intent.
4. If foreground playback does not auto-start, use Accessibility click to trigger the top search result.
5. Confirm: "Sir, playing [Title] for you now."
