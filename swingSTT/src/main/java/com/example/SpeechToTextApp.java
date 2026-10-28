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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import java.time.Year;
import java.util.stream.Collectors;


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
    private volatile boolean isRecording = false;
    private volatile TargetDataLine captureLine;
    private boolean isConverting;
    private boolean isLoading = true;
    private JComboBox<String> backendComboBox;
    private JLabel statusLabel;
    private boolean changingLanguage;
    private SwingWorker<?, ?> conversionWorker;
    private boolean isPlaceholderSet = true;
    private Thread audioThread;
    private Model voskModel;
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
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
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
        transcriptionArea.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusGained(java.awt.event.FocusEvent event) {
                if (isPlaceholderSet) { transcriptionArea.setText(""); isPlaceholderSet = false; }
            }
        });
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
        JLabel modelLabel = new JLabel(messages.getString("label.model"));
        modelLabel.setLabelFor(modelComboBox);
        modelLabel.setFont(new Font("Vazir", Font.PLAIN, 14));
        java.util.List<String> validModels = getValidModels();
        if (validModels.isEmpty()) {
            validModels.addAll(modelDisplayToName.keySet());
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

        backendComboBox = new JComboBox<>(new String[]{"Vosk", "Whisper"});
        backendComboBox.getAccessibleContext().setAccessibleName(messages.getString("label.engine"));
        controlPanel.add(new JLabel(messages.getString("label.engine")));
        controlPanel.add(backendComboBox);
        statusLabel = new JLabel(messages.getString("status.loading"));
        statusLabel.getAccessibleContext().setAccessibleName(messages.getString("label.status"));
        JPanel north = new JPanel(new BorderLayout());
        north.add(statusLabel, BorderLayout.CENTER);
        JButton retry = new JButton(messages.getString("button.retry_upload"));
        retry.addActionListener(e -> UploadQueue.retry(this::uploadStatus));
        north.add(retry, BorderLayout.LINE_END);
        add(north, BorderLayout.NORTH);
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
        configureAccessibility();
        updateBusyControls();
        loadModelAsync(currentModelName);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopAudioCapture();
                Thread pending = audioThread;
                SwingWorker<?, ?> pendingConversion = conversionWorker;
                isConverting = true;
                updateBusyControls();
                new Thread(() -> {
                    try {
                        if (pending != null) pending.join();
                        if (pendingConversion != null) pendingConversion.get();
                    } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    catch (java.util.concurrent.ExecutionException ignored) { }
                    SwingUtilities.invokeLater(() -> { dispose(); System.exit(0); });
                }, "application-close").start();
            }
        });
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
        modelDisplayToName = new LinkedHashMap<>();
        modelDisplayToName.put(messages.getString("model.precise"), decrypt(MODEL_NAMES[0]));
        modelDisplayToName.put(messages.getString("model.fast"), decrypt(MODEL_NAMES[1]));
        for (String name : new String[]{"vosk-model-fa-0.5", "vosk-model-small-fa-0.5", "vosk-model-small-fa-0.4"})
            modelDisplayToName.put(name, name);
        modelNameToDisplay = new LinkedHashMap<>();
        modelDisplayToName.forEach((display, name) -> modelNameToDisplay.put(name, display));
    }

    private java.util.List<String> getValidModels() {
        java.util.List<String> validModels = new ArrayList<>();
        for (String modelName : modelDisplayToName.values()) {
            String resourcePath = "model/" + modelName + "/" + decrypt(REQUIRED_MODEL_FILES.get(0));
            if (externalModel(modelName) != null || getClass().getClassLoader().getResource(resourcePath) != null) {
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

    private Path externalModel(String modelName) {
        java.util.List<Path> roots = new ArrayList<>();
        String configured = AppSettings.get("models.dir", "");
        if (!configured.isBlank()) roots.add(Path.of(configured));
        roots.add(Path.of("model"));
        roots.add(Path.of("src", "main", "resources", "model"));
        try {
            Path code = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            roots.add(code.getParent().resolve("model"));
        } catch (Exception ignored) { }
        for (Path root : roots) {
            Path candidate = root.resolve(modelName);
            if (Files.isRegularFile(candidate.resolve("am/final.mdl"))) return candidate;
        }
        return null;
    }

    private void loadModel(String modelName) throws IOException {
        Path modelPath = externalModel(modelName);
        if (modelPath == null) {
            Path tempDir = Files.createTempDirectory("vosk-model");
            tempDir.toFile().deleteOnExit();
            extractModelFromJar("model/" + modelName, tempDir);
            modelPath = tempDir.resolve(modelName);
        }
        Model replacement = new Model(modelPath.toString());
        if (voskModel != null) voskModel.close();
        voskModel = replacement;
    }

    private void loadModelAsync(String modelName) {
        isLoading = true;
        statusLabel.setText(messages.getString("status.loading"));
        updateBusyControls();
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                loadModel(modelName);
                return null;
            }
            protected void done() {
                try {
                    get();
                    currentModelName = modelName;
                    statusLabel.setText(messages.getString("status.ready"));
                } catch (Exception ex) {
                    modelComboBox.setSelectedItem(modelNameToDisplay.get(currentModelName));
                    statusLabel.setText(messages.getString("status.model_failed"));
                    JOptionPane.showMessageDialog(SpeechToTextApp.this,
                            messages.getString("status.model_failed") + ": " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
                isLoading = false;
                updateBusyControls();
            }
        }.execute();
    }

    private void changeModel() {
        if (isLoading || isRecording || isConverting || audioThread != null) return;
        String selected = getModelNameFromDisplayName((String) modelComboBox.getSelectedItem());
        if (selected != null && !selected.equals(currentModelName)) loadModelAsync(selected);
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
                        try (var paths = Files.walk(dir.toPath())) {
                            paths.filter(Files::isRegularFile)
                                    .map(path -> normalizedBasePath + dir.toPath().relativize(path).toString().replace("\\", "/"))
                                    .forEach(files::add);
                        }
                    }
                } else if (resourceUrl.getProtocol().equals("jar")) {
                    java.net.JarURLConnection connection = (java.net.JarURLConnection) resourceUrl.openConnection();
                    connection.setUseCaches(false);
                    try (java.util.jar.JarFile jar = connection.getJarFile()) {
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
        if (isLoading || isRecording || isConverting || audioThread != null) return;
        File mediaFile = new File(filePathField.getText());
        if (!mediaFile.isFile()) {
            JOptionPane.showMessageDialog(this, messages.getString("error.file_not_found"));
            return;
        }
        final boolean whisper = "Whisper".equals(backendComboBox.getSelectedItem());
        isConverting = true;
        updateBusyControls();
        statusLabel.setText(messages.getString("message.converting"));
        conversionWorker = new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception {
                File wav = convertMediaToWav(mediaFile);
                try {
                    String text = whisper ? WhisperBackend.transcribe(wav.toPath()) : transcribeWavFile(wav);
                    queueUpload(text, mediaFile.toPath());
                    return text;
                } finally { deleteTemporaryWav(wav.toPath()); }
            }
            protected void done() {
                isConverting = false;
                try {
                    String text = get();
                    transcriptionArea.setText(text);
                    isPlaceholderSet = false;
                    statusLabel.setText(messages.getString("message.conversion_complete"));
                    incrementCounter();
                } catch (Exception ex) {
                    statusLabel.setText(messages.getString("error.conversion_failed"));
                    JOptionPane.showMessageDialog(SpeechToTextApp.this, ex.getMessage(),
                            messages.getString("error.conversion_failed"), JOptionPane.ERROR_MESSAGE);
                }
                conversionWorker = null;
                updateBusyControls();
            }
        };
        conversionWorker.execute();
    }

    private boolean queueUpload(String text, Path media) {
        if (text.isBlank() || !UploadQueue.enabled()) return false;
        try { UploadQueue.enqueue(text, media, this::uploadStatus); return true; }
        catch (IOException ex) { uploadStatus("upload.queue_failed"); return false; }
    }

    private void uploadStatus(String key) {
        SwingUtilities.invokeLater(() -> {
            String text = messages.getString(key);
            statusLabel.setText(text);
            if (key.equals("upload.failed") || key.equals("upload.queue_failed"))
                JOptionPane.showMessageDialog(this, text);
        });
    }

    private static void deleteTemporaryWav(Path wav) throws IOException {
        Files.deleteIfExists(wav);
        Files.deleteIfExists(wav.getParent());
    }

    private File convertMediaToWav(File mediaFile) throws IOException, InterruptedException {
        return MediaConverter.convert(mediaFile.toPath()).toFile();
    }

    private String transcribeWavFile(File wavFile) throws Exception {
        if (voskModel == null) throw new IllegalStateException("Model not initialized");
        StringBuilder transcription = new StringBuilder();
        // A decoder belongs to one recording only; never reuse the microphone decoder.
        try (Recognizer fileRecognizer = new Recognizer(voskModel, 16000.0f);
             AudioInputStream wavStream = AudioSystem.getAudioInputStream(wavFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = wavStream.read(buffer)) != -1) {
                if (bytesRead > 0 && fileRecognizer.acceptWaveForm(buffer, bytesRead)) {
                    appendResult(transcription, fileRecognizer.getResult());
                }
            }
            appendResult(transcription, fileRecognizer.getFinalResult());
        }
        return transcription.toString();
    }

    private void appendResult(StringBuilder target, String json) {
        String text = parseVoskResult(json);
        if (!text.isEmpty()) target.append(text).append("\n");
    }

    private void toggleRecording() {
        if (isConverting || isLoading) return;
        if (isRecording) { stopAudioCapture(); return; }
        if (audioThread != null) return;
        final boolean whisper = "Whisper".equals(backendComboBox.getSelectedItem());
        if (!whisper && voskModel == null) {
            JOptionPane.showMessageDialog(this, messages.getString("status.model_failed"));
            return;
        }
        isRecording = true;
        toggleButton.setText(messages.getString("button.stop"));
        statusLabel.setText(messages.getString("status.recording"));
        audioThread = new Thread(() -> captureAudio(whisper), "microphone-capture");
        audioThread.start();
        updateBusyControls();
    }

    private void captureAudio(boolean whisper) {
        Path directory = null;
        boolean queued = false;
        StringBuilder sessionText = new StringBuilder();
        try {
            Path recordings = AppSettings.dataDirectory().resolve("recordings");
            Files.createDirectories(recordings);
            directory = Files.createTempDirectory(recordings, "session-");
            Path pcm = directory.resolve("recording.pcm");
            Path wav = directory.resolve("recording.wav");
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            TargetDataLine line = openMicrophone(info, format);
            captureLine = line;
            try (OutputStream audio = Files.newOutputStream(pcm);
                 Recognizer decoder = whisper ? null : new Recognizer(voskModel, 16000.0f)) {
                if (isRecording) line.start();
                byte[] buffer = new byte[2048];
                while (isRecording) {
                    int count;
                    try { count = line.read(buffer, 0, buffer.length); }
                    catch (IllegalStateException ex) { if (isRecording) throw ex; else break; }
                    if (count <= 0) break;
                    audio.write(buffer, 0, count);
                    // Keep the original PCM levels; avoid amplifying silence/noise.
                    if (decoder != null && decoder.acceptWaveForm(buffer, count)) {
                        String text = parseVoskResult(decoder.getResult());
                        appendSession(sessionText, text);
                        appendTranscription(text);
                    }
                }
                if (decoder != null) {
                    String text = parseVoskResult(decoder.getFinalResult());
                    appendSession(sessionText, text);
                    appendTranscription(text);
                }
            } finally { line.stop(); line.close(); captureLine = null; }
            writePcmWave(pcm, wav, format);
            if (whisper && Files.size(pcm) > 0) {
                SwingUtilities.invokeLater(() -> statusLabel.setText(messages.getString("message.converting")));
                String text = WhisperBackend.transcribe(wav);
                appendSession(sessionText, text);
                appendTranscription(text);
            }
            Files.writeString(directory.resolve("transcript.txt"), sessionText.toString());
            queued = queueUpload(sessionText.toString(), wav);
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    messages.getString("error.recording") + ": " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
        } finally {
            TargetDataLine line = captureLine;
            captureLine = null;
            if (line != null) { line.stop(); line.close(); }
            if (directory != null) {
                try {
                    Path pcm = directory.resolve("recording.pcm");
                    Path wav = directory.resolve("recording.wav");
                    if (!Files.exists(wav) && Files.exists(pcm))
                        writePcmWave(pcm, wav, new AudioFormat(16000, 16, 1, true, false));
                    Files.deleteIfExists(pcm);
                    if (!Files.exists(directory.resolve("transcript.txt")))
                        Files.writeString(directory.resolve("transcript.txt"), sessionText.toString());
                    if (queued) {
                        Files.deleteIfExists(directory.resolve("recording.wav"));
                        Files.deleteIfExists(directory.resolve("transcript.txt"));
                        Files.deleteIfExists(directory);
                    }
                } catch (IOException ignored) { }
            }
            isRecording = false;
            SwingUtilities.invokeLater(() -> {
                audioThread = null;
                toggleButton.setText(messages.getString("button.start"));
                if (statusLabel.getText().equals(messages.getString("status.recording"))
                        || statusLabel.getText().equals(messages.getString("message.converting")))
                    statusLabel.setText(messages.getString("status.ready"));
                updateBusyControls();
            });
        }
    }

    private static void writePcmWave(Path pcm, Path wav, AudioFormat format) throws IOException {
        try (InputStream input = Files.newInputStream(pcm);
             AudioInputStream stream = new AudioInputStream(input, format, Files.size(pcm) / format.getFrameSize())) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile());
        }
    }

    private static TargetDataLine openMicrophone(DataLine.Info info, AudioFormat format) throws LineUnavailableException {
        java.util.List<Mixer> mixers = new ArrayList<>();
        mixers.add(AudioSystem.getMixer(null));
        for (Mixer.Info mixer : AudioSystem.getMixerInfo()) mixers.add(AudioSystem.getMixer(mixer));
        for (Mixer mixer : mixers) {
            if (!mixer.isLineSupported(info)) continue;
            TargetDataLine candidate = null;
            try {
                candidate = (TargetDataLine) mixer.getLine(info);
                candidate.open(format);
                return candidate;
            } catch (LineUnavailableException | IllegalArgumentException ex) {
                if (candidate != null) candidate.close();
            }
        }
        throw new LineUnavailableException("No microphone supports 16 kHz mono PCM");
    }

    private static void appendSession(StringBuilder session, String text) {
        if (!text.isBlank()) session.append(text).append("\n");
    }

    private void appendTranscription(String text) {
        if (text.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            if (isPlaceholderSet) transcriptionArea.setText("");
            isPlaceholderSet = false;
            transcriptionArea.append(text + "\n");
            transcriptionArea.setCaretPosition(transcriptionArea.getDocument().getLength());
        });
    }

    private void stopAudioCapture() {
        isRecording = false;
        toggleButton.setText(messages.getString("button.start"));
        // Close on a worker: some drivers block stop/close while a read is pending.
        TargetDataLine line = captureLine;
        if (line != null) new Thread(() -> { line.stop(); line.close(); }, "microphone-stop").start();
        updateBusyControls();
    }

    private String parseVoskResult(String result) {
        return RecognitionText.fromJson(result);
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
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                writer.write(text);
                JOptionPane.showMessageDialog(this, messages.getString("message.saved") + file.getPath());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, messages.getString("error.save_failed") + ": " + e.getMessage());
            }
        }
    }

    private void changeLanguage() {
        if (changingLanguage) return;
        String selected = (String) languageComboBox.getSelectedItem();
        if (selected == null) return;
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

        changingLanguage = true;
        languageComboBox.removeAllItems();
        languageComboBox.addItem(messages.getString("menu.language.english"));
        languageComboBox.addItem(messages.getString("menu.language.farsi"));
        languageComboBox.setSelectedIndex(newLocale.getLanguage().equals("fa") ? 1 : 0);
        changingLanguage = false;

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
        configureAccessibility();
        revalidate();
        repaint();
    }

    private void configureAccessibility() {
        transcriptionArea.setEditable(true);
        transcriptionArea.getAccessibleContext().setAccessibleName(messages.getString("accessibility.transcription"));
        filePathField.getAccessibleContext().setAccessibleName(messages.getString("accessibility.file"));
        modelComboBox.getAccessibleContext().setAccessibleName(messages.getString("label.model"));
        languageComboBox.getAccessibleContext().setAccessibleName(messages.getString("label.language"));
        toggleButton.setToolTipText(messages.getString("shortcut.record"));
        saveButton.setToolTipText(messages.getString("shortcut.save"));
        JRootPane root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control R"), "record");
        root.getActionMap().put("record", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (toggleButton.isEnabled()) toggleRecording();
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control S"), "save");
        root.getActionMap().put("save", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { saveTranscription(); }
        });
    }

    private void updateBusyControls() {
        boolean busy = isLoading || isRecording || isConverting || audioThread != null;
        backendComboBox.setEnabled(!busy);
        modelComboBox.setEnabled(!busy);
        languageComboBox.setEnabled(!busy);
        browseButton.setEnabled(!busy);
        convertButton.setEnabled(!busy && !filePathField.getText().isBlank());
        toggleButton.setEnabled(!isLoading && !isConverting && (isRecording || !busy));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new SpeechToTextApp().setVisible(true);
        });
    }
}