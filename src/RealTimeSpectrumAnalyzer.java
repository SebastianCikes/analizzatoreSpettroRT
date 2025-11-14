import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.jtransforms.fft.DoubleFFT_1D;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Un analizzatore di spettro audio in tempo reale utilizzando JavaFX e JTransforms.
 * Cattura l'audio, esegue una FFT e visualizza lo spettro con barre logaritmiche e peak hold.
 */
public class RealTimeSpectrumAnalyzer extends Application {

    // --- Costanti Audio ---
    private static final float SAMPLE_RATE = 44100;
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1; // Mono
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;

    // --- Costanti FFT ---
    private static final int FFT_SIZE = 2048; // Dimensione del buffer FFT
    private static final int NUM_BARS = 100; // Quante barre visualizzare
    private static final double INPUT_GAIN = 0.01; // Abbassa il volume (0.01 = -40dB)

    // --- Costanti di Visualizzazione ---
    private static final double CANVAS_WIDTH = 1200;
    private static final double CANVAS_HEIGHT = 600;
    private static final double MIN_DB = -60.0; // dB minimi da visualizzare (taglio)
    private static final double MAX_DB = 20.0;  // dB massimi da visualizzare
    private static final long PEAK_HOLD_DURATION_MS = 1000; // 1 secondo

    // --- Componenti JavaFX ---
    private Canvas canvas;
    private GraphicsContext gc;
    private ComboBox<MixerItem> mixerComboBox;

    // --- Elaborazione Audio ---
    private TargetDataLine audioLine;
    private volatile boolean isRunning = true;
    private DoubleFFT_1D fft;
    private double[] window;
    private double[] fftBuffer;

    // --- Costanti EQ ---
    // Lista di High Shelves cumulativi
    private List<EqShelf> eqShelves;

    // --- Dati per la Visualizzazione ---
    private double[] magnitudesDB;
    private double[] peakLevels; // Livelli di picco per ogni barra
    private long[] peakHoldTimestamps; // Timestamp per la caduta del picco
    private double[] logarithmicBars;

    /**
     * Classe interna per definire un High Shelf EQ
     */
    private static class EqShelf {
        final double freqHz;
        final double gainDb;
        EqShelf(double freqHz, double gainDb) {
            this.freqHz = freqHz;
            this.gainDb = gainDb;
        }
    }


    /**
     * Metodo principale - Lancia l'applicazione JavaFX.
     */
    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Metodo start di JavaFX - Imposta la finestra principale.
     */
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Real-Time Spectrum Analyzer");

        // Inizializza gli array prima di avviare il thread audio
        // per evitare NullPointerException
        fft = new DoubleFFT_1D(FFT_SIZE);
        fftBuffer = new double[FFT_SIZE * 2]; // JTransforms richiede 2*N per dati reali
        window = createWindow(FFT_SIZE);
        magnitudesDB = new double[FFT_SIZE / 2];
        logarithmicBars = new double[NUM_BARS];
        peakLevels = new double[NUM_BARS];
        peakHoldTimestamps = new long[NUM_BARS];

        // --- Inizializza la lista di EQ ---
        eqShelves = new ArrayList<>();
        eqShelves.add(new EqShelf(400.0, 2.0));    // +2dB sopra 400 Hz
        eqShelves.add(new EqShelf(1000.0, 2.0));   // +2dB sopra 1000 Hz
        eqShelves.add(new EqShelf(5000.0, 2.0));   // +2dB sopra 1000 Hz
        // --- Fine EQ ---

        // Inizializza i picchi al livello minimo
        for (int i = 0; i < NUM_BARS; i++) {
            peakLevels[i] = MIN_DB;
            logarithmicBars[i] = MIN_DB; // <-- AGGIUNTO: Inizializza anche le barre.
            peakHoldTimestamps[i] = 0;
        }

        // Imposta l'interfaccia utente
        VBox root = new VBox();
        mixerComboBox = new ComboBox<>();
        setupDeviceComboBox();

        canvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        gc = canvas.getGraphicsContext2D();

        root.getChildren().addAll(mixerComboBox, canvas);
        Scene scene = new Scene(root, CANVAS_WIDTH, CANVAS_HEIGHT);

        primaryStage.setScene(scene);
        primaryStage.show();

        // Listener per cambiare dispositivo
        mixerComboBox.setOnAction(e -> startAudioCapture());

        // Avvia la cattura audio iniziale
        startAudioCapture();

