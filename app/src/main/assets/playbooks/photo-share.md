# Quick Photo Share Playbook

## Trigger
- "Share last photo with [Contact]", "Photo bhejo", "Screenshot send karo"

## Goal
Locates the most recent picture or screenshot in the MediaStore and shares it directly with the requested contact on WhatsApp or Telegram.

## Execution Sequence
1. Query `MediaStore.Images.Media` sorted by `DATE_ADDED DESC` to locate the latest photo or screenshot.
2. Resolve target contact via `READ_CONTACTS`.
3. Construct `ACTION_SEND` intent with image URI and package name `com.whatsapp`.
4. Trigger send button automatically via accessibility grounding if direct intent requires tap.
5. Confirm: "Sir, I have shared your latest photo with [Contact]."
