# Smart Call Secretary Playbook

## Trigger
- "Who is calling?", "Answer call", "Reject call", "Driving mode on"

## Goal
Manage incoming phone calls, announce caller ID, allow hands-free voice pickup or rejection, and automatically handle driving mode courtesies.

## Execution Sequence
1. When incoming phone call rings:
   - Resolve caller identity using `READ_PHONE_STATE` and `READ_CONTACTS`.
   - Announce: "Sir, incoming call from [Caller Name / Number]."
2. When user says "Answer" or "Uthao":
   - Invoke `handle_phone_call(action='answer')`.
3. When user says "Decline" or "Reject":
   - Invoke `handle_phone_call(action='decline')`.
4. If Driving Mode is active:
   - Auto-decline incoming calls and dispatch polite SMS: "Sir is currently driving and will get back to you shortly."
