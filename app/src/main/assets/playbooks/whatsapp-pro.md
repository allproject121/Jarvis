# WhatsApp Pro Secretary Playbook

## Trigger
- "Send WhatsApp message to [Contact]", "WhatsApp padho", "Reply to [Contact] on WhatsApp"

## Goal
Execute WhatsApp operations with zero friction, zero unnecessary confirmation prompts, and verify delivery.

## Execution Sequence
1. For sending a message:
   - Resolve contact name or phone number via `search_contacts(name=...)`.
   - Dispatch the message immediately using `execute_connected_app_action(app='WhatsApp', action_type='send_message', ...)` or accessibility auto-typing.
   - Confirm verbally: "Sir, I have sent the message to [Contact]."
2. For reading unread messages:
   - Call `reply_to_message()` or read notification listener stream.
   - Announce sender and summarize or speak the message text.
   - Offer to reply instantly hands-free.
