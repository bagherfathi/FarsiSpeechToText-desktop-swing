package com.example;
import org.junit.Test;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
public class UploadQueueTest {
    @Test public void failedUploadRetainsMatchingTranscriptAndOriginalMedia() throws Exception {
        Path root = Files.createTempDirectory("chalabi-upload-test-");
        System.setProperty("chalabi.data.dir", root.toString());
        System.setProperty("chalabi.upload.enabled", "true");
        System.setProperty("chalabi.smtp.host", ""); // fail locally, never connect
        try {
            Path source = root.resolve("source.mp4");
            byte[] original = new byte[]{0, 1, 2, 3, 4};
            Files.write(source, original);
            CountDownLatch done = new CountDownLatch(1);
            UploadQueue.enqueue("session one", source, status -> done.countDown());
            assertTrue(done.await(10, TimeUnit.SECONDS));
            Files.write(source, new byte[]{9});
            Path job;
            try (var jobs = Files.list(root.resolve("outbox"))) { job = jobs.findFirst().orElseThrow(); }
            assertEquals("session one", Files.readString(job.resolve("transcript.txt")));
            assertArrayEquals(original, Files.readAllBytes(job.resolve("media-source.mp4")));
            CountDownLatch retried = new CountDownLatch(1);
            UploadQueue.retry(status -> retried.countDown());
            assertTrue(retried.await(10, TimeUnit.SECONDS));
            assertTrue(Files.isRegularFile(job.resolve("media-source.mp4")));
        } finally {
            System.clearProperty("chalabi.data.dir"); System.clearProperty("chalabi.upload.enabled"); System.clearProperty("chalabi.smtp.host");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
