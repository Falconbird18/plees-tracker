# Experimental phone sleep-stage estimates

Plees offers three modes in Settings → Sleep sensing:

- **Mattress motion:** place the phone flat on the mattress near the pillow, uncovered and ventilated. The accelerometer samples movement at approximately 10 Hz. Gravity is removed with a low-pass filter; the remaining motion magnitude is aggregated.
- **Nightstand sound levels:** microphone samples at 16 kHz are reduced immediately to RMS sound levels. Raw PCM is overwritten in a reusable memory buffer. No audio clips are stored, transmitted, or analyzed for speech. This is passive ambient-level sensing, not sonar.
- **Simple timer:** no microphone, movement sensing, or sensor wake lock. The original start/stop workflow remains available.

The large button is the only content in the Sleep tab. The Night page coordinates one selected rest’s stage ring, timeline, and goals. History contains aggregate charts and the journal. Tap a journal entry or chart bar to open a night; “Notes & edit this rest” opens the editor. Missing sensor intervals remain unavailable in both the ring and stacked bars.

## What the labels mean

Awake, light, deep, and REM are **experimental estimates**, not measured physiological stages. The independently implemented classifier combines relative activity, variance, screen interaction, and broad time-of-night priors. Deep/REM labels therefore partly reflect a model of a typical night. There is no claimed clinical validation, measured accuracy, or ability to diagnose sleep disorders. Quiet wakefulness, a partner, pets, music, fans, microphone muting, phone placement, and mattress type can change the results. Do not use these estimates for medical decisions.

At least ten minutes of usable epochs are required before a breakdown is produced. Missing time, sparse samples, all-zero input, and interrupted recording remain unavailable. Older records are not assigned invented stages. Editing a record clips its existing epochs to the new times; any newly added time stays unavailable.

## Storage and lifecycle

Only 30-second aggregates (time, activity mean/variance, sample count, screen-interaction flag) are retained. A foreground service displays an ongoing notification. Microphone permission is requested only for sound mode. Both sensor modes use a partial wake lock and more battery than the simple timer. Sensor collection stops at 16 hours; the manual sleep timer continues. Android/OEM power restrictions can still interrupt capture, and these gaps are not filled in.

Completed epochs are saved atomically in app-private, non-backed-up scratch storage so recording can resume after process interruption. The aggregates are attached to the sleep row on stop, then scratch data is removed. Deleting a sleep removes its aggregates; swipe Undo restores them with the record.

Database migration 5 → 6 adds a default-empty `sensor_data` column without replacing prior records. The additional trailing CSV column preserves sensor aggregates in CSV, folder, and Drive backups. Existing CSVs still import. Calendar export and Health Connect retain their original session-only behavior: the unvalidated estimated stages are not uploaded as clinical stages.

## Reference reviewed

[OpenSleep](https://github.com/timmyy123/opensleep) and its [explanation of phone sensing](https://www.opensleep.tech/blog/how-phones-detect-sleep-with-an-accelerometer) were reviewed for the general approach. Its Android implementation uses mattress actigraphy or active ultrasonic sonar and a heuristic hypnogram pipeline. Plees does not copy that source or its bundled decompiled reference material. Plees uses an independent, simpler classifier and passive sound-level sensing. Neither a clock-based stage model nor sensor collection establishes physiological accuracy.

## Verification limits

Unit tests cover missing/short/all-zero data, gap preservation, awake cues, interval clipping, exact duration accounting, backup round-trips, and interrupted capture recovery. Render tests inspect the actual Android views. Real-device overnight battery, microphone, motion-sensitivity, and stage-accuracy validation are still needed; JVM tests cannot establish them.
