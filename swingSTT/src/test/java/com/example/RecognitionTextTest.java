package com.example;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecognitionTextTest {
    @Test public void decodesEscapesAndPersian() {
        String expected = "سلام \"دنیا\"";
        assertEquals(expected, RecognitionText.fromJson(new com.google.gson.Gson().toJson(java.util.Map.of("text", expected))));
        assertEquals("سلام", RecognitionText.fromJson("{\"text\":\"\\u0633\\u0644\\u0627\\u0645\"}"));
    }
    @Test public void toleratesMissingInvalidOrNonTextResults() {
        for (String input : new String[]{null, "", "{", "null", "[]", "{\"partial\":\"hello\"}", "{\"text\":null}", "{\"text\":42}"})
            assertEquals("", RecognitionText.fromJson(input));
    }
    @Test public void metricsCountSubstitutionDeletionAndInsertion() {
        assertEquals(1.0 / 3, SpeechBenchmark.wer("one two three", "one four three"), 1e-8);
        assertEquals(1.0 / 3, SpeechBenchmark.wer("one two three", "one three"), 1e-8);
        assertEquals(1.0 / 3, SpeechBenchmark.wer("one two three", "one extra two three"), 1e-8);
        assertEquals(0, SpeechBenchmark.wer("علي كار", "علی کار"), 0);
        assertEquals(0, SpeechBenchmark.wer("", ""), 0);
    }
}
