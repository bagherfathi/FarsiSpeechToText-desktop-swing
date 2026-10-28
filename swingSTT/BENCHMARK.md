# Initial regression benchmark

Run: 2026-10-28, Windows, bundled Java 18, Vosk 0.3.45.
One supplied recording: vosk-model-small-fa-0.42/test/test.wav, converted with bundled FFmpeg to mono 16 kHz signed 16-bit PCM. Reference: the corresponding test.txt.

| Model | Old WER | Preserved PCM WER | Old CER | Preserved PCM CER |
|---|---:|---:|---:|---:|
| small-fa-0.42 | 40.00% | 40.00% | 5.56% | 5.56% |
| fa-0.42 | 40.00% | 40.00% | 5.56% | 5.56% |
| small-fa-0.5 | 40.00% | 40.00% | 11.11% | 11.11% |

This establishes no regression on this example. It does NOT establish improved accuracy or rank the models for general Persian. The five-word reference and word segmentation make WER particularly unstable. Preserving PCM avoids boosting noise/silence, but representative noisy recordings are needed to quantify the benefit.

Decode times were roughly 0.8-1.5 seconds per pass, excluding model load. Runs included concurrent work, so these are not reliable performance rankings.

Follow-up data should include conversational dictation, Persian/English mixtures, names, numbers, addresses, noise and different microphones, with speakers separated between training and test. Measure correction effort and time to final text as well as WER/CER.

No Whisper quality result is claimed: no model/runtime was supplied.