        // Assicurati che il thread audio si fermi alla chiusura
        primaryStage.setOnCloseRequest(e -> {
            isRunning = false;
            if (audioLine != null) {
                audioLine.stop();
                audioLine.close();
            }
        });
    }

    /**
     * Popola il ComboBox con i dispositivi di input audio disponibili.
     * Prova a preselezionare "CABLE Output" o "Stereo Mix".
     */
    private void setupDeviceComboBox() {
        ObservableList<MixerItem> mixerItems = FXCollections.observableArrayList();
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        MixerItem preferredMixer = null;
        MixerItem fallbackMixer = null;
        MixerItem firstMixer = null;

        for (Mixer.Info info : mixerInfos) {
            try {
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.getTargetLineInfo().length > 0) {
                    MixerItem item = new MixerItem(info);
                    mixerItems.add(item);

                    if (firstMixer == null) {
                        firstMixer = item;
                    }

                    String nameLower = info.getName().toLowerCase();

                    // Cerca prima il cavo virtuale
                    if (nameLower.contains("cable") || nameLower.contains("vb-audio")) {
                        preferredMixer = item;
                    }

                    // Cerca lo stereo mix come fallback
                    if (preferredMixer == null && (nameLower.contains("mix") || nameLower.contains("stereo") || nameLower.contains("quel che senti"))) {
                        fallbackMixer = item;
                    }
                }
            } catch (Exception e) {
                // Ignora i dispositivi che non supportano l'input
            }
        }

        mixerComboBox.setItems(mixerItems);

        // Imposta la selezione preferita
        if (preferredMixer != null) {
            mixerComboBox.setValue(preferredMixer);
        } else if (fallbackMixer != null) {
            mixerComboBox.setValue(fallbackMixer);
        } else if (firstMixer != null) {
            mixerComboBox.setValue(firstMixer);
        }
    }

    /**
     * Avvia (o riavvia) la cattura audio dal dispositivo selezionato.
     */
    private void startAudioCapture() {
        // Ferma il thread precedente se esiste
        isRunning = false;
        if (audioLine != null) {
            audioLine.stop();
            audioLine.close();
        }

        MixerItem selectedItem = mixerComboBox.getValue();
        if (selectedItem == null) return;

        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
            Mixer mixer = AudioSystem.getMixer(selectedItem.getInfo());
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!mixer.isLineSupported(info)) {
                System.err.println("Dispositivo non supportato: " + selectedItem);
                return;
            }

            audioLine = (TargetDataLine) mixer.getLine(info);
            audioLine.open(format, FFT_SIZE * 2); // Apri con un buffer più grande
            audioLine.start();

            isRunning = true;
            Thread audioThread = new Thread(this::audioCaptureLoop);
            audioThread.setDaemon(true);
            audioThread.start();

        } catch (LineUnavailableException e) {
            e.printStackTrace();
        }
    }

    /**
     * Loop principale di cattura audio. Legge i dati, li processa e richiede il disegno.
     */
    private void audioCaptureLoop() {
        byte[] byteBuffer = new byte[FFT_SIZE * 2]; // 16 bit = 2 byte per campione
        double[] audioSamples = new double[FFT_SIZE];

        while (isRunning) {
            try {
                int bytesRead = audioLine.read(byteBuffer, 0, byteBuffer.length);

                if (bytesRead > 0) {
                    // Converte i byte in campioni double (e applica gain)
                    for (int i = 0, j = 0; i < FFT_SIZE && j < bytesRead; i++, j += 2) {
                        int sample = (byteBuffer[j + 1] << 8) | (byteBuffer[j] & 0xFF);
                        audioSamples[i] = (sample / 32768.0) * INPUT_GAIN;
                    }

                    // Elabora i campioni
                    processAudio(audioSamples);

                    // Richiedi il disegno sul thread JavaFX
                    Platform.runLater(this::drawSpectrum);
                }
            } catch (Exception e) {
                if (isRunning) {
                    e.printStackTrace();
                }
                break;
            }
        }
    }

    /**
     * Elabora un blocco di campioni audio: applica finestra, esegue FFT, calcola dB.
     */
    private void processAudio(double[] audioSamples) {
        // Applica la finestra di Hann ai campioni
        for (int i = 0; i < FFT_SIZE; i++) {
            fftBuffer[i] = audioSamples[i] * window[i];
        }

        // Esegui FFT (dati reali)
        fft.realForward(fftBuffer);

        // Frequenza per ogni "bin" della FFT
        double binWidth = SAMPLE_RATE / FFT_SIZE;

        // Calcola le magnitudini in dB
        for (int i = 0; i < FFT_SIZE / 2; i++) {
            // jTransforms memorizza i risultati in modo compattato
            double real = fftBuffer[i * 2];
            double imag = (i == 0 || i == FFT_SIZE / 2 - 1) ? 0 : fftBuffer[i * 2 + 1];
            double magnitude = Math.sqrt(real * real + imag * imag);

            // Converti in dB
            magnitudesDB[i] = 20 * Math.log10(magnitude + 0.000001); // Evita log(0)

            // --- NUOVA LOGICA HIGH-SHELF (Lista flessibile) ---
            double freq = i * binWidth;

            // Applica tutti gli shelf cumulativi
            for (EqShelf shelf : eqShelves) {
                if (freq >= shelf.freqHz) {
                    magnitudesDB[i] += shelf.gainDb;
                }
            }
            // --- FINE NUOVA LOGICA ---
        }

        // Raggruppa in barre logaritmiche
        getLogarithmicBars(magnitudesDB);
    }

    /**
     * Raggruppa i risultati della FFT (magnitudesDB) in barre logaritmiche (logarithmicBars).
     * Questo metodo include la logica di media.
     */
    private void getLogarithmicBars(double[] magnitudes) {
        double minLogFreq = Math.log10(20); // Frequenza minima (20 Hz)
        double maxLogFreq = Math.log10(SAMPLE_RATE / 2); // Frequenza massima (Nyquist)
        double logRange = maxLogFreq - minLogFreq;

        // Frequenza per ogni "bin" della FFT
        double[] binFrequencies = new double[magnitudes.length];
        for (int i = 0; i < magnitudes.length; i++) {
            binFrequencies[i] = (double) i * SAMPLE_RATE / FFT_SIZE;
        }

        int[] binCounts = new int[NUM_BARS];
        double[] barSum = new double[NUM_BARS];

        // Inizializza somme e conteggi
        for (int i = 0; i < NUM_BARS; i++) {
            barSum[i] = 0.0;
            binCounts[i] = 0;
        }

        // Mappa i bin della FFT alle barre logaritmiche
        for (int i = 1; i < magnitudes.length; i++) { // Inizia da 1 per evitare log(0)
            if (binFrequencies[i] < 20) continue; // Ignora sotto 20 Hz

            double logFreq = Math.log10(binFrequencies[i]);
            int barIndex = (int) (((logFreq - minLogFreq) / logRange) * NUM_BARS);

            if (barIndex >= 0 && barIndex < NUM_BARS) {
                barSum[barIndex] += magnitudes[i];
                binCounts[barIndex]++;
            }
        }

        // Calcola la media e aggiorna l'array delle barre
        for (int i = 0; i < NUM_BARS; i++) {
            if (binCounts[i] > 0) {
                logarithmicBars[i] = barSum[i] / binCounts[i];
            } else {
                // Se nessun bin è caduto in questa barra (comune nei bassi)
                // "prende in prestito" il valore dalla barra precedente
                // per evitare "buchi neri".
                if (i > 0) {
                    // Usa il valore della barra precedente (già calcolata)
                    logarithmicBars[i] = logarithmicBars[i - 1];
                } else {
                    // La prima barra non ha precedenti, quindi va al minimo.
                    logarithmicBars[i] = MIN_DB;
                }
            }

            // Logica Peak Hold
            long now = System.currentTimeMillis();
            if (logarithmicBars[i] >= peakLevels[i]) {
                // Nuovo picco
                peakLevels[i] = logarithmicBars[i];
                peakHoldTimestamps[i] = now;
            } else {
                // Il picco non è stato superato, controlla se è scaduto
                if (now - peakHoldTimestamps[i] > PEAK_HOLD_DURATION_MS) {
                    // Scaduto. Fa cadere il picco al livello attuale
                    peakLevels[i] = logarithmicBars[i];
                    peakHoldTimestamps[i] = now;
                }
                // Se non è scaduto, peakLevels[i] mantiene il suo valore alto
            }
        }
    }


    /**
     * Disegna l'intero spettro sul canvas (griglia, etichette e barre).
     */
    private void drawSpectrum() {
        // Pulisci il canvas
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

        // Disegna la griglia e le etichette
        drawGridAndLabels();

        // Disegna le barre dello spettro (semi-trasparenti)
        double barWidth = CANVAS_WIDTH / NUM_BARS;
        // Colore verde con 65% di opacità
        gc.setFill(new Color(0.1, 1.0, 0.1, 0.65));

        for (int i = 0; i < NUM_BARS; i++) {
            double x = i * barWidth;
            double y = getYForDecibels(logarithmicBars[i]);
            double height = CANVAS_HEIGHT - y;

            if (height < 0) height = 0;

            gc.fillRect(x, y, barWidth, height);
        }

        // Disegna i picchi (linee rosse sottili)
        gc.setStroke(Color.RED);
        gc.setLineWidth(1.5);

        for (int i = 0; i < NUM_BARS; i++) {
            double x = i * barWidth;
            double yPeak = getYForDecibels(peakLevels[i]);

            // Disegna una linea orizzontale per il picco
            if(yPeak > 0 && yPeak < CANVAS_HEIGHT) { // Disegna solo se è visibile
                gc.strokeLine(x, yPeak, x + barWidth, yPeak);
            }
        }
    }

    /**
     * Disegna la griglia di sfondo e le etichette per Frequenza e dB.
     */
    private void drawGridAndLabels() {
        // gc.setStroke(Color.rgb(80, 80, 80)); // Grigio scuro per la griglia
        gc.setFill(Color.rgb(150, 150, 150)); // Grigio chiaro per il testo
        gc.setFont(Font.font("Monospaced", 10));
        // gc.setLineWidth(0.5);

        // --- Griglia Decibel (Linee Orizzontali) ---
        for (double db = MIN_DB; db <= MAX_DB; db += 10) {
            double y = getYForDecibels(db);

            // --- MODIFICA: Linea 0dB blu e più spessa ---
            if (Math.abs(db - 0.0) < 0.1) { // Controllo per 0 dB
                gc.setStroke(Color.BLUE);
                gc.setLineWidth(1.0);
            } else {
                gc.setStroke(Color.rgb(80, 80, 80)); // Grigio scuro per le altre
                gc.setLineWidth(0.5);
            }
            // --- FINE MODIFICA ---

            gc.strokeLine(0, y, CANVAS_WIDTH, y);

            // Etichetta dB
            gc.setFill(Color.rgb(150, 150, 150)); // Assicura colore testo
            gc.fillText(String.format("%+3.0f dB", db), 5, y - 2);
        }

        // Reset spessore linea per griglia frequenza
        gc.setLineWidth(0.5);

        // --- Griglia Frequenza (Logaritmica) ---
        // Stile per linee principali (10, 100, 1k, 10k)
        Color mainLineColor = Color.rgb(100, 100, 100);
        // Stile per linee secondarie (20, 30... 200, 300...)
        Color subLineColor = Color.rgb(60, 60, 60);

        for (int decade = 1; decade <= 4; decade++) { // 10Hz, 100Hz, 1kHz, 10kHz
            double decadeStart = Math.pow(10, decade);

            for (int i = 1; i <= 9; i++) {
                double freq = decadeStart * i;
                if (freq > SAMPLE_RATE / 2) break; // Non superare Nyquist

                double x = getXForFrequency(freq);

                // Linee principali (10, 100, 1k, 10k)
                if (i == 1) {
                    gc.setStroke(mainLineColor);
                    gc.setLineWidth(1.0);

                    String label;
                    if (freq < 1000) {
                        label = String.format("%.0f", freq);
                    } else {
                        label = String.format("%.0fk", freq / 1000);
                    }
                    gc.fillText(label, x + 2, 12); // <-- MODIFICATO: Spostato in alto (era CANVAS_HEIGHT - 5)

                } else { // Linee secondarie
                    gc.setStroke(subLineColor);
                    gc.setLineWidth(0.5);

                    // Etichetta per il "5" (50, 500, 5k)
                    if (i == 5) {
                        String label;
                        if (freq < 1000) {
                            label = String.format("%.0f", freq);
                        } else {
                            label = String.format("%.0fk", freq / 1000);
                        }
                        gc.fillText(label, x + 2, 12); // <-- MODIFICATO: Spostato in alto (era CANVAS_HEIGHT - 5)
                    }
                }
                gc.strokeLine(x, 0, x, CANVAS_HEIGHT);
            }
        }
    }

    // --- Metodi di Utilità ---

    /**
     * Converte un valore in dB in una coordinata Y sul canvas.
     */
    private double getYForDecibels(double db) {
        // Mappa linearmente [MIN_DB, MAX_DB] a [CANVAS_HEIGHT, 0]
        double range = MAX_DB - MIN_DB;
        if (db < MIN_DB) db = MIN_DB;
        if (db > MAX_DB) db = MAX_DB;
        return CANVAS_HEIGHT - ((db - MIN_DB) / range) * CANVAS_HEIGHT;
    }

    /**
     * Converte una frequenza in una coordinata X sul canvas (scala log).
     */
    private double getXForFrequency(double freq) {
        double minLogFreq = Math.log10(20); // 20 Hz
        double maxLogFreq = Math.log10(SAMPLE_RATE / 2); // Nyquist
        double logRange = maxLogFreq - minLogFreq;

        if (freq < 20) return 0;

        double logFreq = Math.log10(freq);
        return ((logFreq - minLogFreq) / logRange) * CANVAS_WIDTH;
    }


    /**
     * Crea una finestra di Hann per smussare i bordi del campione audio.
     */
    private double[] createWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (size - 1)));
        }
        return window;
    }

    /**
     * Classe helper per mostrare nomi leggibili nel ComboBox dei mixer.
     */
    private static class MixerItem {
        private Mixer.Info info;

        public MixerItem(Mixer.Info info) {
            this.info = info;
        }

        public Mixer.Info getInfo() {
            return info;
        }

        @Override
        public String toString() {
            // Es. "CABLE Output (VB-Audio Virtual Cable), version 3.0"
            return String.format("%s, version %s", info.getName(), info.getVersion());
        }
    }
}