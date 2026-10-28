# Chalabi desktop speech recognition

Java Swing app for Persian dictation and audio/video transcription.

## Build and run

Use a JDK 18 or newer and Maven. Run `mvn test` for automated checks and `mvn package` for the Windows distribution. The build uses --release 18, matching the existing bundled Java 18 runtime. The Windows profile builds target/Chalabi_SpeechToText.exe and copies runtime, tools and external models next to it. Keep those folders together. On other platforms the Windows profile is inactive: run the JAR with Java and install native FFmpeg.

Models are excluded from the JAR, even for incremental builds. Search order: models.dir, ./model, src/main/resources/model, then the model folder beside the JAR. Legacy classpath model bundles are supported. Loading happens in the background. A failed replacement preserves the previous model. All installed models are selectable; the existing large 0.42 default is unchanged.

## Configuration

Copy chalabi.properties.example to chalabi.properties in the working directory. Alternatively pass -Dchalabi.config=/absolute/path/chalabi.properties before -jar. Override individual keys with -Dchalabi.KEY=value. Use forward slashes in property paths. Local configuration is ignored by Git.

## Optional Whisper

Install the official whisper.cpp CLI from https://github.com/ggml-org/whisper.cpp and a compatible multilingual GGML model. Configure whisper.executable and whisper.model; select Whisper in the engine dropdown. Files are converted to mono 16 kHz PCM first. Microphone recognition runs AFTER Stop, not as streaming dictation. No models download automatically. Vosk remains available for incremental dictation. Whisper runs as a separate local process without a shell, with configurable timeout. Fine-tuning and model conversion happen outside this app.

Command construction is tested; real Whisper quality has not been benchmarked in this checkout because no executable/model was supplied.

## Transcript and source media upload

The original working SMTP account, credential, sender and recipient are preserved in the ignored local resource src/main/resources/smtp-defaults.properties. Maven bundles that resource into this installation, but Git does not track it. A fresh clone needs its own local resource or SMTP configuration before it can deliver mail. Built artifacts contain your local mail settings: do not publish them. Automatic uploads are enabled by default; no new SMTP configuration or environment variable is required. The transcript now includes the original source media as an attachment. Optional smtp.* settings and an environment credential can override the existing setup; do not add blank smtp.* entries. Set upload.enabled=false only if you want to disable automatic delivery.

Successful nonempty recognition queues the transcript as UTF-8 body/text attachment, plus the original selected media file or captured microphone WAV. Each microphone upload contains only its own recognized text, not previous sessions or the full editor. The original media is snapshotted when queued.

The durable outbox is user.home/.chalabi/outbox, or data.dir/outbox. Failed jobs remain there. Retry pending uploads retries them. Successful sends remove their copies. The default source attachment limit is 18 MB to leave room for MIME/base64 overhead; oversized media remains pending. Larger media needs an appropriate server/limit or a future object-storage uploader. Delivery is at-least-once: interrupted acknowledgement or cleanup may cause duplicates.

Microphone sessions not queued (including when upload is disabled) remain under data.dir/recordings with their transcript. These are sensitive, unencrypted local files: manage retention and device access. Opening the app does not send existing jobs; retry is explicit. Benchmarks and automated tests never send email.

## Keyboard and accessibility

- Ctrl+R starts/stops recording; Ctrl+S saves UTF-8 text.
- The transcript is editable; Ctrl+Tab leaves the editor.
- Accessible labels identify transcription, selected file, engine, language, model and status.
- Recording, conversion and model switching cannot overlap.
- Original audio levels are preserved; chunk peak normalization was removed.
- Each session has its own decoder; the last utterance flushes at Stop.
- Closing waits for an active recording or conversion to preserve its data.

Actual microphone drivers and NVDA/Java Access Bridge need user testing. The UI smoke check does not prove screen-reader usability. Existing transcription-count and expiry behavior is preserved.

## Benchmark

See BENCHMARK.md for results and limitations. Example PowerShell:

    mvn test dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt"
    $speechClasspath = 'target/classes;' + (Get-Content target/runtime-classpath.txt -Raw).Trim()
    & ./tools/ffmpeg/bin/ffmpeg.exe -nostdin -y -i INPUT.wav -ar 16000 -ac 1 -c:a pcm_s16le sample.wav
    & ./JRE/bin/java.exe -cp $speechClasspath com.example.SpeechBenchmark MODEL_DIRECTORY sample.wav REFERENCE.txt

This compares old chunk gain to preserved PCM, reporting WER, CER and decode time excluding model load. Metrics normalize Persian/Arabic yeh and kaf, whitespace and zero-width non-joiners; CER ignores spaces. Punctuation and numbers are not normalized. No upload occurs. Use independent speaker-disjoint real dictation data before changing the default or claiming improved accuracy.
