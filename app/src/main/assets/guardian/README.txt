Voice Guardian assets — three files, three jobs:

    ecapa6s.tflite    ~42 MB   speaker embedding: raw waveform [1,96000] -> [1,192]
    silero_vad.tflite 650 KB   "is this human speech?" gate — Silero VAD v4, driven
                               frame-by-frame with its LSTM state (see SpeechGate.kt)
    cohort.bin        150 KB   200 stranger voiceprints for AS-Norm score
                               normalisation (no audio, just 192-float vectors)

The window is 6 s, not 3 s, and it is filled with CONCATENATED VOICED AUDIO — never
wall-clock audio, and never zero-padded. Measured at 5 dB SNR on LibriSpeech:

    window / content                        EER
    3 s contiguous (the old build)         2.50%
    6 s contiguous                         1.35%
    6 s holding only 2 s of speech        17.14%   <- why silence is stripped
    6 s of concatenated speech             1.43%
    1.5 s of speech, zero-padded to 6 s   30.00%   <- why short speech is repeated
    1.5 s of speech, repeated to fill 6 s  1.43%

Swapping the embedding model was tried and abandoned: CAM++, ResNet34/152 and
ReDimNet-B2 all measured the same as ECAPA once the window matched, so window
length — not architecture — is what drives accuracy here.

Regenerate them with:

    python scripts/convert_ecapa_tflite.py            (raw-waveform model, recommended)
    python scripts/convert_ecapa_tflite.py --mode fbank   (fallback)
    python scripts/build_cohort.py                    (cohort.bin + calibration constants)

    python scripts/convert_silero_tflite.py            (silero_vad.tflite)

The Silero source model lives at scripts/models/silero_vad.onnx — outside assets/ on
purpose, so only the converted .tflite is packaged. It is downloaded as-is from:
    https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx

Each degrades gracefully on its own:
  - no ecapa6s.tflite -> the Guardian is fully inert; Maya runs exactly as before.
  - no silero_vad    -> the speech gate falls back to its DSP stage (energy + zero-
                        crossing rate). Weaker, but ambient noise is still filtered.
  - no cohort.bin    -> scores stay raw cosines and the accept threshold is scaled
                        down to suit them (VoiceGuardian.RAW_FALLBACK_SCALE).

Embeddings are model-specific, and the asset stem is stored on every profile as its
model id — which is why the 6 s model is named ecapa6s.tflite rather than replacing
ecapa.tflite in place: profiles enrolled against the old 3 s model simply stop
matching instead of scoring garbage against an incompatible window. After swapping
the model — or rebuilding cohort.bin, whose logistic constants live in
ScoreNormalizer.kt — re-enroll every voice (Settings -> Voice Guardian -> Remove,
then Record again).

The Android side (voice/guardian/SpeakerEmbedder.kt) auto-detects whether the
model takes a raw waveform [1, N] or 80-dim log-mel features [1, T, 80], so either
conversion mode works.
