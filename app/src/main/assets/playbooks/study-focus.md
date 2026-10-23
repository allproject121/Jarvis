# Deep Study & Focus Sentry Playbook

## Trigger
- "Focus mode on", "Study mode", "Padhai ka time", "Silence phone for 1 hour"

## Goal
Creates an uninterrupted productivity bubble: silences notifications, sets exact countdown timer, dims distraction, and plays ambient focus audio.

## Execution Sequence
1. Enable Do Not Disturb / Notification filter via `NotificationManager`.
2. Schedule a countdown timer for requested duration (default: 45 minutes) via `SCHEDULE_EXACT_ALARM`.
3. Optionally launch ambient focus music (lo-fi, binaural beats, or rain sounds) on Spotify or YouTube.
4. Confirm: "Sir, deep focus protocol initiated for [Duration] minutes. All non-critical interruptions are silenced."
