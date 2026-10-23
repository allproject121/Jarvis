# Daily Briefing Playbook

## Trigger
- "Good morning Jarvis", "Daily briefing", "Aaj ka update", "Morning report"

## Goal
Deliver a comprehensive, articulate morning intelligence briefing covering battery status, local weather & AQI, unread notifications, and scheduled tasks in a single natural spoken response.

## Execution Sequence
1. Check device battery percentage and charging state via `get_battery_status()`.
2. Retrieve current location, temperature, condition, and Air Quality Index via `get_live_weather_and_aqi()`.
3. Check for any pending reminders or calendar events scheduled for today via `get_scheduled_tasks()`.
4. Check for high-priority unread WhatsApp messages via `whatsapp_read_messages()`.
5. Speak the aggregated morning update in articulate, polite Hinglish addressing the user as Sir.
