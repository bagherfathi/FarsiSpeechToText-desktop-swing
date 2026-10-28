package com.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.mail.*;
import javax.mail.internet.*;

/** Durable local outbox. Failed jobs remain available for explicit retry. */
final class UploadQueue {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transcript-upload"); t.setDaemon(true); return t;
    });
    static boolean enabled() { return Boolean.parseBoolean(AppSettings.get("upload.enabled", "true")); }
    static void enqueue(String transcript, Path media, Consumer<String> status) throws IOException {
        if (!enabled()) return;
        Path outbox = AppSettings.dataDirectory().resolve("outbox");
        Files.createDirectories(outbox);
        Path job = Files.createTempDirectory(outbox, ".pending-");
        try {
            // Snapshot the exact source; later changes to the original cannot change this job.
            Files.copy(media, job.resolve("media-" + media.getFileName()));
            Files.writeString(job.resolve("transcript.txt"), transcript);
            Path ready = job.resolveSibling(UUID.randomUUID().toString());
            Files.move(job, ready);
            WORKER.submit(() -> send(ready, status));
        } catch (IOException ex) {
            deleteJob(job);
            throw ex;
        }
    }
    static void retry(Consumer<String> status) {
        if (!enabled()) { status.accept("upload.disabled"); return; }
        WORKER.submit(() -> {
            Path outbox = AppSettings.dataDirectory().resolve("outbox");
            if (!Files.isDirectory(outbox)) return;
            try (var jobs = Files.list(outbox)) {
                for (Path job : jobs.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).toList())
                    send(job, status);
            } catch (IOException ex) { status.accept("upload.failed"); }
        });
    }
    private static void send(Path job, Consumer<String> status) {
        if (!Files.isDirectory(job)) return;
        try {
            String host = AppSettings.get("smtp.host", "");
            String to = AppSettings.get("smtp.to", "");
            String from = AppSettings.get("smtp.from", "");
            String user = AppSettings.get("smtp.user", "");
            String overridePassword = System.getenv(AppSettings.get("smtp.password.env", "CHALABI_SMTP_PASSWORD"));
            String password = overridePassword == null || overridePassword.isBlank()
                    ? AppSettings.get("smtp.password", "") : overridePassword;
            if (host.isBlank() || to.isBlank() || from.isBlank() || user.isBlank() || password == null || password.isBlank())
                throw new IOException("SMTP configuration incomplete");
            Path media;
            try (var files = Files.list(job)) {
                media = files.filter(p -> p.getFileName().toString().startsWith("media-")).findFirst().orElseThrow();
            }
            long limit = Long.parseLong(AppSettings.get("upload.max.media.bytes", "18000000"));
            if (Files.size(media) > limit) throw new IOException("Attachment exceeds configured SMTP limit");
            Properties props = new Properties();
            props.setProperty("mail.smtp.host", host);
            props.setProperty("mail.smtp.port", AppSettings.get("smtp.port", "587"));
            props.setProperty("mail.smtp.auth", "true");
            props.setProperty("mail.smtp.starttls.enable", "true");
            props.setProperty("mail.smtp.ssl.trust", host);
            props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            props.setProperty("mail.smtp.connectiontimeout", "30000");
            props.setProperty("mail.smtp.timeout", "30000");
            props.setProperty("mail.smtp.writetimeout", "60000");
            Session session = Session.getInstance(props, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(user, password); }
            });
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
            message.setSubject("Chalabi transcription " + job.getFileName(), "UTF-8");
            MimeMultipart multipart = new MimeMultipart();
            MimeBodyPart text = new MimeBodyPart();
            text.setText(Files.readString(job.resolve("transcript.txt")), "UTF-8");
            multipart.addBodyPart(text);
            MimeBodyPart attachment = new MimeBodyPart();
            attachment.attachFile(media.toFile());
            attachment.setFileName(media.getFileName().toString().substring(6));
            multipart.addBodyPart(attachment);
            MimeBodyPart transcript = new MimeBodyPart();
            transcript.attachFile(job.resolve("transcript.txt").toFile());
            multipart.addBodyPart(transcript);
            message.setContent(multipart);
            Transport.send(message);
            deleteJob(job);
            status.accept("upload.sent");
        } catch (Exception ex) { status.accept("upload.failed"); }
    }
    private static void deleteJob(Path job) throws IOException {
        try (var children = Files.list(job)) {
            for (Path child : children.toList()) Files.deleteIfExists(child);
        }
        Files.deleteIfExists(job);
    }
}
