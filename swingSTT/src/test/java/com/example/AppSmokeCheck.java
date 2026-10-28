package com.example;
import javax.swing.*;
import java.lang.reflect.Field;
public final class AppSmokeCheck {
    private static Object field(Object app, String name) throws Exception {
        Field field = SpeechToTextApp.class.getDeclaredField(name); field.setAccessible(true); return field.get(app);
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("chalabi.upload.enabled", "false");
        SpeechToTextApp[] app = new SpeechToTextApp[1];
        try {
            SwingUtilities.invokeAndWait(() -> app[0] = new SpeechToTextApp());
            long deadline = System.nanoTime() + 90_000_000_000L;
            boolean[] ready = {false};
            while (!ready[0] && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> {
                    try { ready[0] = ((JComboBox<?>)field(app[0], "backendComboBox")).isEnabled(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                Thread.sleep(100);
            }
            if (!ready[0]) throw new AssertionError("Model load did not complete");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JComboBox<?> language = (JComboBox<?>)field(app[0], "languageComboBox");
                    language.setSelectedIndex(0);
                    if (!((JButton)field(app[0], "toggleButton")).getText().equals("Start Recording")) throw new AssertionError("English switch failed");
                    language.setSelectedIndex(1);
                    JTextArea text = (JTextArea)field(app[0], "transcriptionArea");
                    if (!text.isEditable()) throw new AssertionError("Transcript not editable");
                    if (text.getAccessibleContext().getAccessibleName().contains("?")) throw new AssertionError("Broken Persian label");
                    if (app[0].getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("control R")) == null)
                        throw new AssertionError("Missing recording shortcut");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            System.out.println("PASS: model loading, language switching, editable transcript, accessible Persian label, recording shortcut");
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (app[0] != null) app[0].dispose(); });
        }
        System.exit(0);
    }
}
