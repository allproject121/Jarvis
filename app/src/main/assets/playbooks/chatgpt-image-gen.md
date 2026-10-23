# Autonomous AI Image & Video Generator Playbook

## Trigger
- "Generate image of [Prompt]", "ChatGPT me image banao", "Gemini video banao"

## Goal
Automate ChatGPT or Gemini app via autonomous UI grounding macros to generate art/videos and download them directly into the device Gallery.

## Execution Sequence
1. Check `builtin_macros.json` for target app ("chatgpt image" or "gemini image").
2. Open target app by package name.
3. Tap "New chat" or clear existing prompt box.
4. Type prompt with human typing rhythm.
5. Tap "Send" or "Generate".
6. Wait for "Creating image" or progress indicator to disappear.
7. Open share menu and tap "Download" or "Save to Device".
8. Return to home screen and confirm: "Sir, your generated image has been saved to Gallery."
