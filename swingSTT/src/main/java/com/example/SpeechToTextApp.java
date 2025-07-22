package com.example;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
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
    private boolean isRecording = false;
    private boolean isPlaceholderSet = true;
    private Thread audioThread;
    private Model voskModel;
    private Recognizer recognizer;
    private ResourceBundle messages;
    private Locale currentLocale;
    private static final String[] MODEL_NAMES = {
            "vosk-model-fa-0.42",
//            "vosk-model-fa-0.5",
//            "vosk-model-small-fa-0.4",
            "vosk-model-small-fa-0.42",
//            "vosk-model-small-fa-0.5"
    };
    private static final java.util.List<String> REQUIRED_MODEL_FILES = Arrays.asList(
            "am/final.mdl",
            "am/tree",
            "conf/mfcc.conf",
            "conf/model.conf",
            "graph/disambig_tid.int",
            "graph/Gr.fst",
            "graph/HCLr.fst",
            "graph/phones/word_boundary.int",
            "ivector/final.dubm",
            "ivector/final.ie",
            "ivector/final.mat",
            "ivector/global_cmvn.stats",
            "ivector/online_cmvn.conf",
            "ivector/splice.conf"
    );

    public SpeechToTextApp() {
        // Initialize locale and messages
        currentLocale = Locale.ENGLISH;
        messages = ResourceBundle.getBundle("Messages", currentLocale);

        // Set up GUI
        setTitle(messages.getString("window.title"));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 400);
        setLayout(new BorderLayout());

        // Transcription display
        transcriptionArea = new JTextArea(messages.getString("transcription.placeholder"));
        transcriptionArea.setEditable(false);
        transcriptionArea.setLineWrap(true);
        transcriptionArea.setWrapStyleWord(true);
        transcriptionArea.setFont(new Font("Arial", Font.PLAIN, 14));
        add(new JScrollPane(transcriptionArea), BorderLayout.CENTER);

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout());
        toggleButton = new JButton(messages.getString("button.start"));
        toggleButton.addActionListener(e -> toggleRecording());
        shareButton = new JButton(messages.getString("button.share"));
        shareButton.addActionListener(e -> shareTranscription());
        saveButton = new JButton(messages.getString("button.save"));
        saveButton.addActionListener(e -> saveTranscription());

        // File browser components
        browseButton = new JButton(messages.getString("button.browse"));
        browseButton.addActionListener(e -> browseFile());
        filePathField = new JTextField(20);
        filePathField.setEditable(false);
        convertButton = new JButton(messages.getString("button.convert"));
        convertButton.addActionListener(e -> convertMp3ToText());
        convertButton.setEnabled(false); // Disabled until a file is selected

        // Language selector
        JLabel languageLabel = new JLabel(messages.getString("label.language"));
        languageComboBox = new JComboBox<>(new String[]{
                messages.getString("menu.language.english"),
                messages.getString("menu.language.farsi")
        });
        languageComboBox.addActionListener(e -> changeLanguage());

        // Model selector
        JLabel modelLabel = new JLabel("Model:");
        java.util.List<String> validModels = getValidModels();
        if (validModels.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No valid Vosk models found in resources.", "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        modelComboBox = new JComboBox<>(validModels.toArray(new String[0]));
        modelComboBox.addActionListener(e -> changeModel());

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

        // Set initial orientation (LTR for English)
        applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        setLocationRelativeTo(null);

        // Initialize Vosk after GUI setup
        initializeVosk();
    }

    private java.util.List<String> getValidModels() {
        java.util.List<String> validModels = new ArrayList<>();
        for (String modelName : MODEL_NAMES) {
            String resourcePath = "model/" + modelName + "/am/final.mdl"; // Check a critical file
            if (getClass().getClassLoader().getResource(resourcePath) != null) {
                validModels.add(modelName);
            } else {
                System.err.println("Model " + modelName + " is invalid: missing " + resourcePath);
            }
        }
        return validModels;
    }

    private void initializeVosk() {
        try {
            LibVosk.setLogLevel(LogLevel.DEBUG);
            String selectedModel = (String) modelComboBox.getSelectedItem();
            loadModel(selectedModel);
        } catch (Exception e) {
            String errorMessage = "Error initializing Vosk: " + (e.getMessage() != null ? e.getMessage() : "Unknown error");
            System.err.println(errorMessage);
            e.printStackTrace();
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
        System.out.println("Loading Vosk model: " + modelName + " from: " + modelPath);
        voskModel = new Model(modelPath);
        recognizer = new Recognizer(voskModel, 16000.0f);
    }

    private void changeModel() {
        if (isRecording) {
            toggleRecording(); // Stop recording to safely switch model
        }
        try {
            String selectedModel = (String) modelComboBox.getSelectedItem();
            loadModel(selectedModel);
            SwingUtilities.invokeLater(() -> {
                transcriptionArea.setText(messages.getString("transcription.placeholder"));
                isPlaceholderSet = true;
            });
        } catch (Exception e) {
            String errorMessage = "Error loading model: " + (e.getMessage() != null ? e.getMessage() : "Unknown error");
            System.err.println(errorMessage);
            e.printStackTrace();
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

        // Try to dynamically detect model files
        java.util.List<String> modelFiles = findModelFiles(resourcePath);
        if (modelFiles.isEmpty()) {
            // Fallback to required files
            modelFiles = REQUIRED_MODEL_FILES.stream()
                    .map(file -> resourcePath + "/" + file)
                    .collect(Collectors.toList());
        }

        System.out.println("Extracting model files from resources: " + resourcePath);
        for (String file : modelFiles) {
            String resourceFile = file;
            System.out.println("Attempting to access: " + resourceFile);
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceFile)) {
                if (in == null) {
                    System.err.println("Skipping missing resource: " + resourceFile);
                    continue;
                }
                Path targetPath = modelDir.resolve(resourceFile.substring((resourcePath + "/").length()));
                Files.createDirectories(targetPath.getParent());
                Files.copy(in, targetPath);
                targetPath.toFile().deleteOnExit();
                System.out.println("Extracted: " + resourceFile + " to " + targetPath);
            }
        }

        // Verify critical files
        Path finalMdl = modelDir.resolve("am/final.mdl");
        if (!Files.exists(finalMdl)) {
            throw new IOException("Critical file missing for model: " + resourcePath + "/am/final.mdl");
        }
    }

    private java.util.List<String> findModelFiles(String basePath) {
        java.util.List<String> files = new ArrayList<>();
        try {
            // Normalize basePath to ensure trailing slash
            String normalizedBasePath = basePath.endsWith("/") ? basePath : basePath + "/";
            // Use ClassLoader to list resources
            java.util.Enumeration<java.net.URL> resources = getClass().getClassLoader().getResources(normalizedBasePath);
            while (resources.hasMoreElements()) {
                java.net.URL resourceUrl = resources.nextElement();
                // Handle JAR and directory cases
                if (resourceUrl.getProtocol().equals("file")) {
                    // Running from directory (e.g., target/classes)
                    File dir = new File(resourceUrl.toURI());
                    if (dir.isDirectory()) {
                        Files.walk(dir.toPath())
                                .filter(Files::isRegularFile)
                                .map(path -> normalizedBasePath + dir.toPath().relativize(path).toString().replace("\\", "/"))
                                .forEach(files::add);
                    }
                } else if (resourceUrl.getProtocol().equals("jar")) {
                    // Running from JAR
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
            System.err.println("Error scanning model files: " + e.getMessage());
            e.printStackTrace();
        }
        return files;
    }

    private void browseFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(messages.getString("button.browse"));
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MP3 Files", "mp3"));
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

        File mp3File = new File(filePath);
        if (!mp3File.exists()) {
            JOptionPane.showMessageDialog(this, messages.getString("error.file_not_found"));
            return;
        }

        convertButton.setEnabled(false);
        transcriptionArea.setText(messages.getString("message.converting"));
        isPlaceholderSet = false;

        new Thread(() -> {
            try {
                // Convert MP3 to WAV
                File wavFile = convertMp3ToWav(mp3File);
                // Transcribe WAV
                String transcription = transcribeWavFile(wavFile);
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.setText(transcription);
                    convertButton.setEnabled(true);
                    JOptionPane.showMessageDialog(this, messages.getString("message.conversion_complete"));
                });
                // Clean up temporary WAV file
                wavFile.delete();
            } catch (Exception e) {
                String errorMessage = messages.getString("error.conversion_failed") + ": " + e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.setText(errorMessage);
                    convertButton.setEnabled(true);
                    JOptionPane.showMessageDialog(this, errorMessage);
                });
                e.printStackTrace();
            }
        }).start();
    }

    private File convertMp3ToWav(File mp3File) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("audio");
        File wavFile = new File(tempDir.toFile(), "converted.wav");
        wavFile.deleteOnExit();

        // Get the application directory (JAR or .exe location)
        String appDir;
        try {
            appDir = new File(SpeechToTextApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IOException("Failed to resolve application directory", e);
        }

        // Try multiple FFmpeg paths
        java.util.List<String> ffmpegPaths = Arrays.asList(
                new File(appDir, "tools\\ffmpeg\\bin\\ffmpeg.exe").getAbsolutePath(), // App-image or .exe
                new File(appDir, "..\\tools\\ffmpeg\\bin\\ffmpeg.exe").getAbsolutePath(), // JAR in target/
                "ffmpeg" // System PATH
        );

        String ffmpegPath = null;
        for (String path : ffmpegPaths) {
            System.out.println("Checking FFmpeg path: " + path);
            if (path.equals("ffmpeg") || new File(path).isFile()) {
                ffmpegPath = path;
                System.out.println("Selected FFmpeg path: " + ffmpegPath);
                break;
            }
        }

        if (ffmpegPath == null) {
            throw new IOException("FFmpeg executable not found. Ensure it is in tools\\ffmpeg\\bin relative to the application or in system PATH.");
        }

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-i", mp3File.getAbsolutePath(),
                "-ar", "16000",
                "-ac", "1",
                "-acodec", "pcm_s16le",
                wavFile.getAbsolutePath()
        );
        pb.directory(new File(appDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("FFmpeg: " + line);
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

    private void toggleRecording() {
        if (!isRecording) {
            if (voskModel == null || recognizer == null) {
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.setText("Vosk not initialized properly");
                    isPlaceholderSet = false;
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
        }
    }

    private void startAudioCapture() {
        audioThread = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();

                byte[] buffer = new byte[4096];
                while (isRecording) {
                    int bytesRead = line.read(buffer, 0, buffer.length);
                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                        String result = recognizer.getResult();
                        String text = parseVoskResult(result);
                        if (!text.isEmpty()) {
                            SwingUtilities.invokeLater(() -> {
                                transcriptionArea.append(text + "\n");
                                transcriptionArea.setCaretPosition(transcriptionArea.getDocument().getLength());
                                isPlaceholderSet = false;
                            });
                        }
                    }
                }
                line.stop();
                line.close();
                String finalResult = recognizer.getFinalResult();
                String finalText = parseVoskResult(finalResult);
                if (!finalText.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        transcriptionArea.append(finalText + "\n");
                        transcriptionArea.setCaretPosition(transcriptionArea.getDocument().getLength());
                        isPlaceholderSet = false;
                    });
                }
            } catch (LineUnavailableException e) {
                SwingUtilities.invokeLater(() -> {
                    transcriptionArea.append("Audio error: " + e.getMessage() + "\n");
                    isPlaceholderSet = false;
                });
                e.printStackTrace();
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
                e.printStackTrace();
            }
        }
    }

    private String parseVoskResult(String result) {
        try {
            if (result.contains("\"text\"")) {
                String text = result.split("\"text\"\\s*:\\s*\"")[1];
                text = text.split("\"")[0];
                return text;
            }
        } catch (Exception e) {
            // Ignore parsing errors
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
                e.printStackTrace();
            }
        }
    }

    private void changeLanguage() {
        String selected = (String) languageComboBox.getSelectedItem();
        if (selected.equals(messages.getString("menu.language.farsi"))) {
            currentLocale = new Locale("fa");
        } else {
            currentLocale = Locale.ENGLISH;
        }

        messages = ResourceBundle.getBundle("Messages", currentLocale);
        setTitle(messages.getString("window.title"));
        toggleButton.setText(isRecording ? messages.getString("button.stop") : messages.getString("button.start"));
        shareButton.setText(messages.getString("button.share"));
        saveButton.setText(messages.getString("button.save"));
        browseButton.setText(messages.getString("button.browse"));
        convertButton.setText(messages.getString("button.convert"));
        if (isPlaceholderSet) {
            transcriptionArea.setText(messages.getString("transcription.placeholder"));
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

        revalidate();
        repaint();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new SpeechToTextApp().setVisible(true);
        });
    }
}