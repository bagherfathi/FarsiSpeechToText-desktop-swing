package com.example;
import org.junit.Test;
import java.nio.file.Path;
import static org.junit.Assert.*;
public class WhisperBackendTest {
    @Test public void commandPreservesPathsWithSpacesWithoutShellInterpolation() {
        System.setProperty("chalabi.whisper.executable", "tools with spaces/whisper-cli.exe");
        System.setProperty("chalabi.whisper.model", "models with spaces/model.bin");
        try {
            var args = WhisperBackend.command(Path.of("audio with spaces.wav"), Path.of("result"));
            assertEquals("tools with spaces/whisper-cli.exe", args.get(0));
            assertEquals("models with spaces/model.bin", args.get(2));
            assertTrue(args.contains("fa"));
            assertTrue(args.contains("-otxt"));
            assertFalse(args.contains("--translate"));
        } finally { System.clearProperty("chalabi.whisper.executable"); System.clearProperty("chalabi.whisper.model"); }
    }
}
