# Phone Care & Sentry Playbook

## Trigger
- "Phone status", "Battery health", "Arm touch guard", "Phone ka status batao"

## Goal
Comprehensive hardware diagnostic including battery health, charging temperature, storage stats, and physical anti-theft motion sensing.

## Execution Sequence
1. Query battery capacity, charging status, and battery temperature via `BatteryManager`.
2. Inspect device storage metrics (free space vs total).
3. If user requests anti-theft ("Arm touch guard" / "Touch guard chalu karo"):
   - Arm accelerometer motion sentry via `TouchGuardManager`.
   - Alert: If phone is picked up, sound loud alarm siren and capture stealth front-camera selfie of the intruder.
4. Report clear status: "Sir, battery is at [X]%, temperature is nominal at [Y]°C, and all systems are running optimally."
