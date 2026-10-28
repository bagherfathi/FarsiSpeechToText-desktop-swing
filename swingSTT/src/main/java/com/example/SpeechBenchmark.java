package com.example;

import org.vosk.*;
import javax.sound.sampled.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Offline benchmark: never sends transcripts or audio. Input must be 16kHz mono PCM WAV. */
public final class SpeechBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: SpeechBenchmark MODEL_DIR AUDIO.wav REFERENCE.txt");
        String reference = Files.readString(Path.of(args[2])).trim();
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        try (Model model = new Model(args[0])) {
            for (boolean legacy : new boolean[]{true, false}) {
                long start = System.nanoTime();
                String result = recognize(model, Path.of(args[1]), legacy);
                double seconds = (System.nanoTime() - start) / 1e9;
                System.out.printf(Locale.ROOT, "%s\tWER=%.4f\tCER=%.4f\tseconds=%.3f\t%s%n",
                        legacy ? "legacy-peak-gain" : "original-pcm", wer(reference, result), cer(reference, result), seconds, result);
            }
        }
    }
    static String recognize(Model model, Path wav, boolean legacy) throws Exception {
        StringBuilder result = new StringBuilder();
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile());
             Recognizer decoder = new Recognizer(model, 16000)) {
            AudioFormat f = audio.getFormat();
            if (f.getSampleRate() != 16000 || f.getChannels() != 1 || f.getSampleSizeInBits() != 16
                    || f.isBigEndian() || !f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED))
                throw new IllegalArgumentException("Expected 16kHz mono signed 16-bit little endian PCM");
            byte[] buffer = new byte[2048];
            int count;
            while ((count = audio.read(buffer)) != -1) {
                if (legacy) peakGain(buffer, count);
                if (decoder.acceptWaveForm(buffer, count)) result.append(RecognitionText.fromJson(decoder.getResult())).append(' ');
            }
            result.append(RecognitionText.fromJson(decoder.getFinalResult()));
        }
        return result.toString().trim();
    }
    private static void peakGain(byte[] bytes, int count) {
        ShortBuffer samples = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int max = 0;
        for (int i = 0; i < samples.limit(); i++) max = Math.max(max, Math.abs((int)samples.get(i)));
        if (max > 0 && max < 30000) {
            double gain = 32760.0 / max;
            for (int i = 0; i < samples.limit(); i++) samples.put(i, (short)Math.max(-32768, Math.min(32767, samples.get(i) * gain)));
        }
    }
    static String normalize(String text) {
        return text.replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ').trim().replaceAll("\\s+", " ");
    }
    static double wer(String reference, String hypothesis) {
        String ref = normalize(reference), hyp = normalize(hypothesis);
        return rate(ref.isEmpty() ? new String[0] : ref.split(" "), hyp.isEmpty() ? new String[0] : hyp.split(" "));
    }
    static double cer(String reference, String hypothesis) {
        return rate(normalize(reference).replace(" ", "").codePoints().mapToObj(c -> new String(Character.toChars(c))).toArray(String[]::new),
                normalize(hypothesis).replace(" ", "").codePoints().mapToObj(c -> new String(Character.toChars(c))).toArray(String[]::new));
    }
    private static double rate(String[] ref, String[] hyp) {
        int[] previous = new int[hyp.length + 1];
        for (int j = 0; j <= hyp.length; j++) previous[j] = j;
        for (int i = 1; i <= ref.length; i++) {
            int[] next = new int[hyp.length + 1]; next[0] = i;
            for (int j = 1; j <= hyp.length; j++) next[j] = Math.min(previous[j] + 1,
                    Math.min(next[j-1] + 1, previous[j-1] + (ref[i-1].equals(hyp[j-1]) ? 0 : 1)));
            previous = next;
        }
        return (double)previous[hyp.length] / Math.max(1, ref.length);
    }
}
