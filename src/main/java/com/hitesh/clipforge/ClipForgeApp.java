package com.hitesh.clipforge;

import javafx.animation.*;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ClipForgeApp extends Application {
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ---- Wizard scaffolding ----
    private static final String[] STEP_NAMES = {"Get clip", "Subtitles", "Watermark", "Export"};
    private int currentStep = 0;
    private Node[] stepPanes;
    private Label[] stepDots;
    private StackPane contentHost;
    private Button backBtn, nextBtn;
    private StackPane busyOverlay;
    private Label busyLabel;
    private ProgressBar progress;
    private Label status;
    private TextArea log;

    // ---- Step 1: Extract ----
    // JavaFX's media engine reliably plays short clips but hard-stalls (reaches "ready", never
    // actually advances, no error) once content crosses somewhere between 5 and 10 minutes —
    // confirmed empirically on this machine across multiple file lengths. So long videos are
    // never loaded in full for preview; only a short window at a time is, and extraction (which
    // reads directly from the remote stream, not the preview window) is unaffected by this.
    private static final double PREVIEW_WINDOW_SECONDS = 240;
    private double totalVideoDuration;
    private double windowStartOffset = 0;
    private TextField urlField, inField, outField, jumpField;
    private Button jumpBtn;
    private Label windowInfoLabel;
    private MediaPlayer player;
    private MediaView mediaView;
    private TimelineScrubber scrubber;
    private Button playPauseBtn, loadBtn, extractBtn;
    private Node playIcon, pauseIcon;
    private boolean suppressFieldSync = false;
    private String lastLoadedUrl;
    // Two separately resolved streams: a small one used only to build the scrubbing preview
    // (fast to fetch and re-encode), and the full-quality one used only for the real extraction.
    private String videoStreamUrl, audioStreamUrl;
    private String previewVideoUrl, previewAudioUrl;
    private String currentPreviewMediaUrl;

    private static final String HQ_FORMAT =
            "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]"
            + "/bv*[height<=1080]+ba"
            + "/b[height<=1080]"
            + "/b";
    private static final String PREVIEW_FORMAT =
            "bv*[height<=480][vcodec^=avc1]+ba[ext=m4a]"
            + "/bv*[height<=480]+ba"
            + "/b[height<=480]"
            + "/wv*+wa/w";

    /** Hardware H.264 encoder that actually works on this machine (probed once), or null for libx264. */
    private volatile String hwEncoder;
    private volatile boolean hwEncoderProbed = false;
    private FileChannel workDirLockChannel;
    private FileLock workDirLock;
    private Path currentClip;

    // ---- Step 2: Subtitles ----
    private TextField fontField;
    private ComboBox<String> positionBox;
    private Spinner<Integer> fontSize;
    private ColorPicker textColor, outlineColor;
    private Button transcribeBtn;
    private TextArea transcriptPreview;
    private ComboBox<String> whisperModelBox, whisperLanguageBox, captionLengthBox;
    private Path currentSrt;
    private MediaPlayer subtitlePlayer;
    private MediaView subtitleMediaView;
    private Slider subtitleSeekSlider;
    private Label subtitleOverlayLabel;
    private Button subtitlePlayPauseBtn;
    private Node subPlayIcon, subPauseIcon;
    private ListView<SubtitleCue> cueListView;
    private TextField cueStartField, cueEndField;
    private TextArea cueTextArea;
    private List<SubtitleCue> cues = new ArrayList<>();
    private Path subtitlePreviewFor;
    private boolean suppressCueSync = false;

    private static class SubtitleCue {
        double start, end;
        String text;
        SubtitleCue(double start, double end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    // ---- Step 3: Watermark ----
    private CheckBox watermarkCheck;
    private WatermarkBoxOverlay watermarkOverlay;
    private Button captureFrameBtn, removeWatermarkBtn;
    private Label watermarkStatus;
    private Path watermarkClip;
    private MediaView watermarkResultView;
    private MediaPlayer watermarkResultPlayer;
    private Button watermarkResultPlayBtn;
    private Node wmPlayIcon, wmPauseIcon;
    private VBox watermarkResultSection;

    // ---- Step 4: Export ----
    private Label summaryClip, summarySubs, summaryWatermark;

    // ---- Shell / guidance ----
    private static final String[] NEXT_LABELS = {"Next: Subtitles", "Next: Watermark", "Next: Export", "Export video"};
    private HBox[] stepItems;
    private ScrollPane pageScroll;
    private Label toastLabel;
    private TitledPane logPane;
    private final List<Runnable> guideRefreshers = new ArrayList<>();
    private boolean rangeTouched = false, cueEdited = false, styleTouched = false;

    @Override public void start(Stage stage) {
        stage.setTitle("ClipForge");
        loadFonts();
        cleanupWorkDir();

        Node header = buildHeader();

        stepPanes = new Node[]{
                stepPage("Get your clip",
                        "Load a video from a link, choose the part you want, and cut it out. Only that part is downloaded.",
                        guide(new String[]{"Paste a link and press Load video",
                                        "Drag the green start and red end handles to the part you want",
                                        "Press Extract clip"},
                                new java.util.function.BooleanSupplier[]{() -> videoStreamUrl != null,
                                        () -> rangeTouched || currentClip != null, () -> currentClip != null}),
                        buildStep1Extract()),
                stepPage("Add subtitles",
                        "Captions are written automatically from the speech in your clip, and you can fix any word before exporting. Don't want captions? Press Next to skip.",
                        guide(new String[]{"Pick the spoken language and press Generate subtitles",
                                        "Play the preview and correct any wrong words in the caption list",
                                        "Adjust font, size and position until it looks right"},
                                new java.util.function.BooleanSupplier[]{() -> hasCaptions(), () -> cueEdited, () -> styleTouched}),
                        buildStep2Subtitles()),
                stepPage("Watermark", "Optional. Press Next to skip this step.", null, buildStep3Watermark()),
                stepPage("Export your video",
                        "Check what will be included below, then press Export video and choose where to save the MP4.",
                        null, buildStep4Export())
        };
        contentHost = new StackPane();
        contentHost.setAlignment(Pos.TOP_CENTER);
        contentHost.getStyleClass().add("page-host");
        pageScroll = new ScrollPane(contentHost);
        pageScroll.setFitToWidth(true);
        pageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pageScroll.getStyleClass().add("page-scroll");
        VBox.setVgrow(pageScroll, Priority.ALWAYS);
        installSmoothWheel();

        Node footer = buildFooter();

        VBox root = new VBox(0, header, pageScroll, footer);
        root.getStyleClass().add("app-shell");

        busyOverlay = buildBusyOverlay();
        toastLabel = new Label();
        toastLabel.getStyleClass().add("toast");
        toastLabel.setVisible(false);
        toastLabel.setMouseTransparent(true);
        StackPane.setAlignment(toastLabel, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toastLabel, new Insets(0, 0, 96, 0));
        modalLayer = new StackPane();
        modalLayer.getStyleClass().add("modal-layer");
        modalLayer.setVisible(false);
        modalLayer.setPickOnBounds(true);
        StackPane sceneRoot = new StackPane(root, toastLabel, busyOverlay, modalLayer);

        var screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(sceneRoot, Math.min(1120, screen.getWidth() - 40), Math.min(900, screen.getHeight() - 40));
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        stage.setScene(scene);
        scene.widthProperty().addListener((o, a, b) -> moveStepIndicator(currentStep));
        stage.setMinWidth(860);
        stage.setMinHeight(600);
        stage.show();

        goToStep(0);
        // Design check: launching with --step=1..4 opens that step directly (no clip needed).
        String startStep = getParameters().getNamed().get("step");
        if (startStep != null) try { goToStep(Integer.parseInt(startStep.trim()) - 1); } catch (NumberFormatException ignored) {}
        var named = getParameters().getNamed();
        if (named.containsKey("selftest")) {
            String[] r = named.getOrDefault("range", "0-20").split("-");
            runSelfTest(named.get("selftest"), Double.parseDouble(r[0]), Double.parseDouble(r[1]),
                    "1".equals(named.get("subs")), "1".equals(named.get("watermark")),
                    Paths.get(named.get("out")), Paths.get(named.get("report")));
        }
        if (getParameters().getUnnamed().contains("--bottom")) {
            PauseTransition later = new PauseTransition(Duration.millis(900));
            later.setOnFinished(e -> pageScroll.setVvalue(1));
            later.play();
        }
        if (getParameters().getUnnamed().contains("--controls")) {
            for (Node n : new Node[]{scrubber, controlsRow}) { n.setVisible(true); n.setManaged(true); }
            scrubber.setDurationSeconds(180);
        }
        if (getParameters().getUnnamed().contains("--dialog")) {
            PauseTransition later = new PauseTransition(Duration.millis(900));
            later.setOnFinished(e -> showSheet("danger", "Stop this?",
                    "What's running now will be stopped and this step's progress is lost. Your earlier steps are kept.",
                    "Stop", null, "Keep going", null));
            later.play();
        }
    }

    /** Bundled typefaces (SIL Open Font License, see /fonts): Bricolage Grotesque for the whole
     *  interface, JetBrains Mono only for timecodes so digits line up while they change. */
    private void loadFonts() {
        for (String f : List.of("BricolageGrotesque_400Regular.ttf", "BricolageGrotesque_500Medium.ttf",
                "BricolageGrotesque_600SemiBold.ttf", "BricolageGrotesque_700Bold.ttf",
                "JetBrainsMono_400Regular.ttf", "JetBrainsMono_600SemiBold.ttf")) {
            try (InputStream in = getClass().getResourceAsStream("/fonts/" + f)) {
                if (in != null) javafx.scene.text.Font.loadFont(in, 13);
            } catch (IOException ignored) {}
        }
    }

    private Region spacer() { Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); return r; }

    /** A rounded CSS background only draws a shape behind its children — it doesn't clip them,
     *  so rectangular video content that fills the frame shows square corners poking through the
     *  rounded border. Bind an actual clip shape so the content is masked to match. */
    private void applyRoundedClip(Region region, double radius) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        region.setClip(clip);
    }

    /** Header: wordmark on the left, the step rail on the right. The rail is the map of the whole
     *  job — finished steps show a tick and can be clicked to go back. */
    private Node buildHeader() {
        Label title = new Label("ClipForge");
        title.getStyleClass().add("app-title");

        HBox rail = new HBox(6);
        rail.setAlignment(Pos.CENTER_RIGHT);
        stepDots = new Label[STEP_NAMES.length];
        stepItems = new HBox[STEP_NAMES.length];
        for (int i = 0; i < STEP_NAMES.length; i++) {
            final int idx = i;
            Label dot = new Label(String.valueOf(i + 1));
            dot.getStyleClass().add("step-dot");
            Label name = new Label(STEP_NAMES[i]);
            name.getStyleClass().add("step-name");
            HBox item = new HBox(8, dot, name);
            item.setAlignment(Pos.CENTER_LEFT);
            item.getStyleClass().add("step-item");
            item.setOnMouseClicked(e -> { if (idx == 0 || currentClip != null) goToStep(idx); });
            stepDots[i] = dot;
            stepItems[i] = item;
            rail.getChildren().add(item);
            if (i < STEP_NAMES.length - 1) {
                Region line = new Region();
                line.getStyleClass().add("step-connector");
                rail.getChildren().add(line);
            }
        }
        // A yellow underline that glides to whichever step is active.
        stepIndicator = new Region();
        stepIndicator.getStyleClass().add("step-indicator");
        stepIndicator.setManaged(false);
        stepIndicator.resize(0, 2);
        stepIndicator.prefWidthProperty().addListener((o, a, b) -> stepIndicator.resize(b.doubleValue(), 2));
        Pane indicatorLane = new Pane(stepIndicator);
        indicatorLane.setMinHeight(2); indicatorLane.setPrefHeight(2); indicatorLane.setMaxHeight(2);
        VBox railBox = new VBox(6, rail, indicatorLane);
        railBox.setAlignment(Pos.CENTER_RIGHT);
        railBox.setFillWidth(true);
        HBox header = new HBox(24, title, spacer(), railBox);
        header.getStyleClass().add("header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /** Footer stays put while the page scrolls, so Back / Next and the current status are always
     *  in view. Technical output lives in a collapsed "Details" panel underneath. */
    private Node buildFooter() {
        backBtn = new Button("Back");
        backBtn.getStyleClass().add("ghost-btn");
        backBtn.setOnAction(e -> goToStep(currentStep - 1));
        nextBtn = new Button(NEXT_LABELS[0]);
        nextBtn.getStyleClass().addAll("primary-btn", "next-btn");
        nextBtn.setOnAction(e -> {
            if (currentStep == stepPanes.length - 1) runFinalExport();
            else goToStep(currentStep + 1);
        });

        status = new Label("Paste a video link to begin.");
        status.getStyleClass().addAll("status-label", "status-info");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);

        progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("footer-progress");

        HBox navRow = new HBox(16, backBtn, status, nextBtn);
        navRow.setAlignment(Pos.CENTER_LEFT);
        navRow.getStyleClass().add("footer-row");

        log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(6);
        log.setWrapText(true);
        log.getStyleClass().add("log-area");
        logPane = new TitledPane("Details", log);
        logPane.setExpanded(false);
        logPane.getStyleClass().add("log-pane");

        VBox footer = new VBox(0, progress, navRow, logPane);
        footer.getStyleClass().add("footer-bar");
        return footer;
    }

    private void setActiveStepVisual(int idx) {
        for (int i = 0; i < stepDots.length; i++) {
            stepItems[i].getStyleClass().removeAll("step-item-active", "step-item-done", "step-item-locked");
            String newText = i < idx || (i != idx && i == 0 && currentClip != null) ? "\u2713" : String.valueOf(i + 1);
            boolean becameDone = "\u2713".equals(newText) && !newText.equals(stepDots[i].getText());
            stepDots[i].setText(newText);
            if (becameDone) pop(stepDots[i]);
            if (i == idx) stepItems[i].getStyleClass().add("step-item-active");
            else if (i < idx || (i == 0 && currentClip != null)) stepItems[i].getStyleClass().add("step-item-done");
            else if (currentClip == null) stepItems[i].getStyleClass().add("step-item-locked");
        }
    }

    private void goToStep(int idx) {
        if (idx < 0 || idx >= stepPanes.length) return;
        int direction = idx >= currentStep ? 1 : -1;
        boolean changed = idx != currentStep || contentHost.getChildren().isEmpty();
        currentStep = idx;
        setActiveStepVisual(idx);
        Node pane = stepPanes[idx];
        contentHost.getChildren().setAll(pane);
        if (stepIndicator != null) moveStepIndicator(idx);
        // Every step starts at the top of its page, and the new page slides in from the side
        // you're heading towards (forward = from the right, Back = from the left).
        if (scrollAnim != null) scrollAnim.stop();
        pageScroll.setVvalue(0);
        if (changed && ANIMATE) {
            pane.setOpacity(0);
            pane.setTranslateX(36 * direction);
            FadeTransition ft = new FadeTransition(Duration.millis(260), pane);
            ft.setToValue(1);
            TranslateTransition tt = new TranslateTransition(Duration.millis(320), pane);
            tt.setToX(0);
            tt.setInterpolator(EASE_OUT);
            new ParallelTransition(ft, tt).play();
        } else { pane.setOpacity(1); pane.setTranslateX(0); }
        backBtn.setVisible(idx > 0);
        backBtn.setManaged(idx > 0);
        nextBtn.setText(NEXT_LABELS[idx]);
        if (idx == 1 && currentClip != null && !currentClip.equals(subtitlePreviewFor)) attachSubtitlePreview(currentClip);
        if (idx == 2 && currentClip != null && !watermarkOverlay.hasImage()) captureFrame();
        if (idx == 3) refreshSummary();
        updateNextEnablement();
        refreshGuides();
        // Put keyboard focus on the thing you'd do next, not on Back.
        javafx.application.Platform.runLater(() -> {
            if (idx == 0 && currentClip == null) urlField.requestFocus();
            else if (!nextBtn.isDisabled()) nextBtn.requestFocus();
        });
    }

    private void updateNextEnablement() {
        boolean enabled = currentStep != 0 || currentClip != null;
        nextBtn.setDisable(!enabled);
        if (stepItems != null) setActiveStepVisual(currentStep);
    }

    /** One step's page: a plain-language heading, one sentence on what the step is for, an
     *  optional to-do list that ticks itself off as the work gets done, then the controls. */
    private Node stepPage(String title, String description, Node guide, Node body) {
        Label h = new Label(title);
        h.getStyleClass().add("step-title");
        Label d = new Label(description);
        d.getStyleClass().add("step-desc");
        d.setWrapText(true);
        VBox head = new VBox(6, h, d);
        if (guide != null) head.getChildren().add(guide);
        head.getStyleClass().add("step-head");
        VBox page = new VBox(14, head, body);
        page.getStyleClass().add("step-page");
        page.setMaxWidth(1040);
        return page;
    }

    /** Numbered to-do list (the items really are done in order). Each item turns into a tick
     *  once its condition is met, so the user can see at a glance what's left. */
    private Node guide(String[] items, java.util.function.BooleanSupplier[] done) {
        HBox row = new HBox(22);
        row.getStyleClass().add("guide");
        for (int i = 0; i < items.length; i++) {
            Label mark = new Label(String.valueOf(i + 1));
            mark.getStyleClass().add("guide-mark");
            Label text = new Label(items[i]);
            text.getStyleClass().add("guide-text");
            text.setWrapText(true);
            HBox item = new HBox(8, mark, text);
            item.setAlignment(Pos.CENTER_LEFT);
            item.getStyleClass().add("guide-item");
            row.getChildren().add(item);
            final int n = i;
            guideRefreshers.add(() -> {
                boolean ok = done[n].getAsBoolean();
                boolean wasDone = item.getStyleClass().contains("guide-item-done");
                mark.setText(ok ? "\u2713" : String.valueOf(n + 1));
                if (ok && !wasDone) pop(mark);
                item.getStyleClass().remove("guide-item-done");
                if (ok) item.getStyleClass().add("guide-item-done");
            });
        }
        return row;
    }

    private void refreshGuides() { guideRefreshers.forEach(Runnable::run); }

    // =========================================================================
    // Self-test: drives the real step methods (the same ones the buttons call) through
    // the whole flow, timing each stage and watching the UI thread for freezes.
    //   --selftest=<url> --range=<startSec>-<endSec> --subs=1 --out=<mp4> --report=<folder>
    // =========================================================================

    private volatile long frameLast, frameMaxGapNs;
    private volatile int frameStalls;
    private AnimationTimer frameMonitor;

    private void runSelfTest(String url, double a, double b, boolean subs, boolean watermark, Path outFile, Path reportDir) {
        frameMonitor = new AnimationTimer() {
            @Override public void handle(long now) {
                if (frameLast != 0) {
                    long gap = now - frameLast;
                    if (gap > frameMaxGapNs) frameMaxGapNs = gap;
                    if (gap > 250_000_000L) frameStalls++;
                }
                frameLast = now;
            }
        };
        frameMonitor.start();
        Thread t = new Thread(() -> {
            StringBuilder rep = new StringBuilder("ClipForge self-test\nURL: " + url + "\nRange: " + a + "s - " + b + "s, subtitles: " + subs + ", watermark step: " + watermark + "\n\n");
            long all = System.nanoTime();
            try {
                Files.createDirectories(reportDir);
                Thread.sleep(1500); // let the first page settle
                testStage(rep, reportDir, "1 Load video", () -> {
                    fx(() -> { urlField.setText(url); runLoadVideo(); return null; });
                    waitUntil(() -> player != null && player.getStatus() == MediaPlayer.Status.PLAYING && !busyOverlay.isVisible(), 300);
                    Thread.sleep(1200);
                    return "preview window " + fx(() -> fmtHms(scrubber.getDurationSeconds())) + ", video " + fmtHms(totalVideoDuration)
                            + ", jump row shown: " + fx(() -> jumpRow.isVisible());
                });
                testStage(rep, reportDir, "2 Extract clip", () -> {
                    fx(() -> { inField.setText(fmtPrecise(a)); outField.setText(fmtPrecise(b)); applyFieldsToScrubber(); runExtractClip(); return null; });
                    waitUntil(() -> currentClip != null && !busyOverlay.isVisible(), 600);
                    Thread.sleep(900);
                    return "clip " + describeVideo(currentClip) + ", GPU encoder: " + (hwEncoder != null ? hwEncoder : "none (CPU)");
                });
                if (subs) {
                    testStage(rep, reportDir, "3 Generate subtitles", () -> {
                        fx(() -> { goToStep(1); return null; });
                        Thread.sleep(1500);
                        fx(() -> { whisperLanguageBox.setValue("English"); captionLengthBox.setValue("Short (Shorts / Reels style)"); runTranscription(); return null; });
                        waitBusyCycle(1200);
                        Thread.sleep(1500);
                        return fx(() -> cues.size()) + " captions, first: \"" + fx(() -> cues.isEmpty() ? "" : cues.get(0).text) + "\"";
                    });
                } else {
                    fx(() -> { goToStep(1); return null; });
                    Thread.sleep(800);
                    snap(reportDir.resolve("3 Subtitles page.png"));
                }
                if (watermark) {
                    testStage(rep, reportDir, "4 Watermark step", () -> {
                        fx(() -> { goToStep(2); return null; });
                        Thread.sleep(1500);
                        fx(() -> { watermarkCheck.setSelected(true); return null; });
                        waitUntil(() -> watermarkOverlay.hasImage(), 60);
                        Thread.sleep(800);
                        fx(() -> { runRemoveWatermark(); return null; });
                        waitBusyCycle(3600);
                        Thread.sleep(1200);
                        if (fx(() -> watermarkClip) == null) throw new IOException("step finished without a result clip");
                        return "result " + describeVideo(watermarkClip);
                    });
                } else {
                    fx(() -> { goToStep(2); return null; });
                    Thread.sleep(900);
                    snap(reportDir.resolve("4 Watermark page (not run).png"));
                }
                testStage(rep, reportDir, "5 Export", () -> {
                    fx(() -> { goToStep(3); return null; });
                    Thread.sleep(900);
                    snap(reportDir.resolve("5a Export page.png"));
                    Files.deleteIfExists(outFile);
                    fx(() -> { exportTo(outFile.toFile()); return null; });
                    waitBusyCycle(600);
                    Thread.sleep(900);
                    if (!Files.exists(outFile)) throw new IOException("export file was not created");
                    return "output " + describeVideo(outFile) + ", " + (Files.size(outFile) / 1024) + " KB";
                });
                fx(() -> { if (modalLayer.isVisible()) closeSheet(null); return null; });
                rep.append(String.format(Locale.US, "\nTOTAL: %.1fs%n", (System.nanoTime() - all) / 1e9));
            } catch (Exception ex) {
                rep.append("\nSTOPPED: ").append(ex).append("\n");
            } finally {
                try {
                    Files.writeString(reportDir.resolve("report.txt"), rep.toString(), StandardCharsets.UTF_8);
                    Files.writeString(reportDir.resolve("activity-log.txt"), fx(() -> { flushLog(); return log.getText(); }), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                ui(javafx.application.Platform::exit);
            }
        }, "selftest");
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface private interface TestBody { String run() throws Exception; }

    private void testStage(StringBuilder rep, Path dir, String name, TestBody body) throws Exception {
        frameMaxGapNs = 0; frameStalls = 0;
        long t0 = System.nanoTime();
        String detail;
        try { detail = body.run(); }
        catch (Exception ex) {
            String st = fx(() -> status.getText());
            rep.append(String.format(Locale.US, "%-22s FAILED after %.1fs: %s | status: %s%n", name, (System.nanoTime() - t0) / 1e9, ex.getMessage(), st));
            snap(dir.resolve(name + " (failed).png"));
            throw ex;
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        rep.append(String.format(Locale.US, "%-22s %6.1fs | UI longest freeze %4d ms, freezes >250ms: %d | %s%n",
                name, secs, frameMaxGapNs / 1_000_000, frameStalls, detail));
        rep.append("    status: ").append(fx(() -> status.getText())).append("\n");
        snap(dir.resolve(name + ".png"));
    }

    private <T> T fx(java.util.concurrent.Callable<T> c) throws Exception {
        if (javafx.application.Platform.isFxApplicationThread()) return c.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        javafx.application.Platform.runLater(() -> { try { f.complete(c.call()); } catch (Throwable e) { f.completeExceptionally(e); } });
        return f.get(60, TimeUnit.SECONDS);
    }

    private void waitUntil(java.util.function.BooleanSupplier cond, int timeoutSec) throws Exception {
        long end = System.nanoTime() + timeoutSec * 1_000_000_000L;
        while (System.nanoTime() < end) {
            if (fx(cond::getAsBoolean)) return;
            if (fx(() -> status.getStyleClass().contains("status-error")) && !fx(() -> busyOverlay.isVisible()))
                throw new IOException("app reported an error");
            Thread.sleep(250);
        }
        throw new IOException("timed out after " + timeoutSec + "s");
    }

    /** Waits for a background job: the busy overlay appears, then goes away. */
    private void waitBusyCycle(int timeoutSec) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < end && !fx(() -> busyOverlay.isVisible())) Thread.sleep(100);
        waitUntil(() -> !busyOverlay.isVisible(), timeoutSec);
        if (fx(() -> status.getStyleClass().contains("status-error"))) throw new IOException("app reported an error");
    }

    private String describeVideo(Path f) {
        try {
            String ffprobe = findTool("ffprobe", "ffprobe.exe");
            String out = runCommandCaptureOutput(List.of(ffprobe, "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=codec_name,width,height,pix_fmt:format=duration", "-of", "csv=p=0:s=x", f.toString()));
            return out.trim().replace("\n", " ").replace("\r", "");
        } catch (Exception e) { return "(ffprobe failed: " + e.getMessage() + ")"; }
    }

    private void snap(Path file) {
        try {
            WritableImage img = fx(() -> log.getScene().snapshot(null));
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            int[] buf = new int[w * h];
            img.getPixelReader().getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), buf, 0, w);
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            bi.setRGB(0, 0, w, h, buf, 0, w);
            javax.imageio.ImageIO.write(bi, "png", file.toFile());
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Motion. Short, eased, and only where something changed or needs your eye.
    // CLIPFORGE_ANIMATIONS=0 turns it all off (instant changes, same behaviour).
    // =========================================================================

    private static final boolean ANIMATE = !"0".equals(System.getenv("CLIPFORGE_ANIMATIONS"));
    /** Fast start, soft landing — reads as "settling into place". */
    private static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);
    private Timeline scrollAnim;
    private Region stepIndicator;

    /** Smoothly scrolls the page to a vertical position (0 = top, 1 = bottom). */
    private void smoothScrollTo(double target) {
        target = Math.max(0, Math.min(1, target));
        if (scrollAnim != null) scrollAnim.stop();
        if (!ANIMATE) { pageScroll.setVvalue(target); return; }
        scrollAnim = new Timeline(new KeyFrame(Duration.millis(520),
                new KeyValue(pageScroll.vvalueProperty(), target, EASE_OUT)));
        scrollAnim.play();
    }

    /** Scrolls just enough that the node is fully on screen (top-aligned with a margin if it's
     *  taller than the view). Runs after layout so freshly revealed nodes have real sizes. */
    private void ensureVisible(Node node, double margin) {
        javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(() -> {
            if (node.getScene() == null) return;
            double contentH = contentHost.getBoundsInLocal().getHeight();
            double viewH = pageScroll.getViewportBounds().getHeight();
            if (contentH <= viewH + 1) return;
            var b = contentHost.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double scrollable = contentH - viewH;
            double viewTop = pageScroll.getVvalue() * scrollable;
            double target;
            if (b.getMinY() - margin < viewTop || b.getHeight() + 2 * margin > viewH) target = b.getMinY() - margin;
            else if (b.getMaxY() + margin > viewTop + viewH) target = b.getMaxY() + margin - viewH;
            else return;
            smoothScrollTo(target / scrollable);
        }));
    }

    /** Shows nodes that were hidden, each rising and fading in, slightly staggered. */
    private void reveal(Node... nodes) {
        int k = 0;
        for (Node n : nodes) {
            boolean wasHidden = !n.isVisible();
            n.setManaged(true);
            n.setVisible(true);
            if (!ANIMATE || !wasHidden) continue;
            n.setOpacity(0);
            n.setTranslateY(14);
            FadeTransition f = new FadeTransition(Duration.millis(280), n);
            f.setToValue(1);
            TranslateTransition t = new TranslateTransition(Duration.millis(380), n);
            t.setToY(0);
            t.setInterpolator(EASE_OUT);
            ParallelTransition pt = new ParallelTransition(f, t);
            pt.setDelay(Duration.millis(70 * k++));
            pt.play();
        }
    }

    /** A quick "done" bounce for ticks and badges. */
    private void pop(Node n) {
        if (!ANIMATE) return;
        ScaleTransition up = new ScaleTransition(Duration.millis(140), n);
        up.setFromX(0.6); up.setFromY(0.6);
        up.setToX(1.18); up.setToY(1.18);
        up.setInterpolator(EASE_OUT);
        ScaleTransition down = new ScaleTransition(Duration.millis(160), n);
        down.setToX(1); down.setToY(1);
        new SequentialTransition(up, down).play();
    }

    /** Draws the eye to the button you should press next: two gentle pulses, then still. */
    private void nudge(Node n) {
        if (!ANIMATE) return;
        ScaleTransition s = new ScaleTransition(Duration.millis(260), n);
        s.setFromX(1); s.setFromY(1);
        s.setToX(1.07); s.setToY(1.07);
        s.setAutoReverse(true);
        s.setCycleCount(4);
        s.setInterpolator(Interpolator.EASE_BOTH);
        s.setDelay(Duration.millis(250));
        s.play();
    }

    /** Fades between two states of the same view (e.g. the empty placeholder and the video). */
    private void crossFade(Node out, Node in) {
        if (!ANIMATE) { if (out != null) out.setVisible(false); if (in != null) { in.setOpacity(1); in.setVisible(true); } return; }
        if (out != null && out.isVisible()) {
            FadeTransition f = new FadeTransition(Duration.millis(200), out);
            f.setToValue(0);
            f.setOnFinished(e -> { out.setVisible(false); out.setOpacity(1); });
            f.play();
        }
        if (in != null) {
            in.setVisible(true);
            in.setOpacity(0);
            FadeTransition f = new FadeTransition(Duration.millis(420), in);
            f.setToValue(1);
            f.play();
        }
    }

    /** Wheel scrolling glides instead of jumping. Lists and text boxes inside the page keep their
     *  own scrolling: they consume the event before it bubbles up here. */
    private void installSmoothWheel() {
        contentHost.addEventHandler(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) return;
            double contentH = contentHost.getBoundsInLocal().getHeight();
            double viewH = pageScroll.getViewportBounds().getHeight();
            double scrollable = contentH - viewH;
            if (scrollable <= 0) return;
            double base = (scrollAnim != null && scrollAnim.getStatus() == Animation.Status.RUNNING) ? scrollTarget : pageScroll.getVvalue();
            scrollTarget = Math.max(0, Math.min(1, base - e.getDeltaY() * 1.6 / scrollable));
            if (!ANIMATE) pageScroll.setVvalue(scrollTarget);
            else {
                if (scrollAnim != null) scrollAnim.stop();
                scrollAnim = new Timeline(new KeyFrame(Duration.millis(260),
                        new KeyValue(pageScroll.vvalueProperty(), scrollTarget, EASE_OUT)));
                scrollAnim.play();
            }
            e.consume();
        });
    }
    private double scrollTarget;

    /** Slides the yellow underline in the step rail to the active step. */
    private void moveStepIndicator(int idx) {
        javafx.application.Platform.runLater(() -> {
            HBox item = stepItems[idx];
            var b = stepIndicator.getParent().sceneToLocal(item.localToScene(item.getBoundsInLocal()));
            double x = b.getMinX() + 10, w = Math.max(0, b.getWidth() - 20);
            if (!ANIMATE || stepIndicator.getWidth() == 0) {
                stepIndicator.setTranslateX(x);
                stepIndicator.setPrefWidth(w);
                return;
            }
            new Timeline(new KeyFrame(Duration.millis(380),
                    new KeyValue(stepIndicator.translateXProperty(), x, EASE_OUT),
                    new KeyValue(stepIndicator.prefWidthProperty(), w, EASE_OUT))).play();
        });
    }

    private StackPane buildBusyOverlay() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(48, 48);
        busyLabel = new Label();
        busyLabel.getStyleClass().add("busy-label");
        busyLabel.setWrapText(true);
        busyLabel.setMaxWidth(420);
        busyLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Label hint = new Label("You can keep this window open. Long clips take a while.");
        hint.getStyleClass().add("busy-hint");

        Button cancelBtn = new Button("Stop");
        cancelBtn.getStyleClass().add("ghost-btn");
        cancelBtn.setOnAction(e -> showSheet("danger", "Stop this?",
                "What's running now will be stopped and this step's progress is lost. Your earlier steps are kept.",
                "Stop", () -> { cancelCurrentOperation(); toast("Stopped"); },
                "Keep going", null));

        VBox box = new VBox(14, spinner, busyLabel, hint, cancelBtn);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("busy-card");
        box.setMaxSize(480, Region.USE_PREF_SIZE);
        StackPane overlay = new StackPane(box);
        overlay.getStyleClass().add("busy-overlay");
        overlay.setVisible(false);
        overlay.setPickOnBounds(true);
        return overlay;
    }

    private VBox card(Node... content) {
        VBox box = new VBox(12, content);
        box.getStyleClass().add("section-card");
        return box;
    }

    /** Small heading for a group of controls inside a step. */
    private Label groupTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("group-title");
        return l;
    }

    private TitledPane titled(String title, javafx.scene.Node content) {
        TitledPane p = new TitledPane(title, content);
        p.setExpanded(true);
        return p;
    }

    private Label field(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        return l;
    }

    // =========================================================================
    // Step 1: Extract
    // =========================================================================

    private Label videoEmptyState, clipLengthLabel;
    private HBox controlsRow;
    private HBox jumpRow;

    private Node buildStep1Extract() {
        urlField = new TextField();
        urlField.setPromptText("Paste a video link (YouTube, X and most other video sites)");
        urlField.setOnAction(e -> runLoadVideo());
        HBox.setHgrow(urlField, Priority.ALWAYS);
        loadBtn = new Button("Load video");
        loadBtn.getStyleClass().add("primary-btn");
        loadBtn.setOnAction(e -> runLoadVideo());
        HBox urlRow = new HBox(10, urlField, loadBtn);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        mediaView = new MediaView();
        mediaView.setPreserveRatio(true);
        mediaView.setFitWidth(900);
        mediaView.setFitHeight(290);
        videoEmptyState = new Label("Your video will appear here after you press Load video.");
        videoEmptyState.getStyleClass().add("empty-state");
        StackPane videoFrame = new StackPane(videoEmptyState, mediaView);
        videoFrame.getStyleClass().add("video-frame");
        videoFrame.setPrefHeight(290);
        videoFrame.setMinHeight(200);
        videoFrame.setAlignment(Pos.CENTER);
        applyRoundedClip(videoFrame, 8);

        playIcon = new Polygon(0, 0, 0, 14, 12, 7);
        ((Polygon) playIcon).setFill(Color.web("#EDECE7"));
        Rectangle bar1 = new Rectangle(4, 14, Color.web("#EDECE7"));
        Rectangle bar2 = new Rectangle(4, 14, Color.web("#EDECE7"));
        HBox pauseBars = new HBox(3, bar1, bar2);
        pauseIcon = pauseBars;
        pauseIcon.setVisible(false);
        StackPane iconStack = new StackPane(playIcon, pauseIcon);
        playPauseBtn = new Button();
        playPauseBtn.setGraphic(iconStack);
        playPauseBtn.getStyleClass().add("icon-btn");
        playPauseBtn.setDisable(true);
        playPauseBtn.setTooltip(new Tooltip("Play / pause the selected part"));
        playPauseBtn.setOnAction(e -> togglePlayback());

        Label timeLabel = new Label("00:00:00 / 00:00:00");
        timeLabel.getStyleClass().add("time-chip");
        this.timeLabel = timeLabel;

        scrubber = new TimelineScrubber();
        scrubber.setOnSeek(sec -> { if (player != null) player.seek(Duration.seconds(sec)); });
        scrubber.setOnRangeChanged(range -> { rangeTouched = true; updateFieldsFromScrubber(); refreshGuides(); });

        inField = new TextField("00:00:00.000");
        outField = new TextField("00:00:30.000");
        inField.getStyleClass().add("time-field");
        outField.getStyleClass().add("time-field");
        inField.setPrefWidth(120);
        outField.setPrefWidth(120);
        inField.setTooltip(new Tooltip("Where the clip starts. Type a time and press Enter, or drag the green handle."));
        outField.setTooltip(new Tooltip("Where the clip ends. Type a time and press Enter, or drag the red handle."));
        inField.setOnAction(e -> applyFieldsToScrubber());
        outField.setOnAction(e -> applyFieldsToScrubber());
        inField.focusedProperty().addListener((o, was, is) -> { if (!is) applyFieldsToScrubber(); });
        outField.focusedProperty().addListener((o, was, is) -> { if (!is) applyFieldsToScrubber(); });

        HBox transportRow = new HBox(10, playPauseBtn, timeLabel);
        transportRow.setAlignment(Pos.CENTER_LEFT);

        Region inDot = new Region(); inDot.getStyleClass().addAll("marker-dot", "marker-in");
        Region outDot = new Region(); outDot.getStyleClass().addAll("marker-dot", "marker-out");
        clipLengthLabel = new Label("Clip length 00:00:30");
        clipLengthLabel.getStyleClass().add("clip-length");
        HBox rangeRow = new HBox(8, inDot, field("Start"), inField, new Region() {{ setMinWidth(8); }},
                outDot, field("End"), outField, clipLengthLabel);
        rangeRow.setAlignment(Pos.CENTER_LEFT);

        extractBtn = new Button("Extract clip");
        extractBtn.getStyleClass().add("primary-btn");
        extractBtn.setDisable(true);
        extractBtn.setOnAction(e -> runExtractClip());

        controlsRow = new HBox(20, transportRow, rangeRow, spacer(), extractBtn);
        controlsRow.setAlignment(Pos.CENTER_LEFT);
        // Timeline and clip controls only appear once there's a video to use them on.
        for (Node n : new Node[]{scrubber, controlsRow}) { n.setVisible(false); n.setManaged(false); }

        jumpField = new TextField("00:00:00");
        jumpField.setPrefWidth(100);
        jumpField.getStyleClass().add("time-field");
        jumpBtn = new Button("Show this part");
        jumpBtn.setDisable(true);
        jumpBtn.setOnAction(e -> {
            try {
                loadPreviewWindow(parseTime(jumpField.getText()));
            } catch (Exception ex) { alert(ex.getMessage()); }
        });
        windowInfoLabel = new Label();
        windowInfoLabel.getStyleClass().add("hint-label");
        windowInfoLabel.setWrapText(true);
        Label jumpLabel = new Label("Long video: the preview shows 4 minutes at a time. Jump to");
        jumpLabel.getStyleClass().add("hint-label");
        jumpRow = new HBox(10, jumpLabel, jumpField, jumpBtn, windowInfoLabel);
        jumpRow.setAlignment(Pos.CENTER_LEFT);
        jumpRow.getStyleClass().add("jump-row");
        jumpRow.setVisible(false);
        jumpRow.setManaged(false);

        return card(urlRow, videoFrame, scrubber, controlsRow, jumpRow);
    }

    private void updateClipLength() {
        if (clipLengthLabel != null) clipLengthLabel.setText("Clip length " + fmtHms(Math.max(0, scrubber.getOutSeconds() - scrubber.getInSeconds())));
    }

    private Label timeLabel;

    private void togglePlayback() {
        if (player == null) return;
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
            showPlayIcon();
        } else {
            if (player.getCurrentTime().toSeconds() >= scrubber.getOutSeconds() - 0.05) {
                player.seek(Duration.seconds(scrubber.getInSeconds()));
            }
            player.play();
            showPauseIcon();
            // scheduleStallCheck/scheduleFreezeCheck re-arm on every PLAYING transition via the
            // status listener in attachPreview, but scheduleNeverStartedCheck was only ever armed
            // once, from the very first auto-play on load — so a manual replay after dragging a
            // handle (which seeks the player) that then silently never reaches PLAYING had no
            // recovery path at all. This covers that case too.
            scheduleNeverStartedCheck(player, currentPreviewMediaUrl, 0);
        }
    }

    private void showPlayIcon() { playIcon.setVisible(true); pauseIcon.setVisible(false); }
    private void showPauseIcon() { playIcon.setVisible(false); pauseIcon.setVisible(true); }

    private void updateFieldsFromScrubber() {
        suppressFieldSync = true;
        // Millisecond precision: whole-second text here used to get parsed back on focus loss and
        // silently snap a handle that was dragged to e.g. 12.4s onto 12.0s.
        inField.setText(fmtPrecise(scrubber.getInSeconds()));
        outField.setText(fmtPrecise(scrubber.getOutSeconds()));
        updateClipLength();
        suppressFieldSync = false;
    }

    private void applyFieldsToScrubber() {
        if (suppressFieldSync || scrubber.getDurationSeconds() <= 0) return;
        try {
            double s = parseTime(inField.getText());
            double e = parseTime(outField.getText());
            if (e <= s) throw new IllegalArgumentException("End must be after start.");
            if (e > scrubber.getDurationSeconds() + 0.001)
                throw new IllegalArgumentException("Out point is past the end of the loaded preview window ("
                        + fmtPrecise(scrubber.getDurationSeconds()) + "). Use \"Jump to time\" to load a later part.");
            // Setting in then out separately clamped the new in point against the OLD out point,
            // so moving the whole range later (e.g. 0-30 -> 40-50) silently produced 29.9-50.
            scrubber.setRange(s, e);
            rangeTouched = true;
            updateClipLength();
            refreshGuides();
            if (player != null) player.seek(Duration.seconds(s));
        } catch (Exception ex) {
            alert(ex.getMessage());
            updateFieldsFromScrubber();
        }
    }

    private void runLoadVideo() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) { setStatus("Paste a video link into the box first, then press Load video.", "error"); urlField.requestFocus(); return; }
        lastLoadedUrl = url;
        setBusy("Finding the video...");
        loadBtn.setDisable(true);
        submit(() -> {
            try {
                resolveStreams(url);

                // Deliberately not clearing the busy overlay / re-enabling Load here on success —
                // loadPreviewWindow takes over immediately and owns clearing it at the real end
                // of the whole operation. Clearing it in between (even briefly) let the button
                // become clickable again while the blank video frame was still showing, which is
                // exactly the gap that let a second overlapping load get triggered.
                ui(() -> {
                    jumpBtn.setDisable(false);
                    loadBtn.setDisable(false);
                    boolean longVideo = totalVideoDuration > PREVIEW_WINDOW_SECONDS;
                    jumpRow.setVisible(longVideo);
                    jumpRow.setManaged(longVideo);
                    rangeTouched = false;
                    loadPreviewWindow(0);
                });
            } catch (Exception ex) {
                fail(ex);
                ui(() -> { clearBusy(); loadBtn.setDisable(false); });
            }
        });
    }

    /** Fetches [start, start+len) of a remote video and encodes it to `out`.
     *
     *  YouTube serves each connection at only ~2x real time, so one ffmpeg pulling a 4-minute
     *  preview took ~2 minutes no matter how fast the GPU encoder was (measured: 115s). Longer
     *  ranges are therefore split into up to 4 video pieces fetched and encoded in parallel, plus
     *  the audio as one continuous piece (so there are no clicks at the joins), then joined with
     *  a stream copy. Short ranges keep the original single-pass path. */
    private RangeProxy rangeProxy;

    /** ffmpeg reads remote streams through the local range relay (see RangeProxy): bounded
     *  range requests are served at full speed where one long request is throttled. Falls back
     *  to the direct URL if the relay can't start. */
    private synchronized String relay(String url) {
        if (url == null) return null;
        try {
            if (rangeProxy == null) rangeProxy = new RangeProxy();
            return rangeProxy.wrap(url);
        } catch (IOException e) {
            ui(() -> logLine("Download relay unavailable, reading the stream directly: " + e.getMessage()));
            return url;
        }
    }

    private volatile boolean relayBroken = false;

    private void fetchSegment(String ffmpeg, String vUrlRemote, String aUrlRemote, double start, double len,
                              String vf, boolean preview, String audioRate, Path out) throws Exception {
        if (!relayBroken) {
            try {
                fetchSegmentVia(ffmpeg, relay(vUrlRemote), relay(aUrlRemote), start, len, vf, preview, audioRate, out);
                return;
            } catch (CancellationException ce) {
                throw ce;
            } catch (Exception ex) {
                // Never let the speed-up be the reason a step fails: retry on the direct path
                // (the original behaviour) and stop using the relay for this session.
                relayBroken = true;
                ui(() -> logLine("Fast download path failed (" + String.valueOf(ex.getMessage()).split("\\R")[0]
                        + ") — retrying with a direct connection."));
            }
        }
        fetchSegmentVia(ffmpeg, vUrlRemote, aUrlRemote, start, len, vf, preview, audioRate, out);
    }

    private void fetchSegmentVia(String ffmpeg, String vUrl, String aUrl, double start, double len,
                                 String vf, boolean preview, String audioRate, Path out) throws Exception {
        int pieces = len >= 40 ? (int) Math.min(4, Math.ceil(len / 20.0)) : 1;
        if (pieces == 1) {
            encodeWithFallback(enc -> {
                List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-ss", fmt(start), "-i", vUrl));
                if (aUrl != null) cmd.addAll(List.of("-ss", fmt(start), "-i", aUrl));
                cmd.addAll(List.of("-t", fmt(len), "-vf", vf));
                cmd.addAll(videoEncoderArgs(enc, preview));
                cmd.addAll(List.of("-c:a", "aac", "-b:a", audioRate));
                if (aUrl != null) cmd.addAll(List.of("-map", "0:v:0", "-map", "1:a:0"));
                else cmd.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?"));
                cmd.add(out.toString());
                return cmd;
            });
            return;
        }
        hardwareEncoder(); // probe once up front, not from four threads at the same time
        Path dir = out.resolveSibling(out.getFileName() + "_parts");
        Files.createDirectories(dir);
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        List<Path> parts = new ArrayList<>();
        double pieceLen = len / pieces;
        for (int k = 0; k < pieces; k++) {
            double ps = start + k * pieceLen;
            double pl = (k == pieces - 1) ? (start + len - ps) : pieceLen;
            Path part = dir.resolve(String.format("v%02d.mp4", k));
            parts.add(part);
            jobs.add(CompletableFuture.runAsync(() -> {
                try {
                    encodeWithFallback(enc -> {
                        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-ss", fmt(ps), "-i", vUrl,
                                "-t", fmt(pl), "-vf", vf));
                        cmd.addAll(videoEncoderArgs(enc, preview));
                        cmd.addAll(List.of("-an", part.toString()));
                        return cmd;
                    });
                } catch (Exception ex) { throw new CompletionException(ex); }
            }, executor));
        }
        Path audio = dir.resolve("audio.m4a");
        String audioSrc = aUrl != null ? aUrl : vUrl;
        jobs.add(CompletableFuture.runAsync(() -> {
            try {
                runCommand(List.of(ffmpeg, "-y", "-ss", fmt(start), "-i", audioSrc, "-t", fmt(len),
                        "-vn", "-c:a", "aac", "-b:a", audioRate, audio.toString()));
            } catch (Exception ex) { throw new CompletionException(ex); }
        }, executor));
        try {
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ce) {
            for (CompletableFuture<Void> j : jobs) j.cancel(true);
            Throwable c = ce.getCause();
            if (c instanceof Exception ex) throw ex;
            throw ce;
        }
        Path list = dir.resolve("parts.txt");
        StringBuilder sb = new StringBuilder();
        for (Path pth : parts) sb.append("file '").append(pth.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''")).append("'\n");
        Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
        boolean hasAudio = Files.exists(audio) && Files.size(audio) > 0;
        List<String> join = new ArrayList<>(List.of(ffmpeg, "-y", "-f", "concat", "-safe", "0", "-i", list.toString()));
        if (hasAudio) join.addAll(List.of("-i", audio.toString(), "-map", "0:v:0", "-map", "1:a:0"));
        join.addAll(List.of("-c", "copy", "-shortest", out.toString()));
        runCommand(join);
        try (var st = Files.walk(dir)) {
            st.sorted(Comparator.reverseOrder()).forEach(pth -> { try { Files.delete(pth); } catch (IOException ignored) {} });
        }
    }

    /** Resolves both stream URLs (and the duration) for a page URL without downloading anything.
     *  The full-quality lookup also prints the duration, so the old separate "--print duration"
     *  call (which repeated the slow page extraction) is gone, and the preview lookup runs in
     *  parallel with it — roughly halving the time "Load Video" spends waiting on yt-dlp.
     *  Also called again to refresh the URLs when they expire (YouTube's links only live a few
     *  hours, so extracting after leaving the app open used to fail with HTTP 403). */
    private void resolveStreams(String url) throws Exception {
        String ytdlp = findTool("yt-dlp", "yt-dlp.exe");
        CompletableFuture<String> previewFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return runCommandCaptureOutput(List.of(ytdlp, "--no-playlist", "--no-warnings",
                        "-f", PREVIEW_FORMAT, "--print", "urls", url));
            } catch (Exception ex) { throw new CompletionException(ex); }
        }, executor);

        String hq = runCommandCaptureOutput(List.of(ytdlp, "--no-playlist", "--no-warnings",
                "-f", HQ_FORMAT, "--print", "duration", "--print", "urls", url));
        String[] hqLines = Arrays.stream(hq.split("\\R+")).map(String::trim).filter(x -> !x.isEmpty()).toArray(String[]::new);
        if (hqLines.length < 2)
            throw new IOException("Could not resolve a stream URL for this link.");
        double dur;
        try { dur = Double.parseDouble(hqLines[0]); } catch (NumberFormatException nfe) { dur = 0; } // "NA" for live/unknown
        String[] hqUrls = Arrays.copyOfRange(hqLines, 1, hqLines.length);

        String[] pvUrls;
        try {
            String pv = previewFuture.get();
            pvUrls = Arrays.stream(pv.split("\\R+")).map(String::trim).filter(x -> x.startsWith("http")).toArray(String[]::new);
        } catch (Exception ex) {
            // A missing low-res variant is not fatal: preview just falls back to the HQ stream.
            pvUrls = new String[0];
            String m = ex.getMessage();
            ui(() -> logLine("Low-res preview stream unavailable, using full-quality stream for preview (" + m + ")"));
        }

        videoStreamUrl = hqUrls[0];
        audioStreamUrl = hqUrls.length > 1 ? hqUrls[1] : null;
        if (pvUrls.length > 0) {
            previewVideoUrl = pvUrls[0];
            previewAudioUrl = pvUrls.length > 1 ? pvUrls[1] : null;
        } else {
            previewVideoUrl = videoStreamUrl;
            previewAudioUrl = audioStreamUrl;
        }
        totalVideoDuration = dur;
    }

    /** Loads only a short window of the video for preview, starting at startOffset seconds into
     *  the full source — never the whole thing, since JavaFX's player hard-stalls past roughly
     *  5-10 minutes of content regardless of resolution/size. Fetches directly from the already-
     *  resolved remote stream via -ss/-t (fast HTTP range seek), so cost scales with the window
     *  size, not the source's total length, and the same mechanism works whether the video is
     *  15 seconds or 3 hours long. */
    private void loadPreviewWindow(double startOffset) {
        if (videoStreamUrl == null) { alert("Load a video first."); return; }
        windowStartOffset = Math.max(0, Math.min(startOffset, Math.max(0, totalVideoDuration - 1)));
        double windowLen = totalVideoDuration > 0
                ? Math.min(PREVIEW_WINDOW_SECONDS, totalVideoDuration - windowStartOffset)
                : PREVIEW_WINDOW_SECONDS;
        setBusy("Loading the preview...");
        submit(() -> {
            try {
                Path out = workDir();
                long stamp = System.nanoTime();
                Path rawWindow = out.resolve("window_raw_" + stamp + ".mp4");
                Path window = out.resolve("window_" + stamp + ".mp4");
                String ffmpeg = findTool("ffmpeg", "ffmpeg.exe");
                // Preview reads the separately resolved low-res stream (<=480p), so far less data
                // is fetched and decoded than when it pulled the 1080p stream and shrank it.
                // Still scaled to <=480p + mod-16 here because the fallback can be a bigger stream.
                withStreamRefresh(() -> fetchSegment(ffmpeg, previewVideoUrl, previewAudioUrl, windowStartOffset, windowLen,
                        "scale='if(gt(ih,480),-2,iw)':'if(gt(ih,480),480,ih)',scale=trunc(iw/16)*16:trunc(ih/16)*16",
                        true, "128k", rawWindow));
                // Seeking into a remote stream leaves a PTS offset that ffmpeg/ffprobe tolerate
                // fine but JavaFX's player cannot — confirmed empirically: identical content
                // stalls or errors muxed straight from a remote seek, and plays correctly once
                // timestamps are normalized in a quick second remux pass (stream copy, not a
                // re-encode, so this costs almost nothing).
                runCommand(List.of(ffmpeg, "-y", "-i", rawWindow.toString(),
                        "-avoid_negative_ts", "make_zero", "-fflags", "+genpts",
                        "-c", "copy", window.toString()));
                Files.deleteIfExists(rawWindow);
                double windowLenFinal = windowLen;
                ui(() -> {
                    attachPreview(window.toUri().toString());
                    String totalStr = totalVideoDuration > 0 ? fmtHms(totalVideoDuration) : "unknown length";
                    windowInfoLabel.setText("Now showing " + fmtHms(windowStartOffset) + " to "
                            + fmtHms(windowStartOffset + windowLenFinal) + " of " + totalStr + ".");
                    setStatus("Video loaded. Drag the green and red handles to choose your part, then press Extract clip.", "info");
                });
            } catch (Exception ex) { fail(ex); }
            finally { ui(this::clearBusy); }
        });
    }

    private void attachPreview(String mediaUrl) { attachPreview(mediaUrl, 0); }

    private void attachPreview(String mediaUrl, int attempt) {
        if (player != null) player.dispose();
        currentPreviewMediaUrl = mediaUrl;
        Media media = new Media(mediaUrl);
        final MediaPlayer p = new MediaPlayer(media);
        player = p;
        mediaView.setMediaPlayer(p);
        playPauseBtn.setDisable(false);
        extractBtn.setDisable(false);
        p.setOnReady(() -> {
            // The video fades in over the placeholder, the timeline and clip controls rise into
            // place, and the page scrolls so you can see all of them without hunting.
            boolean firstReveal = !controlsRow.isVisible();
            crossFade(videoEmptyState, mediaView);
            reveal(scrubber, controlsRow);
            if (firstReveal) ensureVisible(controlsRow, 24);
            double dur = p.getTotalDuration().toSeconds();
            scrubber.setDurationSeconds(dur);
            updateFieldsFromScrubber();
            timeLabel.setText(fmtHms(0) + " / " + fmtHms(dur));
            p.play(); // auto-start: confirms the stream actually plays, not just "ready"
            scheduleNeverStartedCheck(p, mediaUrl, attempt);
        });
        p.currentTimeProperty().addListener((o, a, b) -> ui(() -> {
            if (player != p) return;
            scrubber.setPlayheadSeconds(b.toSeconds());
            double dur = p.getTotalDuration().toSeconds();
            timeLabel.setText(fmtHms(b.toSeconds()) + " / " + fmtHms(dur));
            // Small epsilon: landing exactly ON the out point (e.g. by dragging the out handle
            // there) must not itself be treated as "played past the end" and re-paused instantly.
            if (p.getStatus() == MediaPlayer.Status.PLAYING && b.toSeconds() > scrubber.getOutSeconds() + 0.08) {
                p.pause();
                p.seek(Duration.seconds(scrubber.getOutSeconds()));
            }
        }));
        p.setOnEndOfMedia(this::showPlayIcon);
        p.statusProperty().addListener((o, a, b) -> ui(() -> {
            if (player != p) return;
            if (b == MediaPlayer.Status.PLAYING) {
                showPauseIcon();
                scheduleStallCheck(p, mediaUrl, attempt);
                scheduleFreezeCheck(p, mediaUrl, attempt);
            } else showPlayIcon();
        }));
        p.setOnError(() -> ui(() -> {
            if (player != p) return;
            String msg = p.getError() != null ? p.getError().getMessage() : "unknown playback error";
            if (attempt < 2) {
                logLine("Preview init failed (attempt " + (attempt + 1) + "/3): " + msg + " — retrying...");
                attachPreview(mediaUrl, attempt + 1);
            } else {
                logLine("Preview stream failed after retrying (" + msg + ") — downloading a fresh copy...");
                loadPreviewWindow(windowStartOffset);
            }
        }));
    }

    /** Covers the case where setOnError never fires AND status never reaches PLAYING at all —
     *  the stream can sit stuck in READY/PAUSED/STALLED indefinitely with no signal whatsoever.
     *  Without this, a broken stream looks identical to a working one (duration/metadata loads
     *  fine) but silently never plays, forever, with no recovery attempted. */
    private void scheduleNeverStartedCheck(MediaPlayer p, String mediaUrl, int attempt) {
        PauseTransition check = new PauseTransition(Duration.seconds(4));
        check.setOnFinished(e -> {
            if (player != p) return;
            if (p.getStatus() != MediaPlayer.Status.PLAYING) {
                if (attempt < 2) {
                    logLine("Preview never started playing (stuck in " + p.getStatus() + ") — retrying...");
                    attachPreview(mediaUrl, attempt + 1);
                } else {
                    logLine("Preview never started playing after retrying — downloading a fresh copy...");
                    loadPreviewWindow(windowStartOffset);
                }
            }
        });
        check.play();
    }

    /** Confirms playback actually advances, not just that the player reached READY/PLAYING —
     *  some failure modes report success but the position never moves. */
    private void scheduleStallCheck(MediaPlayer p, String mediaUrl, int attempt) {
        double startPos = p.getCurrentTime().toSeconds();
        PauseTransition check = new PauseTransition(Duration.seconds(2));
        check.setOnFinished(e -> {
            if (player != p || p.getStatus() != MediaPlayer.Status.PLAYING) return;
            double now = p.getCurrentTime().toSeconds();
            if (now - startPos < 0.2) {
                if (attempt < 2) {
                    logLine("Preview stalled (no playback progress) — retrying...");
                    attachPreview(mediaUrl, attempt + 1);
                } else {
                    logLine("Preview stream won't advance after retrying — downloading a fresh copy...");
                    loadPreviewWindow(windowStartOffset);
                }
            }
        });
        check.play();
    }

    /** JavaFX's bundled media engine can silently stop rendering video frames while audio keeps
     *  playing (no error fires). Detect this by comparing two snapshots a second apart and, if
     *  genuinely frozen while supposedly playing, recreate the player to recover. */
    private void scheduleFreezeCheck(MediaPlayer p, String mediaUrl, int attempt) {
        PauseTransition first = new PauseTransition(Duration.seconds(1.2));
        first.setOnFinished(e1 -> {
            if (player != p || p.getStatus() != MediaPlayer.Status.PLAYING) return;
            WritableImage snap1 = mediaView.snapshot(new SnapshotParameters(), null);
            PauseTransition second = new PauseTransition(Duration.seconds(1.0));
            second.setOnFinished(e2 -> {
                if (player != p || p.getStatus() != MediaPlayer.Status.PLAYING) return;
                WritableImage snap2 = mediaView.snapshot(new SnapshotParameters(), null);
                if (framesLookIdentical(snap1, snap2)) {
                    if (attempt < 2) {
                        logLine("Detected frozen video (audio-only playback) — recovering...");
                        attachPreview(mediaUrl, attempt + 1);
                    } else {
                        logLine("Video still frozen after retrying — downloading a fresh copy...");
                        loadPreviewWindow(windowStartOffset);
                    }
                }
            });
            second.play();
        });
        first.play();
    }

    private boolean framesLookIdentical(WritableImage a, WritableImage b) {
        int w = (int) a.getWidth(), h = (int) a.getHeight();
        if (w != (int) b.getWidth() || h != (int) b.getHeight() || w == 0 || h == 0) return false;
        PixelReader ra = a.getPixelReader(), rb = b.getPixelReader();
        int stepX = Math.max(1, w / 20), stepY = Math.max(1, h / 20);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                if (ra.getArgb(x, y) != rb.getArgb(x, y)) return false;
            }
        }
        return true;
    }

    /** Clears subtitle and watermark state tied to the previous clip so neither can leak into
     *  an export of a newly (re-)extracted clip with different content/timing. */
    private void resetDownstreamState() {
        cueEdited = false;
        currentSrt = null;
        cues = new ArrayList<>();
        cueListView.getItems().clear();
        transcriptPreview.clear();
        subtitleOverlayLabel.setText("");
        subtitlePreviewFor = null;

        watermarkClip = null;
        watermarkOverlay.clearImage();
        watermarkCheck.setSelected(false);
        watermarkStatus.setText("Skip this step if the clip has no watermark.");
    }

    private void runExtractClip() {
        if (videoStreamUrl == null) { alert("Load a video first."); return; }
        // Scrubber in/out are relative to the currently loaded preview window, not the full
        // source — offset back to absolute position in the source video before extracting.
        double s = windowStartOffset + scrubber.getInSeconds();
        double e = windowStartOffset + scrubber.getOutSeconds();
        if (e <= s) { setStatus("The end time must be after the start time.", "error"); return; }
        if (player != null) player.pause();
        setBusy("Cutting out your clip (" + fmtHms(e - s) + ")...");
        submit(() -> {
            try {
                Path out = workDir();
                long stamp = System.nanoTime();
                Path rawClip = out.resolve("clip_raw_" + stamp + ".mp4");
                Path clip = out.resolve("clip_" + stamp + ".mp4");
                String ffmpeg = findTool("ffmpeg", "ffmpeg.exe");
                // -ss before each -i seeks that remote stream via HTTP range requests, so only
                // the trimmed segment is fetched from video (and audio, if separate) — not the
                // whole source, however long it is.
                // JavaFX's decoder rejects non-mod-16 sizes, so dimensions are still forced to
                // mod-16 — by cropping the few leftover edge pixels (e.g. 1080 -> 1072 rows) rather
                // than scaling, which used to slightly stretch the whole picture.
                withStreamRefresh(() -> fetchSegment(ffmpeg, videoStreamUrl, audioStreamUrl, s, e - s,
                        "crop=trunc(iw/16)*16:trunc(ih/16)*16,setsar=1", false, "192k", rawClip));
                // Remote-seeked output carries a PTS offset JavaFX's player can't handle even
                // though it plays fine elsewhere — normalize it in a fast stream-copy pass before
                // this file gets played by the subtitle preview or watermark frame capture.
                runCommand(List.of(ffmpeg, "-y", "-i", rawClip.toString(),
                        "-avoid_negative_ts", "make_zero", "-fflags", "+genpts",
                        "-c", "copy", clip.toString()));
                Files.deleteIfExists(rawClip);
                currentClip = clip;
                ui(() -> {
                    currentClipSeconds = e - s;
                    resetDownstreamState();
                    setStatus("Clip ready (" + fmtHms(e - s) + "). Press Next to add subtitles, or Export to save it as is.", "success");
                    updateNextEnablement();
                    refreshGuides();
                    toast("Clip extracted");
                    nudge(nextBtn);
                });
            } catch (Exception ex) { fail(ex); }
            finally { ui(this::clearBusy); }
        });
    }

    // =========================================================================
    // Step 2: Subtitles
    // =========================================================================

    private Node buildStep2Subtitles() {
        fontField = new TextField("Arial");
        fontSize = new Spinner<>(12, 120, 52, 2);
        textColor = new ColorPicker(Color.WHITE);
        outlineColor = new ColorPicker(Color.BLACK);
        positionBox = new ComboBox<>();
        positionBox.getItems().addAll("Bottom", "Center", "Top");
        positionBox.setValue("Bottom");
        fontSize.setEditable(true);
        fontSize.setPrefWidth(90);
        positionBox.valueProperty().addListener((o, a, b) -> { updateSubtitleOverlayAlignment(); markStyleTouched(); });
        fontField.textProperty().addListener((o, a, b) -> { updateSubtitleOverlayStyle(); markStyleTouched(); });
        fontSize.valueProperty().addListener((o, a, b) -> { updateSubtitleOverlayStyle(); markStyleTouched(); });
        textColor.valueProperty().addListener((o, a, b) -> { updateSubtitleOverlayStyle(); markStyleTouched(); });
        outlineColor.valueProperty().addListener((o, a, b) -> { updateSubtitleOverlayStyle(); markStyleTouched(); });

        transcribeBtn = new Button("Generate subtitles");
        transcribeBtn.getStyleClass().add("primary-btn");
        transcribeBtn.setOnAction(e -> runTranscription());

        whisperModelBox = new ComboBox<>();
        whisperModelBox.getItems().addAll(MODEL_FAST, MODEL_BALANCED, MODEL_ACCURATE);
        whisperModelBox.setValue(MODEL_BALANCED);

        whisperLanguageBox = new ComboBox<>();
        whisperLanguageBox.getItems().addAll("Auto-detect", "English", "Hindi", "Telugu", "Tamil", "Kannada",
                "Malayalam", "Bengali", "Marathi", "Urdu", "Arabic", "Spanish", "French", "Portuguese", "Indonesian");
        whisperLanguageBox.setValue("Auto-detect");

        captionLengthBox = new ComboBox<>();
        captionLengthBox.getItems().addAll("Normal (sentence chunks)", "Short (Shorts / Reels style)");
        captionLengthBox.setValue("Normal (sentence chunks)");

        // Two groups in the order you use them: make the captions, then make them look right.
        GridPane genGrid = new GridPane();
        genGrid.setHgap(12); genGrid.setVgap(10);
        genGrid.add(field("Spoken language"), 0, 0); genGrid.add(whisperLanguageBox, 0, 1);
        genGrid.add(field("Accuracy"), 1, 0); genGrid.add(whisperModelBox, 1, 1);
        genGrid.add(field("Caption length"), 2, 0); genGrid.add(captionLengthBox, 2, 1);
        genGrid.add(transcribeBtn, 3, 1);
        Label genHint = new Label("Runs on this computer. Higher accuracy is slower; Short captions suit vertical Shorts and Reels.");
        genHint.getStyleClass().add("hint-label");
        genHint.setWrapText(true);
        VBox genGroup = new VBox(10, groupTitle("Generate"), genGrid, genHint);

        GridPane styleGrid = new GridPane();
        styleGrid.setHgap(12); styleGrid.setVgap(10);
        styleGrid.add(field("Font"), 0, 0); styleGrid.add(fontField, 0, 1);
        styleGrid.add(field("Size"), 1, 0); styleGrid.add(fontSize, 1, 1);
        styleGrid.add(field("Text colour"), 2, 0); styleGrid.add(textColor, 2, 1);
        styleGrid.add(field("Outline colour"), 3, 0); styleGrid.add(outlineColor, 3, 1);
        styleGrid.add(field("Position"), 4, 0); styleGrid.add(positionBox, 4, 1);
        VBox styleGroup = new VBox(10, groupTitle("Look"), styleGrid);

        subtitleMediaView = new MediaView();
        subtitleMediaView.setPreserveRatio(true);
        subtitleMediaView.setFitWidth(900);
        subtitleMediaView.setFitHeight(300);

        subtitleOverlayLabel = new Label();
        subtitleOverlayLabel.getStyleClass().add("subtitle-overlay-label");
        subtitleOverlayLabel.setWrapText(true);
        subtitleOverlayLabel.setMaxWidth(820);
        subtitleOverlayLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        // TextAlignment only centers *wrapped* multi-line text within the label's own box — a
        // short single line still hugs the left edge of that box unless the label's own content
        // alignment is also set, which is what was pinning captions to the bottom-left.
        subtitleOverlayLabel.setAlignment(Pos.CENTER);

        StackPane previewFrame = new StackPane(subtitleMediaView, subtitleOverlayLabel);
        previewFrame.getStyleClass().add("video-frame");
        previewFrame.setPrefHeight(310);
        applyRoundedClip(previewFrame, 10);
        StackPane.setMargin(subtitleOverlayLabel, new Insets(20));
        // Re-scale the caption preview whenever the displayed video size changes, so it matches
        // what the export burns in (size is defined relative to the video, not the window).
        subtitleMediaView.layoutBoundsProperty().addListener((o, a, b) -> updateSubtitleOverlayStyle());
        updateSubtitleOverlayAlignment();
        updateSubtitleOverlayStyle();

        subPlayIcon = new Polygon(0, 0, 0, 14, 12, 7);
        ((Polygon) subPlayIcon).setFill(Color.WHITE);
        HBox subPauseBars = new HBox(3, new Rectangle(4, 14, Color.WHITE), new Rectangle(4, 14, Color.WHITE));
        subPauseIcon = subPauseBars;
        subPauseIcon.setVisible(false);
        StackPane subIconStack = new StackPane(subPlayIcon, subPauseIcon);
        subtitlePlayPauseBtn = new Button();
        subtitlePlayPauseBtn.setGraphic(subIconStack);
        subtitlePlayPauseBtn.getStyleClass().add("icon-btn");
        subtitlePlayPauseBtn.setDisable(true);
        subtitlePlayPauseBtn.setOnAction(e -> toggleSubtitlePlayback());

        subtitleSeekSlider = new Slider(0, 1, 0);
        subtitleSeekSlider.setDisable(true);
        HBox.setHgrow(subtitleSeekSlider, Priority.ALWAYS);
        subtitleSeekSlider.setOnMouseReleased(e -> { if (subtitlePlayer != null) subtitlePlayer.seek(Duration.seconds(subtitleSeekSlider.getValue())); });

        HBox previewControls = new HBox(10, subtitlePlayPauseBtn, subtitleSeekSlider);
        previewControls.setAlignment(Pos.CENTER_LEFT);

        cueListView = new ListView<>();
        cueListView.setPrefHeight(200);
        Label noCues = new Label("No captions yet. Press Generate subtitles above.");
        noCues.getStyleClass().add("empty-state");
        cueListView.setPlaceholder(noCues);
        cueListView.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(SubtitleCue c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : fmtPrecise(c.start) + " → " + fmtPrecise(c.end) + "   " + c.text.replace("\n", " / "));
            }
        });
        cueListView.getSelectionModel().selectedItemProperty().addListener((o, a, cue) -> {
            if (cue == null) return;
            suppressCueSync = true;
            cueStartField.setText(fmtPrecise(cue.start));
            cueEndField.setText(fmtPrecise(cue.end));
            cueTextArea.setText(cue.text);
            suppressCueSync = false;
            if (subtitlePlayer != null) subtitlePlayer.seek(Duration.seconds(cue.start));
        });

        cueStartField = new TextField();
        cueEndField = new TextField();
        cueStartField.setPrefWidth(110);
        cueEndField.setPrefWidth(110);
        cueTextArea = new TextArea();
        cueTextArea.setPrefRowCount(3);
        cueTextArea.setWrapText(true);

        // Text edits apply immediately to the selected cue as you type — no button, no risk
        // of a later row-selection silently discarding what you typed.
        cueTextArea.textProperty().addListener((o, a, b) -> {
            if (suppressCueSync) return;
            SubtitleCue cue = cueListView.getSelectionModel().getSelectedItem();
            if (cue == null) return;
            cue.text = b;
            cueEdited = true;
            cueListView.refresh();
            persistCues();
            refreshGuides();
        });
        cueStartField.focusedProperty().addListener((o, was, is) -> { if (!is) applyCueTiming(); });
        cueEndField.focusedProperty().addListener((o, was, is) -> { if (!is) applyCueTiming(); });

        cueTextArea.setPromptText("Select a caption on the left to edit its text");
        Button deleteCueBtn = new Button("Delete caption");
        deleteCueBtn.getStyleClass().add("ghost-btn");
        deleteCueBtn.setOnAction(e -> deleteSelectedCue());
        Button addCueBtn = new Button("Add caption here");
        addCueBtn.setTooltip(new Tooltip("Adds a 2-second caption at the current preview position"));
        addCueBtn.setOnAction(e -> addNewCue());

        HBox cueEditRow = new HBox(10, field("Start"), cueStartField, field("End"), cueEndField);
        cueEditRow.setAlignment(Pos.CENTER_LEFT);
        HBox cueActionRow = new HBox(10, deleteCueBtn, addCueBtn);
        VBox cueEditor = new VBox(8, cueEditRow, cueTextArea, cueActionRow);
        HBox.setHgrow(cueListView, Priority.ALWAYS);
        HBox.setHgrow(cueEditor, Priority.ALWAYS);
        HBox editorRow = new HBox(16, cueListView, cueEditor);

        transcriptPreview = new TextArea();
        transcriptPreview.setEditable(false);
        transcriptPreview.setVisible(false);
        transcriptPreview.setManaged(false);

        Label editHint = new Label("Pick a caption to jump to it in the preview. Edits save as you type and are used in the export.");
        editHint.getStyleClass().add("hint-label");
        editHint.setWrapText(true);
        reviewGroup = new VBox(10, groupTitle("Check and edit"), previewFrame, previewControls, editHint, editorRow, transcriptPreview);

        return card(genGroup, separator(), reviewGroup, separator(), styleGroup);
    }

    private Region separator() {
        Region r = new Region();
        r.getStyleClass().add("group-separator");
        return r;
    }

    private VBox reviewGroup;

    private void markStyleTouched() { styleTouched = true; refreshGuides(); }

    private static final String MODEL_FAST = "Fast", MODEL_BALANCED = "Balanced (recommended)", MODEL_ACCURATE = "Most accurate (slow)";

    private void attachSubtitlePreview(Path clip) { attachSubtitlePreview(clip, 0); }

    private void attachSubtitlePreview(Path clip, int attempt) {
        if (subtitlePlayer != null) subtitlePlayer.dispose();
        Media media = new Media(clip.toUri().toString());
        final MediaPlayer sp = new MediaPlayer(media);
        subtitlePlayer = sp;
        subtitleMediaView.setMediaPlayer(sp);
        subtitlePlayPauseBtn.setDisable(false);
        subtitleSeekSlider.setDisable(false);
        sp.setOnReady(() -> {
            subtitleSeekSlider.setMax(sp.getTotalDuration().toSeconds());
            updateSubtitleOverlayStyle();
            sp.play();
            scheduleSubNeverStartedCheck(sp, clip, attempt);
        });
        sp.currentTimeProperty().addListener((o, a, b) -> ui(() -> {
            if (subtitlePlayer != sp) return;
            if (!subtitleSeekSlider.isValueChanging() && !subtitleSeekSlider.isPressed()) subtitleSeekSlider.setValue(b.toSeconds());
            updateActiveCueLabel(b.toSeconds());
        }));
        sp.setOnEndOfMedia(this::showSubPlayIcon);
        sp.statusProperty().addListener((o, a, b) -> ui(() -> {
            if (subtitlePlayer != sp) return;
            if (b == MediaPlayer.Status.PLAYING) {
                showSubPauseIcon();
                scheduleSubStallCheck(sp, clip, attempt);
                scheduleSubFreezeCheck(sp, clip, attempt);
            } else showSubPlayIcon();
        }));
        sp.setOnError(() -> ui(() -> {
            if (subtitlePlayer != sp) return;
            String msg = sp.getError() != null ? sp.getError().getMessage() : "unknown";
            if (attempt < 2) {
                logLine("Subtitle preview init failed (attempt " + (attempt + 1) + "/3): " + msg + " — retrying...");
                attachSubtitlePreview(clip, attempt + 1);
            } else {
                logLine("Subtitle preview playback error after retrying: " + msg);
            }
        }));
        subtitlePreviewFor = clip;
        showSubPlayIcon();
    }

    /** Same safety nets as the main preview player (see scheduleNeverStartedCheck/
     *  scheduleStallCheck/scheduleFreezeCheck) — the subtitle preview plays a locally extracted
     *  file through the same JavaFX engine and is just as prone to silently sticking with no
     *  error, which is why the "Play" button sometimes did nothing: play() was being called, but
     *  the player was stuck, with no way to tell short of seeking (which is a different code path
     *  that happens to repaint a frame even while effectively stalled). */
    private void scheduleSubNeverStartedCheck(MediaPlayer sp, Path clip, int attempt) {
        PauseTransition check = new PauseTransition(Duration.seconds(4));
        check.setOnFinished(e -> {
            if (subtitlePlayer != sp) return;
            if (sp.getStatus() != MediaPlayer.Status.PLAYING) {
                if (attempt < 2) {
                    logLine("Subtitle preview never started playing — retrying...");
                    attachSubtitlePreview(clip, attempt + 1);
                } else {
                    logLine("Subtitle preview won't start after retrying.");
                }
            }
        });
        check.play();
    }

    private void scheduleSubStallCheck(MediaPlayer sp, Path clip, int attempt) {
        double startPos = sp.getCurrentTime().toSeconds();
        PauseTransition check = new PauseTransition(Duration.seconds(2));
        check.setOnFinished(e -> {
            if (subtitlePlayer != sp || sp.getStatus() != MediaPlayer.Status.PLAYING) return;
            if (sp.getCurrentTime().toSeconds() - startPos < 0.2) {
                if (attempt < 2) {
                    logLine("Subtitle preview stalled — retrying...");
                    attachSubtitlePreview(clip, attempt + 1);
                } else {
                    logLine("Subtitle preview still stalled after retrying.");
                }
            }
        });
        check.play();
    }

    private void scheduleSubFreezeCheck(MediaPlayer sp, Path clip, int attempt) {
        PauseTransition first = new PauseTransition(Duration.seconds(1.2));
        first.setOnFinished(e1 -> {
            if (subtitlePlayer != sp || sp.getStatus() != MediaPlayer.Status.PLAYING) return;
            WritableImage snap1 = subtitleMediaView.snapshot(new SnapshotParameters(), null);
            PauseTransition second = new PauseTransition(Duration.seconds(1.0));
            second.setOnFinished(e2 -> {
                if (subtitlePlayer != sp || sp.getStatus() != MediaPlayer.Status.PLAYING) return;
                WritableImage snap2 = subtitleMediaView.snapshot(new SnapshotParameters(), null);
                if (framesLookIdentical(snap1, snap2)) {
                    if (attempt < 2) {
                        logLine("Subtitle preview frozen — recovering...");
                        attachSubtitlePreview(clip, attempt + 1);
                    } else {
                        logLine("Subtitle preview still frozen after retrying.");
                    }
                }
            });
            second.play();
        });
        first.play();
    }

    private void toggleSubtitlePlayback() {
        if (subtitlePlayer == null) return;
        if (subtitlePlayer.getStatus() == MediaPlayer.Status.PLAYING) { subtitlePlayer.pause(); showSubPlayIcon(); }
        else { subtitlePlayer.play(); showSubPauseIcon(); }
    }

    private void showSubPlayIcon() { subPlayIcon.setVisible(true); subPauseIcon.setVisible(false); }
    private void showSubPauseIcon() { subPlayIcon.setVisible(false); subPauseIcon.setVisible(true); }

    private void updateActiveCueLabel(double t) {
        String txt = "";
        for (SubtitleCue c : cues) {
            if (t >= c.start && t <= c.end) { txt = c.text; break; }
        }
        subtitleOverlayLabel.setText(txt);
    }

    private void updateSubtitleOverlayAlignment() {
        Pos pos = switch (positionBox.getValue()) {
            case "Top" -> Pos.TOP_CENTER;
            case "Center" -> Pos.CENTER;
            default -> Pos.BOTTOM_CENTER;
        };
        StackPane.setAlignment(subtitleOverlayLabel, pos);
    }

    private void updateSubtitleOverlayStyle() {
        String textHex = toWebColor(textColor.getValue());
        String outlineHex = toWebColor(outlineColor.getValue());
        // "Size" means: font size on a video whose short side is 1080px — the export scales it
        // to the clip's real resolution, so the preview applies the same ratio to the displayed
        // video. The old fixed size/2 didn't match the burned-in result at all.
        double shown = subtitleShortSideOnScreen();
        double px = shown > 0 ? fontSize.getValue() * shown / 1080.0 : fontSize.getValue() * 400 / 1080.0;
        double outlinePx = Math.max(1, 3 * (shown > 0 ? shown : 400) / 1080.0);
        subtitleOverlayLabel.setStyle(
                "-fx-font-family: '" + fontField.getText().replace("'", "") + "';"
                + "-fx-font-size: " + String.format(Locale.US, "%.1f", Math.max(8, px)) + "px;"
                + "-fx-text-fill: " + textHex + ";"
                + "-fx-font-weight: bold;"
                + "-fx-effect: dropshadow(one-pass-box, " + outlineHex + ", "
                + String.format(Locale.US, "%.1f", outlinePx) + ", 1.0, 0, 0);");
    }

    /** Short side, in screen pixels, of the video as currently displayed in the subtitle preview. */
    private double subtitleShortSideOnScreen() {
        if (subtitlePlayer == null || subtitlePlayer.getMedia() == null) return 0;
        int w = subtitlePlayer.getMedia().getWidth(), h = subtitlePlayer.getMedia().getHeight();
        if (w <= 0 || h <= 0) return 0;
        double scale = Math.min(subtitleMediaView.getFitWidth() / w, subtitleMediaView.getFitHeight() / h);
        var bounds = subtitleMediaView.getLayoutBounds();
        if (bounds.getWidth() > 0 && bounds.getHeight() > 0) scale = Math.min(bounds.getWidth() / w, bounds.getHeight() / h);
        return Math.min(w, h) * scale;
    }

    private String toWebColor(Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private void applyCueTiming() {
        if (suppressCueSync) return;
        SubtitleCue cue = cueListView.getSelectionModel().getSelectedItem();
        if (cue == null) return;
        try {
            double s = parseTime(cueStartField.getText());
            double e = parseTime(cueEndField.getText());
            // Just clicking into a timing field and away used to re-apply whole-second text and
            // round the cue's real timing, knocking captions out of sync. Skip no-op edits.
            if (Math.abs(s - cue.start) < 0.0005 && Math.abs(e - cue.end) < 0.0005) return;
            if (e <= s) throw new IllegalArgumentException("End must be after start.");
            cue.start = s; cue.end = e;
            cues.sort(Comparator.comparingDouble(c -> c.start));
            suppressCueSync = true;
            cueListView.getItems().setAll(cues);
            cueListView.getSelectionModel().select(cue);
            suppressCueSync = false;
            persistCues();
        } catch (Exception ex) {
            alert(ex.getMessage());
            suppressCueSync = true;
            cueStartField.setText(fmtPrecise(cue.start));
            cueEndField.setText(fmtPrecise(cue.end));
            suppressCueSync = false;
        }
    }

    private void deleteSelectedCue() {
        SubtitleCue cue = cueListView.getSelectionModel().getSelectedItem();
        if (cue == null) return;
        cues.remove(cue);
        cueListView.getItems().setAll(cues);
        persistCues();
    }

    private void addNewCue() {
        double start = subtitlePlayer != null ? subtitlePlayer.getCurrentTime().toSeconds() : 0;
        SubtitleCue cue = new SubtitleCue(start, start + 2, "New subtitle");
        cues.add(cue);
        cues.sort(Comparator.comparingDouble(c -> c.start));
        cueListView.getItems().setAll(cues);
        cueListView.getSelectionModel().select(cue);
        persistCues();
    }

    private void persistCues() {
        try {
            if (currentSrt == null) currentSrt = workDir().resolve("whisper").resolve("subtitles.srt");
            Files.createDirectories(currentSrt.getParent());
            String srt = cuesToSrt(cues);
            Files.writeString(currentSrt, srt, StandardCharsets.UTF_8);
            transcriptPreview.setText(srt);
        } catch (IOException ex) { fail(ex); }
    }

    private String cuesToSrt(List<SubtitleCue> list) {
        StringBuilder b = new StringBuilder();
        int i = 1;
        for (SubtitleCue c : list) {
            // A blank line inside a cue's text is the SRT block separator, so it used to split
            // the cue in two (or drop it) on the next read. Empty cues have nothing to show.
            String text = sanitizeCueText(c.text);
            if (text.isEmpty()) continue;
            b.append(i++).append("\n").append(srtTime(c.start)).append(" --> ").append(srtTime(c.end))
                    .append("\n").append(text).append("\n\n");
        }
        return b.toString();
    }

    private String sanitizeCueText(String t) {
        if (t == null) return "";
        return t.replace("\r", "").replaceAll("\n[ \t]*(\n[ \t]*)+", "\n").strip();
    }

    private boolean hasCaptions() {
        return cues.stream().anyMatch(c -> !sanitizeCueText(c.text).isEmpty());
    }

    private String srtTime(double sec) {
        // Round once on total milliseconds — rounding only the fraction could yield ms = 1000
        // (e.g. 1.9996s -> "00:00:01,1000"), which is an invalid timestamp.
        long totalMs = Math.round(Math.max(0, sec) * 1000);
        long ms = totalMs % 1000, total = totalMs / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", total / 3600, (total % 3600) / 60, total % 60, ms);
    }

    private List<SubtitleCue> parseSrt(String content) {
        List<SubtitleCue> list = new ArrayList<>();
        String[] blocks = content.replace("\r", "").split("\\n\\n+");
        for (String block : blocks) {
            String[] lines = block.strip().split("\\n");
            if (lines.length < 3) continue;
            String[] times = lines[1].split("\\s+-->\\s+");
            if (times.length != 2) continue;
            double s, e;
            try { s = parseSrtTime(times[0]); e = parseSrtTime(times[1]); }
            catch (RuntimeException bad) { continue; } // skip a malformed block instead of losing all cues
            String text = String.join("\n", Arrays.copyOfRange(lines, 2, lines.length));
            list.add(new SubtitleCue(s, e, text));
        }
        return list;
    }

    private double parseSrtTime(String t) {
        String[] p = t.trim().replace(',', '.').split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2]);
    }

    private static final Map<String, String> LANGUAGE_CODES = Map.ofEntries(
            Map.entry("Auto-detect", "auto"), Map.entry("English", "en"), Map.entry("Hindi", "hi"),
            Map.entry("Urdu", "ur"), Map.entry("Arabic", "ar"), Map.entry("Spanish", "es"),
            Map.entry("French", "fr"), Map.entry("Portuguese", "pt"), Map.entry("Indonesian", "id"));

    private void runTranscription() {
        if (currentClip == null) { setStatus("Extract a clip first (step 1).", "error"); return; }
        String modelName = switch (whisperModelBox.getValue()) {
            case MODEL_FAST -> "base";
            case MODEL_ACCURATE -> "medium";
            default -> "small";
        };
        String langCode = LANGUAGE_CODES.getOrDefault(whisperLanguageBox.getValue(), "auto");
        String captionMode = captionLengthBox.getValue().startsWith("Short") ? "short" : "normal";
        Path clipToTranscribe = currentClip;
        setBusy("Writing subtitles from the speech in your clip...");
        submit(() -> {
            try {
                Path out = workDir().resolve("whisper");
                Files.createDirectories(out);
                String py = findWhisperPython();
                // Uses faster-whisper when it's installed (typically ~4x faster for the same
                // accuracy), otherwise openai-whisper. Either way it runs on the GPU in fp16 when
                // CUDA is available — the old script forced fp16=False, which wastes a GPU.
                // "short" mode splits on word timestamps into Shorts-sized captions.
                String script = """
import sys
clip, out, model_name, language, mode = sys.argv[1:6]
short = mode == "short"
MAX_CHARS, MAX_DUR = 32, 2.5

def ts(x):
    total_ms = int(round(max(0.0, float(x)) * 1000))
    ms = total_ms % 1000; total = total_ms // 1000
    return f"{total//3600:02d}:{(total%3600)//60:02d}:{total%60:02d},{ms:03d}"

segments = []
engine, device = None, "cpu"
try:
    from faster_whisper import WhisperModel
    engine = "faster-whisper"
except ImportError:
    pass

if engine:
    cuda = False
    try:
        import ctranslate2
        cuda = ctranslate2.get_cuda_device_count() > 0
    except Exception:
        cuda = False
    try:
        model = WhisperModel(model_name, device="cuda" if cuda else "cpu", compute_type="float16" if cuda else "int8")
    except Exception as e:
        print(f"GPU init failed ({e}), using CPU", flush=True)
        cuda = False
        model = WhisperModel(model_name, device="cpu", compute_type="int8")
    device = "cuda" if cuda else "cpu"
    print(f"ENGINE {engine} on {device}", flush=True)
    segs, info = model.transcribe(clip, language=None if language == "auto" else language,
                                  word_timestamps=short, vad_filter=True)
    for sg in segs:
        words = [(w.start, w.end, w.word) for w in (sg.words or [])] if short else None
        segments.append((sg.start, sg.end, sg.text, words))
        print(f"PROGRESS {sg.end:.1f}", flush=True)
else:
    import whisper, torch
    cuda = torch.cuda.is_available()
    device = "cuda" if cuda else "cpu"
    engine = "whisper"
    print(f"ENGINE {engine} on {device}", flush=True)
    model = whisper.load_model(model_name, device=device)
    kwargs = {"fp16": cuda, "word_timestamps": short}
    if language != "auto":
        kwargs["language"] = language
    result = model.transcribe(clip, **kwargs)
    for sg in result["segments"]:
        words = [(w["start"], w["end"], w["word"]) for w in sg.get("words", [])] if short else None
        segments.append((sg["start"], sg["end"], sg["text"], words))

cues = []
for start, end, text, words in segments:
    if short and words:
        cur, cs, ce = "", None, None
        for ws, we, w in words:
            w = w.strip()
            if not w:
                continue
            cand = (cur + " " + w).strip()
            if cur and (len(cand) > MAX_CHARS or (we - cs) > MAX_DUR):
                cues.append((cs, ce, cur))
                cur, cs = w, ws
            else:
                cur = cand
                if cs is None:
                    cs = ws
            ce = we
        if cur:
            cues.append((cs, ce, cur))
    else:
        t = text.strip()
        if t:
            cues.append((start, end, t))

with open(out, "w", encoding="utf-8") as f:
    for i, (s0, e0, t) in enumerate(cues, 1):
        if e0 <= s0:
            e0 = s0 + 0.3
        f.write(f"{i}\\n{ts(s0)} --> {ts(e0)}\\n{t}\\n\\n")
""";
                Path tmp = out.resolve("make_srt.py");
                Files.writeString(tmp, script, StandardCharsets.UTF_8);
                Path srt = out.resolve("subtitles.srt");
                runCommand(List.of(py, tmp.toString(), clipToTranscribe.toString(), srt.toString(), modelName, langCode, captionMode),
                        line -> {
                            if (line.startsWith("PROGRESS ")) {
                                String sec = line.substring(9).trim();
                                busyLabel.setText("Writing subtitles from the speech in your clip... " + sec + "s transcribed");
                            } else logLine(line);
                        });
                currentSrt = srt;
                String content = Files.readString(currentSrt, StandardCharsets.UTF_8);
                ui(() -> {
                    cues = parseSrt(content);
                    cueListView.getItems().setAll(cues);
                    transcriptPreview.setText(content);
                    setStatus(cues.isEmpty()
                            ? "No speech was found in this clip, so there are no captions. You can add them by hand or press Next."
                            : "Subtitles ready: " + cues.size() + " captions. Play the preview to check them.",
                            cues.isEmpty() ? "info" : "success");
                    refreshGuides();
                    if (!cues.isEmpty()) {
                        toast("Subtitles generated");
                        // Take you straight to checking them: scroll to the preview, select the
                        // first caption and play from the start so captions appear live.
                        ensureVisible(reviewGroup, 16);
                        cueListView.getSelectionModel().selectFirst();
                        if (subtitlePlayer != null) { subtitlePlayer.seek(Duration.ZERO); subtitlePlayer.play(); }
                    }
                });
            } catch (Exception ex) { fail(ex); }
            finally { ui(this::clearBusy); }
        });
    }

    // =========================================================================
    // Step 3: Watermark removal (AI inpainting)
    // =========================================================================

    private Node buildStep3Watermark() {
        watermarkCheck = new CheckBox("This clip has a watermark I want removed");
        watermarkOverlay = new WatermarkBoxOverlay();
        watermarkOverlay.setDisable(true);

        watermarkCheck.getStyleClass().add("toggle-row");
        captureFrameBtn = new Button("Refresh Frame");
        captureFrameBtn.getStyleClass().add("ghost-btn");
        captureFrameBtn.setOnAction(e -> captureFrame());
        removeWatermarkBtn = new Button("Remove Watermark (AI)");
        removeWatermarkBtn.getStyleClass().add("primary-btn");
        removeWatermarkBtn.setDisable(true);
        removeWatermarkBtn.setOnAction(e -> runRemoveWatermark());

        watermarkStatus = new Label("Skip this step if the clip has no watermark.");
        watermarkStatus.getStyleClass().add("hint-label");
        watermarkStatus.setWrapText(true);

        watermarkCheck.selectedProperty().addListener((o, was, is) -> {
            watermarkOverlay.setDisable(!is);
            captureFrameBtn.setDisable(!is);
            removeWatermarkBtn.setDisable(!is);
            if (is && currentClip != null && !watermarkOverlay.hasImage()) captureFrame();
        });
        captureFrameBtn.setDisable(true);

        Label hint = new Label("Drag the box over the watermark on the frame below, resize it with the corner handle, then remove it. This uses a local AI model to reconstruct the background — it works best on a static logo in a fixed position, may take a while per frame, and won't be perfect on complex/moving watermarks.");
        hint.getStyleClass().add("hint-label");
        hint.setWrapText(true);

        HBox actions = new HBox(10, captureFrameBtn, removeWatermarkBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        StackPane frameHolder = new StackPane(watermarkOverlay);
        frameHolder.getStyleClass().add("video-frame");
        frameHolder.setPrefHeight(420);
        applyRoundedClip(frameHolder, 8);

        // Result preview: hidden until a watermark removal actually completes, so you can play
        // back the cleaned clip directly here and confirm the watermark is really gone and
        // quality looks right, instead of only seeing a status-text line.
        watermarkResultView = new MediaView();
        watermarkResultView.setPreserveRatio(true);
        watermarkResultView.setFitWidth(900);
        watermarkResultView.setFitHeight(400);
        StackPane resultFrame = new StackPane(watermarkResultView);
        resultFrame.getStyleClass().add("video-frame");
        resultFrame.setPrefHeight(420);
        applyRoundedClip(resultFrame, 8);

        wmPlayIcon = new Polygon(0, 0, 0, 14, 12, 7);
        ((Polygon) wmPlayIcon).setFill(Color.web("#EDECE7"));
        HBox wmPauseBars = new HBox(3, new Rectangle(4, 14, Color.web("#EDECE7")), new Rectangle(4, 14, Color.web("#EDECE7")));
        wmPauseIcon = wmPauseBars;
        wmPauseIcon.setVisible(false);
        StackPane wmIconStack = new StackPane(wmPlayIcon, wmPauseIcon);
        watermarkResultPlayBtn = new Button();
        watermarkResultPlayBtn.setGraphic(wmIconStack);
        watermarkResultPlayBtn.getStyleClass().add("icon-btn");
        watermarkResultPlayBtn.setOnAction(e -> toggleWatermarkResultPlayback());

        Label resultLabel = new Label("Result preview (after removal):");
        resultLabel.getStyleClass().add("group-title");
        HBox resultControls = new HBox(10, watermarkResultPlayBtn);
        resultControls.setAlignment(Pos.CENTER_LEFT);
        watermarkResultSection = new VBox(10, separator(), resultLabel, resultFrame, resultControls);
        watermarkResultSection.setManaged(false);
        watermarkResultSection.setVisible(false);

        VBox intro = new VBox(6, watermarkCheck, hint);
        return card(intro, frameHolder, actions, watermarkStatus, watermarkResultSection);
    }

    private void captureFrame() {
        if (currentClip == null) return;
        submit(() -> {
            try {
                Path frame = workDir().resolve("wm_frame_" + System.currentTimeMillis() + ".png");
                String ffmpeg = findTool("ffmpeg", "ffmpeg.exe");
                runCommand(List.of(ffmpeg, "-y", "-i", currentClip.toString(), "-frames:v", "1", "-q:v", "2", frame.toString()));
                Image img = new Image(frame.toUri().toString());
                ui(() -> watermarkOverlay.setImage(img, 900, 400));
            } catch (Exception ex) { fail(ex); }
        });
    }

    private void runRemoveWatermark() {
        if (currentClip == null) { alert("Extract a clip first."); return; }
        if (!watermarkOverlay.hasImage()) { alert("Capture a frame first."); return; }
        int[] box = watermarkOverlay.getNativeBox();
        setBusy("Removing watermark (AI video inpainting)...");
        submit(() -> {
            try {
                Path out = workDir().resolve("watermark_" + System.currentTimeMillis());
                Path cropDir = out.resolve("crop");
                Path patchDir = out.resolve("patches");
                Files.createDirectories(cropDir);
                Files.createDirectories(patchDir);

                String ffmpeg = findTool("ffmpeg", "ffmpeg.exe");
                String fps = ffprobeFps(currentClip);
                int[] res = ffprobeResolution(currentClip);
                int W = res[0], H = res[1];

                // Only the watermark region (plus a wide surrounding context) is ever extracted,
                // processed and composited — never the full frame. ffmpeg does the crop and the
                // final composite (fast, native C code); Python/GPU only ever touches the small
                // crop, never decodes or re-encodes a full-resolution frame.
                int x = box[0], y = box[1], w = box[2], h = box[3];
                final int MASK_PAD = 10, CONTEXT = 100;
                int mx0 = Math.max(0, x - MASK_PAD), my0 = Math.max(0, y - MASK_PAD);
                int mx1 = Math.min(W, x + w + MASK_PAD), my1 = Math.min(H, y + h + MASK_PAD);
                int cx0 = Math.max(0, mx0 - CONTEXT), cy0 = Math.max(0, my0 - CONTEXT);
                int cx1 = Math.min(W, mx1 + CONTEXT), cy1 = Math.min(H, my1 + CONTEXT);
                int cw = cx1 - cx0, ch = cy1 - cy0;
                int rmx0 = mx0 - cx0, rmy0 = my0 - cy0, rmx1 = mx1 - cx0, rmy1 = my1 - cy0;

                runCommand(List.of(ffmpeg, "-y", "-i", currentClip.toString(),
                        "-vf", "crop=" + cw + ":" + ch + ":" + cx0 + ":" + cy0,
                        "-qscale:v", "2", cropDir.resolve("frame_%06d.png").toString()));

                String py = findWatermarkPython();
                String script = """
import sys, os, gc, shutil
from PIL import Image, ImageFilter
import numpy as np
import torch
from propainter.propainter_video import (
    RawFrameSequencer, RawMaskSequencer, ScaledProPainterIterator, FilePathDirSequencer
)

crop_dir, patch_dir, rmx0, rmy0, rmx1, rmy1 = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6])
files = sorted(os.listdir(crop_dir))
total = len(files)

FEATHER = 12
CHUNK = 120
OVERLAP = 12

sample = Image.open(os.path.join(crop_dir, files[0]))
cw, ch = sample.size
mask_arr = np.zeros((ch, cw), dtype=np.uint8)
mask_arr[rmy0:rmy1, rmx0:rmx1] = 255
mask_img = Image.fromarray(mask_arr)
alpha = np.array(Image.fromarray(mask_arr).filter(ImageFilter.GaussianBlur(FEATHER)))

mask_tmp = os.path.join(patch_dir, "_mask_src")
chunk_f = os.path.join(patch_dir, "_chunk_f")
chunk_m = os.path.join(patch_dir, "_chunk_m")
os.makedirs(mask_tmp, exist_ok=True)
for f in files:
    mask_img.save(os.path.join(mask_tmp, f))

# Processed in fixed-size chunks, each with its own fresh model iterator and an explicit
# GPU memory release (del + empty_cache) right after — this is what keeps memory flat and
# predictable across arbitrarily long clips instead of slowly climbing for the whole run,
# and guarantees the GPU is fully free again the moment this one edit finishes, so the next
# edit the user runs always starts with the full VRAM budget available, never leftovers.
# A small OVERLAP of real preceding frames is fed into each chunk (then discarded from the
# output) purely so the model has temporal context right at the chunk boundary.
done = 0
start = 0
while start < total:
    end = min(start + CHUNK, total)
    ctx_start = max(0, start - OVERLAP)
    chunk_files = files[ctx_start:end]

    for d in (chunk_f, chunk_m):
        if os.path.exists(d): shutil.rmtree(d)
        os.makedirs(d)
    for f in chunk_files:
        shutil.copy2(os.path.join(crop_dir, f), os.path.join(chunk_f, f))
        shutil.copy2(os.path.join(mask_tmp, f), os.path.join(chunk_m, f))

    raw_frames = RawFrameSequencer(data=FilePathDirSequencer(dir_path=chunk_f))
    raw_masks = RawMaskSequencer(data=FilePathDirSequencer(dir_path=chunk_m))
    it = ScaledProPainterIterator(raw_frames=raw_frames, raw_masks=raw_masks,
                                   image_resize_ratio=1.0, mask_dilation=4, use_cuda=True)

    skip = start - ctx_start
    local_idx = 0
    for piece in it:
        for patch in piece:
            if local_idx >= skip:
                fname = chunk_files[local_idx]
                # RGBA patch: colour is the model's output, alpha is the feathered mask —
                # ffmpeg's overlay filter does the actual per-pixel blend against the
                # original frame natively, so Python never touches a full-res frame.
                rgba = np.dstack([patch.astype(np.uint8), alpha])
                Image.fromarray(rgba, mode="RGBA").save(os.path.join(patch_dir, fname))
                done += 1
                print(f"PROGRESS {done} {total}", flush=True)
            local_idx += 1

    del it, raw_frames, raw_masks
    gc.collect()
    torch.cuda.empty_cache()
    start = end

for d in (mask_tmp, chunk_f, chunk_m):
    shutil.rmtree(d, ignore_errors=True)
""";
                Path scriptPath = out.resolve("inpaint.py");
                Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

                runCommand(List.of(py, scriptPath.toString(), cropDir.toString(), patchDir.toString(),
                        String.valueOf(rmx0), String.valueOf(rmy0), String.valueOf(rmx1), String.valueOf(rmy1)),
                        line -> {
                            if (line.startsWith("PROGRESS")) {
                                String[] parts = line.split("\\s+");
                                ui(() -> setBusy("Removing watermark: frame " + parts[1] + " / " + parts[2]));
                            } else {
                                logLine(line);
                            }
                        });

                Path cleaned = workDir().resolve("clean_" + System.currentTimeMillis() + ".mp4");
                encodeWithFallback(enc -> {
                    List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-i", currentClip.toString(),
                            "-framerate", fps, "-i", patchDir.resolve("frame_%06d.png").toString(),
                            "-filter_complex", "[1:v]format=rgba[ov];[0:v][ov]overlay=x=" + cx0 + ":y=" + cy0 + ":format=auto[outv]",
                            "-map", "[outv]", "-map", "0:a?"));
                    cmd.addAll(videoEncoderArgs(enc, false));
                    cmd.addAll(List.of("-c:a", "aac", "-movflags", "+faststart", "-shortest", cleaned.toString()));
                    return cmd;
                });

                watermarkClip = cleaned;
                ui(() -> {
                    watermarkStatus.setText("Watermark removed: " + cleaned.getFileName());
                    watermarkResultSection.setManaged(true);
                    watermarkResultSection.setVisible(true);
                    attachWatermarkResultPreview(cleaned);
                    alert("Watermark removed successfully! Scroll down to preview the cleaned result.");
                });
            } catch (Exception ex) { fail(ex); }
            finally { ui(this::clearBusy); }
        });
    }

    private void attachWatermarkResultPreview(Path clip) { attachWatermarkResultPreview(clip, 0); }

    private void attachWatermarkResultPreview(Path clip, int attempt) {
        if (watermarkResultPlayer != null) watermarkResultPlayer.dispose();
        Media media = new Media(clip.toUri().toString());
        final MediaPlayer wp = new MediaPlayer(media);
        watermarkResultPlayer = wp;
        watermarkResultView.setMediaPlayer(wp);
        wp.setOnEndOfMedia(this::showWmPlayIcon);
        wp.statusProperty().addListener((o, a, b) -> ui(() -> {
            if (watermarkResultPlayer != wp) return;
            if (b == MediaPlayer.Status.PLAYING) showWmPauseIcon(); else showWmPlayIcon();
        }));
        wp.setOnError(() -> ui(() -> {
            if (watermarkResultPlayer != wp) return;
            String msg = wp.getError() != null ? wp.getError().getMessage() : "unknown";
            if (attempt < 2) {
                logLine("Watermark result preview failed (attempt " + (attempt + 1) + "/3): " + msg + " — retrying...");
                attachWatermarkResultPreview(clip, attempt + 1);
            } else {
                logLine("Watermark result preview failed after retrying: " + msg);
            }
        }));
        showWmPlayIcon();
    }

    private void toggleWatermarkResultPlayback() {
        if (watermarkResultPlayer == null) return;
        if (watermarkResultPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            watermarkResultPlayer.pause();
            showWmPlayIcon();
        } else {
            watermarkResultPlayer.play();
            showWmPauseIcon();
        }
    }

    private void showWmPlayIcon() { wmPlayIcon.setVisible(true); wmPauseIcon.setVisible(false); }
    private void showWmPauseIcon() { wmPlayIcon.setVisible(false); wmPauseIcon.setVisible(true); }

    private String ffprobeFps(Path video) throws Exception {
        String ffprobe = findTool("ffprobe", "ffprobe.exe");
        Process p = new ProcessBuilder(ffprobe, "-v", "0", "-select_streams", "v:0",
                "-of", "csv=p=0", "-show_entries", "stream=r_frame_rate", video.toString())
                .redirectErrorStream(true).start();
        String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        return result.isEmpty() ? "30" : result;
    }

    private int[] ffprobeResolution(Path video) throws Exception {
        String ffprobe = findTool("ffprobe", "ffprobe.exe");
        Process p = new ProcessBuilder(ffprobe, "-v", "0", "-select_streams", "v:0",
                "-of", "csv=p=0", "-show_entries", "stream=width,height", video.toString())
                .redirectErrorStream(true).start();
        String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(10, TimeUnit.SECONDS);
        String[] parts = result.split(",");
        return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }

    // =========================================================================
    // Step 4: Export
    // =========================================================================

    private Node buildStep4Export() {
        summaryClip = new Label();
        summarySubs = new Label();
        summaryWatermark = new Label();
        VBox rows = new VBox(0);
        for (Label l : new Label[]{summaryClip, summarySubs, summaryWatermark}) {
            l.getStyleClass().add("summary-row");
            l.setMaxWidth(Double.MAX_VALUE);
            l.setWrapText(true);
            rows.getChildren().add(l);
        }
        rows.getStyleClass().add("summary-list");
        Label outHint = new Label("Saved as an MP4 (H.264 video, AAC audio) that uploads straight to YouTube, Instagram and X.");
        outHint.getStyleClass().add("hint-label");
        outHint.setWrapText(true);
        return card(groupTitle("What goes into your video"), rows, outHint);
    }

    private void refreshSummary() {
        boolean subs = currentSrt != null && hasCaptions();
        setSummaryRow(summaryClip, currentClip != null,
                currentClip != null ? "Clip: " + fmtHms(clipDurationSeconds()) + " cut from your video" : "No clip yet. Go back to step 1 and extract one.");
        setSummaryRow(summarySubs, subs,
                subs ? "Subtitles: " + cues.size() + " captions, burned into the picture" : "No subtitles. The video will have no captions.");
        setSummaryRow(summaryWatermark, watermarkClip != null,
                watermarkClip != null ? "Watermark step applied" : "Watermark step skipped");
    }

    private void setSummaryRow(Label l, boolean included, String text) {
        l.getStyleClass().removeAll("summary-yes", "summary-no");
        l.getStyleClass().add(included ? "summary-yes" : "summary-no");
        l.setText((included ? "\u2713   " : "\u2013   ") + text);
    }

    private double currentClipSeconds;
    private double clipDurationSeconds() { return currentClipSeconds; }

    private void runFinalExport() {
        if (currentClip == null) { setStatus("Extract a clip first (step 1).", "error"); return; }
        FileChooser fc = new FileChooser();
        fc.setTitle("Save your video");
        fc.setInitialFileName("clip_final.mp4");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4 video", "*.mp4"));
        File chosen = fc.showSaveDialog(nextBtn.getScene().getWindow());
        if (chosen == null) return;
        exportTo(chosen);
    }

    private void exportTo(File chosen) {

        // Exporting into the scratch folder would get the file wiped on the next launch.
        try {
            if (chosen.toPath().toAbsolutePath().normalize().startsWith(workDir().toAbsolutePath().normalize())) {
                alert("Please save outside the ClipForge working folder (" + workDir() + ") — that folder is cleared on every launch.");
                return;
            }
        } catch (IOException ignored) {}

        Path base = watermarkClip != null ? watermarkClip : currentClip;
        boolean burnSubs = currentSrt != null && hasCaptions();
        String dest = chosen.getAbsolutePath();
        setBusy("Saving your video...");
        submit(() -> {
            try {
                String ffmpeg = findTool("ffmpeg", "ffmpeg.exe");
                // -movflags +faststart puts the index at the front (YouTube/web start processing
                // and playing immediately); explicit 8-bit 4:2:0 + CRF 18 so the final encode
                // keeps the extraction's quality instead of falling to ffmpeg's default CRF 23.
                List<String> common = List.of("-map", "0:v:0", "-map", "0:a:0?",
                        "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart");
                if (burnSubs) {
                    persistCuesBlocking();
                    int[] res = ffprobeResolution(base);
                    Path ass = workDir().resolve("subtitles.ass");
                    Files.writeString(ass, srtToAss(Files.readString(currentSrt, StandardCharsets.UTF_8), res[0], res[1]), StandardCharsets.UTF_8);
                    List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-i", base.toString(),
                            "-vf", "subtitles=" + escapeFilterPath(ass)));
                    cmd.addAll(common);
                    cmd.add(dest);
                    runCommand(cmd);
                } else if (base.equals(currentClip)) {
                    // Nothing to burn in and the clip is already a clean H.264/AAC file we made —
                    // copy the streams instead of re-encoding: instant and zero quality loss.
                    runCommand(List.of(ffmpeg, "-y", "-i", base.toString(), "-map", "0",
                            "-c", "copy", "-movflags", "+faststart", dest));
                } else {
                    List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-i", base.toString()));
                    cmd.addAll(common);
                    cmd.add(dest);
                    runCommand(cmd);
                }
                ui(() -> {
                    setStatus("Saved " + chosen.getName() + " in " + chosen.getParent(), "success");
                    showSheet("success", "Your video is ready",
                            chosen.getName() + " is saved in " + chosen.getParent() + ".",
                            "Show in folder", () -> getHostServices().showDocument(chosen.getParentFile().toURI().toString()),
                            "Done", null);
                });
            } catch (Exception ex) { fail(ex); }
            finally { ui(this::clearBusy); }
        });
    }

    /** Writes the current cues to the SRT from a background thread (export reads that file). */
    private void persistCuesBlocking() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        ui(() -> { persistCues(); done.complete(null); });
        done.get(10, TimeUnit.SECONDS);
    }

    /** Builds the ASS style at the clip's real resolution. The canvas used to be hardcoded to
     *  1920x1080, so on vertical (9:16) or non-16:9 clips libass stretched text unevenly; now the
     *  canvas matches the video and the user's "Size" (defined at a 1080px short side) is scaled
     *  to it, along with outline, shadow and margins. */
    private String assStyle(int width, int height) {
        double k = Math.min(width, height) / 1080.0;
        String primary = assColor(textColor.getValue());
        String outline = assColor(outlineColor.getValue());
        int size = (int) Math.max(8, Math.round(fontSize.getValue() * k));
        int align = switch (positionBox.getValue()) { case "Top" -> 8; case "Center" -> 5; default -> 2; };
        String font = fontField.getText().replace(",", "").trim();
        if (font.isEmpty()) font = "Arial";
        return String.format(Locale.US, "Style: Default,%s,%d,%s,&H00000000,%s,&H80000000,-1,0,0,0,100,100,0,0,1,%.1f,%.1f,%d,%d,%d,%d,1",
                font, size, primary, outline, 3 * k, 2 * k, align,
                Math.round(40 * k), Math.round(40 * k), Math.round(60 * k));
    }

    private String srtToAss(String srt, int width, int height) {
        StringBuilder b = new StringBuilder();
        b.append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(width)
                .append("\nPlayResY: ").append(height)
                .append("\nScaledBorderAndShadow: yes\nWrapStyle: 0\n\n[V4+ Styles]\n")
                .append("Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding\n");
        b.append(assStyle(width, height)).append("\n\n[Events]\nFormat: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text\n");
        for (SubtitleCue c : parseSrt(srt)) {
            String text = sanitizeCueText(c.text);
            if (text.isEmpty()) continue;
            text = text.replace("{", "\\{").replace("}", "\\}").replace("\n", "\\N");
            b.append("Dialogue: 0,").append(assTime(c.start)).append(",").append(assTime(c.end))
                    .append(",Default,,0,0,0,,").append(text).append("\n");
        }
        return b.toString();
    }

    /** ASS timestamps are H:MM:SS.cc (centiseconds). The old "%04.1f" wrote tenths, putting
     *  every caption up to 100ms off its real timing. */
    private String assTime(double sec) {
        long cs = Math.round(Math.max(0, sec) * 100);
        long h = cs / 360000, m = (cs / 6000) % 60, sWhole = (cs / 100) % 60, frac = cs % 100;
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, sWhole, frac);
    }

    private String assColor(Color c) {
        int r = (int)Math.round(c.getRed()*255), g=(int)Math.round(c.getGreen()*255), b=(int)Math.round(c.getBlue()*255);
        return String.format(Locale.US, "&H00%02X%02X%02X", b,g,r);
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private Path workDir() throws IOException {
        Path p = Paths.get(System.getProperty("user.home"), "ClipForge");
        Files.createDirectories(p); return p;
    }

    /** All exports are saved by the user to a separate chosen location, so everything under the
     *  working dir is scratch from past runs — safe to wipe on each launch to avoid unbounded
     *  disk growth (downloads, preview proxies, extracted frames, etc. can total hundreds of MB). */
    private void cleanupWorkDir() {
        try {
            Path dir = workDir();
            Path lockFile = dir.resolve(".instance.lock");
            // Only the first running instance owns the scratch folder. A second ClipForge window
            // used to wipe the files the first one was still using in the middle of a job.
            workDirLockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            workDirLock = workDirLockChannel.tryLock();
            if (workDirLock == null) return;
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(dir) && !p.equals(lockFile))
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } catch (IOException | OverlappingFileLockException ignored) {}
    }

    private double parseTime(String s) {
        s=s.trim();
        if (s.matches("\\d+(\\.\\d+)?")) return Double.parseDouble(s);
        String[] p=s.split(":");
        if(p.length==2) return Integer.parseInt(p[0])*60 + Double.parseDouble(p[1]);
        if(p.length==3) return Integer.parseInt(p[0])*3600 + Integer.parseInt(p[1])*60 + Double.parseDouble(p[2]);
        throw new IllegalArgumentException("Invalid time: " + s);
    }

    private String fmt(double sec) { return String.format(Locale.US, "%.3f", sec); }

    /** HH:MM:SS.mmm — used anywhere a time is shown in an editable field and read back. */
    private String fmtPrecise(double sec) {
        long totalMs = Math.round(Math.max(0, sec) * 1000);
        long ms = totalMs % 1000, total = totalMs / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", total / 3600, (total % 3600) / 60, total % 60, ms);
    }

    private String fmtHms(double sec) {
        int total = (int) Math.round(sec);
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }

    private final Map<String, String> pythonForTask = new ConcurrentHashMap<>();

    /** Several Pythons are often installed side by side (here: 3.13 first on PATH, the AI packages
     *  only in 3.10). Picking the first "python" on PATH silently runs a different interpreter than
     *  the one pip install put packages into (transcription failing with "No module named whisper",
     *  watermark removal with "No module named PIL" — same root cause, different packages). Tries
     *  every python on PATH (plus the py launcher's) and uses the first that satisfies the given
     *  import check. Cached per task for the session. */
    private String findPythonWithModules(String taskKey, String importCheckExpr, String errorHint) throws IOException {
        String cached = pythonForTask.get(taskKey);
        if (cached != null) return cached;
        List<String> candidates = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(System.getenv().getOrDefault("ComSpec", "cmd.exe"), "/c", "where", "python")
                    .redirectErrorStream(true).start();
            for (String line : readAll(p.getInputStream()).split("\\R")) {
                String c = line.trim();
                if (!c.isEmpty() && Files.exists(Paths.get(c)) && !c.toLowerCase(Locale.ROOT).contains("\\windowsapps\\")) candidates.add(c);
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        candidates.add("py");
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-c", importCheckExpr).redirectErrorStream(true).start();
                readAll(p.getInputStream());
                if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    pythonForTask.put(taskKey, c);
                    ui(() -> logLine("Using Python for " + taskKey + ": " + c));
                    return c;
                }
            } catch (Exception ignored) {}
        }
        throw new IOException(errorHint);
    }

    private String findWhisperPython() throws IOException {
        return findPythonWithModules("whisper",
                "import importlib.util as u,sys; sys.exit(0 if (u.find_spec('faster_whisper') or u.find_spec('whisper')) else 1)",
                "Subtitles need Whisper, but no installed Python has it. Run: pip install faster-whisper");
    }

    private String findWatermarkPython() throws IOException {
        return findPythonWithModules("watermark",
                "import importlib.util as u,sys; sys.exit(0 if (u.find_spec('torch') and u.find_spec('PIL') and u.find_spec('propainter')) else 1)",
                "Watermark removal needs torch, Pillow and propainter installed, but no installed Python has all of them. "
                        + "Run (in that Python): pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124 && pip install pillow propainter");
    }

    private String findTool(String... names) throws IOException {
        for(String n:names) {
            if(n.contains("\\") && Files.exists(Paths.get(n))) return n;
            try {
                Process p = new ProcessBuilder(System.getenv().getOrDefault("ComSpec","cmd.exe"), "/c", "where", n)
                        .redirectErrorStream(true).start();
                String s=new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                p.waitFor(5, TimeUnit.SECONDS);
                // Skip the Microsoft Store "python.exe" placeholder in WindowsApps — running it
                // opens the Store instead of Python, so transcription silently never ran.
                for (String line : s.split("\\R")) {
                    String c = line.trim();
                    if (c.isEmpty() || !Files.exists(Paths.get(c))) continue;
                    if (c.toLowerCase(Locale.ROOT).contains("\\windowsapps\\")) continue;
                    return c;
                }
            } catch(Exception ignored) {}
        }
        throw new IOException("Could not find " + String.join(" / ", names) + ". Open a NEW terminal or add it to PATH.");
    }


    /** Every external process currently running on behalf of a background task — tracked so the
     *  Cancel button can kill all of them (Load Video runs two yt-dlp lookups in parallel). */
    private final Set<Process> runningProcesses = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelRequested;

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private void runCommand(List<String> cmd) throws Exception {
        runCommand(cmd, this::logLine);
    }

    /** Runs a command and returns its stdout as text (e.g. yt-dlp's resolved URLs). stderr is
     *  drained on its own thread at the same time: reading all of stdout first and only then
     *  stderr could deadlock as soon as a tool wrote enough warnings to fill the stderr pipe. */
    private String runCommandCaptureOutput(List<String> cmd) throws Exception {
        ui(() -> logLine("$ " + String.join(" ", cmd)));
        Process p = new ProcessBuilder(cmd).start();
        runningProcesses.add(p);
        try {
            CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()));
            String out = readAll(p.getInputStream());
            int code = p.waitFor();
            String err = errFuture.get(10, TimeUnit.SECONDS);
            if (cancelRequested) throw new CancellationException("Cancelled by user.");
            if (!err.isBlank()) { String e = err.strip(); ui(() -> logLine(e)); }
            if (code != 0) throw new IOException("Command failed with exit code " + code + (err.isBlank() ? "" : ": " + err.strip()));
            return out;
        } finally { runningProcesses.remove(p); }
    }

    private static String readAll(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) { return ""; }
    }

    /** Runs a command streaming its output to onLine. On failure the exception message carries the
     *  last lines of output, so callers can tell e.g. a GPU-encoder failure from an expired link. */
    private void runCommand(List<String> cmdIn, Consumer<String> onLine) throws Exception {
        // ffmpeg's banner and twice-a-second progress lines flooded the activity log with
        // thousands of lines, each one a UI update — enough to freeze the window for ~0.4s.
        // Warnings and errors still come through.
        List<String> cmd = cmdIn;
        String exe = Paths.get(cmdIn.get(0)).getFileName().toString().toLowerCase(Locale.ROOT);
        if (exe.equals("ffmpeg") || exe.equals("ffmpeg.exe")) {
            cmd = new ArrayList<>(cmdIn);
            cmd.addAll(1, List.of("-hide_banner", "-nostats", "-loglevel", "warning"));
        }
        List<String> shown = cmd;
        ui(() -> logLine("$ " + String.join(" ", shown)));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        runningProcesses.add(p);
        Deque<String> tail = new ArrayDeque<>();
        try {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) {
                    String x = line;
                    ui(() -> onLine.accept(x));
                    tail.addLast(x);
                    if (tail.size() > 15) tail.removeFirst();
                }
            }
            int code = p.waitFor();
            if (cancelRequested) throw new CancellationException("Cancelled by user.");
            if (code != 0) throw new IOException("Command failed with exit code " + code
                    + (tail.isEmpty() ? "" : ":\n" + String.join("\n", tail)));
        } finally { runningProcesses.remove(p); }
    }

    /** Wired to the busy overlay's Cancel button. Kills whatever processes are currently running —
     *  the calling flow's catch block picks up the resulting CancellationException and reports
     *  it as "Cancelled.", same as any other failure path. */
    private void cancelCurrentOperation() {
        cancelRequested = true;
        for (Process p : runningProcesses) p.destroyForcibly();
        logLine("Cancel requested...");
    }

    // ---- Hardware-accelerated encoding ----

    /** Finds a hardware H.264 encoder that actually works here (NVIDIA, Intel or AMD), testing each
     *  with a tiny real encode — ffmpeg builds list these encoders even with no matching GPU.
     *  Probed once per session. Set CLIPFORGE_NO_HW=1 to force the CPU encoder. */
    private String hardwareEncoder() {
        if (hwEncoderProbed) return hwEncoder;
        synchronized (this) {
            if (hwEncoderProbed) return hwEncoder;
            String found = null;
            if (!"1".equals(System.getenv("CLIPFORGE_NO_HW"))) {
                try {
                    String ffmpeg = findTool("ffmpeg", "ffmpeg.exe");
                    for (String enc : List.of("h264_nvenc", "h264_qsv", "h264_amf")) {
                        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                                "-f", "lavfi", "-i", "color=c=black:s=320x240:d=0.2"));
                        cmd.addAll(videoEncoderArgs(enc, true));
                        cmd.addAll(List.of("-f", "null", "-"));
                        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                        readAll(p.getInputStream());
                        if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) { found = enc; break; }
                        p.destroyForcibly();
                    }
                } catch (Exception ignored) {}
            }
            hwEncoder = found;
            hwEncoderProbed = true;
            String msg = found != null ? "Using GPU encoder: " + found : "No working GPU encoder found — using CPU (libx264).";
            ui(() -> logLine(msg));
            return found;
        }
    }

    /** Encoder arguments for preview (fast, lower quality) or extraction (high quality) encodes.
     *  enc == null means CPU libx264. */
    private List<String> videoEncoderArgs(String enc, boolean preview) {
        if (enc == null) return List.of("-c:v", "libx264", "-preset", "veryfast",
                "-crf", preview ? "23" : "18", "-pix_fmt", "yuv420p");
        return switch (enc) {
            // b_ref_mode disabled: keeps the stream simple for JavaFX's decoder.
            case "h264_nvenc" -> List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr",
                    "-cq", preview ? "28" : "19", "-b:v", "0", "-b_ref_mode", "disabled",
                    "-profile:v", "high", "-pix_fmt", "yuv420p");
            case "h264_qsv" -> List.of("-c:v", "h264_qsv", "-preset", "veryfast",
                    "-global_quality", preview ? "28" : "20", "-pix_fmt", "nv12");
            case "h264_amf" -> List.of("-c:v", "h264_amf", "-quality", "balanced", "-rc", "cqp",
                    "-qp_i", preview ? "26" : "18", "-qp_p", preview ? "28" : "20", "-pix_fmt", "yuv420p");
            default -> List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", preview ? "23" : "18", "-pix_fmt", "yuv420p");
        };
    }

    /** Runs an encode on the GPU encoder when one works, and transparently re-runs it on the CPU
     *  if the GPU encoder fails for this input (it's then disabled for the rest of the session). */
    private void encodeWithFallback(java.util.function.Function<String, List<String>> buildCmd) throws Exception {
        String enc = hardwareEncoder();
        if (enc == null) { runCommand(buildCmd.apply(null)); return; }
        try {
            runCommand(buildCmd.apply(enc));
        } catch (CancellationException ce) {
            throw ce;
        } catch (IOException ex) {
            String m = String.valueOf(ex.getMessage());
            if (!m.contains(enc) && !m.toLowerCase(Locale.ROOT).contains("encoder")) throw ex;
            hwEncoder = null;
            ui(() -> logLine("GPU encoder " + enc + " failed on this clip — retrying on CPU."));
            runCommand(buildCmd.apply(null));
        }
    }

    /** Runs a step that reads the remote stream URLs; if it fails (typically HTTP 403 because
     *  the resolved links expired while the app sat open), re-resolves them once and retries. */
    private void withStreamRefresh(ThrowingRunnable step) throws Exception {
        try {
            step.run();
        } catch (CancellationException ce) {
            throw ce;
        } catch (Exception ex) {
            if (lastLoadedUrl == null) throw ex;
            ui(() -> logLine("Stream read failed — the video link may have expired. Refreshing it and retrying..."));
            resolveStreams(lastLoadedUrl);
            step.run();
        }
    }

    private void submit(Runnable r){ executor.submit(r); }
    private void ui(Runnable r){ javafx.application.Platform.runLater(r); }
    private void setBusy(String s){ cancelRequested = false; ui(()->{setStatus(s, "info");progress.setProgress(-1);busyLabel.setText(s);showOverlay(true);}); }
    private void clearBusy(){ progress.setProgress(0); showOverlay(false); refreshGuides(); }

    private Animation overlayAnim;
    /** Busy overlay fades in with its card rising slightly, and fades out when work finishes. */
    private void showOverlay(boolean show) {
        if (overlayAnim != null) overlayAnim.stop();
        Node card = busyOverlay.getChildren().isEmpty() ? null : busyOverlay.getChildren().get(0);
        if (!ANIMATE) { busyOverlay.setVisible(show); busyOverlay.setOpacity(1); return; }
        if (show) {
            if (busyOverlay.isVisible() && busyOverlay.getOpacity() == 1) return;
            busyOverlay.setOpacity(0);
            busyOverlay.setVisible(true);
            FadeTransition f = new FadeTransition(Duration.millis(180), busyOverlay);
            f.setToValue(1);
            ParallelTransition pt = new ParallelTransition(f);
            if (card != null) {
                card.setTranslateY(12);
                TranslateTransition t = new TranslateTransition(Duration.millis(320), card);
                t.setToY(0); t.setInterpolator(EASE_OUT);
                pt.getChildren().add(t);
            }
            overlayAnim = pt;
        } else {
            if (!busyOverlay.isVisible()) return;
            FadeTransition f = new FadeTransition(Duration.millis(200), busyOverlay);
            f.setToValue(0);
            f.setOnFinished(e -> { busyOverlay.setVisible(false); busyOverlay.setOpacity(1); });
            overlayAnim = f;
        }
        overlayAnim.play();
    }
    /** Activity-log lines are collected and written to the text box a few times a second, and the
     *  box keeps only the most recent ~300 KB, so a chatty tool can't slow the window down. */
    private final StringBuilder logBuf = new StringBuilder();
    private boolean logFlushPending;
    private void logLine(String s){
        logBuf.append(s).append('\n');
        if (logFlushPending) return;
        logFlushPending = true;
        PauseTransition flush = new PauseTransition(Duration.millis(150));
        flush.setOnFinished(e -> flushLog());
        flush.play();
    }
    private void flushLog() {
        logFlushPending = false;
        if (logBuf.length() == 0) return;
        log.appendText(logBuf.toString());
        logBuf.setLength(0);
        int max = 300_000;
        if (log.getLength() > max) log.deleteText(0, log.getLength() - max);
    }

    /** Footer status line. kind: "info", "success" or "error" (colour + leading symbol). */
    private void setStatus(String text, String kind) {
        status.getStyleClass().removeAll("status-info", "status-success", "status-error");
        status.getStyleClass().add("status-" + kind);
        String prefix = switch (kind) { case "success" -> "\u2713  "; case "error" -> "\u26A0  "; default -> ""; };
        String full = prefix + text;
        if (full.equals(status.getText())) return;
        status.setText(full);
        if (ANIMATE) {
            status.setOpacity(0.2);
            FadeTransition f = new FadeTransition(Duration.millis(240), status);
            f.setToValue(1);
            f.play();
            if ("error".equals(kind)) shake(status);
        }
    }

    /** Tiny horizontal shake so an error in the footer is noticed. */
    private void shake(Node n) {
        TranslateTransition t = new TranslateTransition(Duration.millis(55), n);
        t.setFromX(0); t.setByX(5);
        t.setAutoReverse(true);
        t.setCycleCount(6);
        t.setOnFinished(e -> n.setTranslateX(0));
        t.play();
    }

    /** Errors say what happened and point at the fix; the raw tool output goes to Details. */
    private void fail(Exception e){ ui(()->{
        if (e instanceof CancellationException) { setStatus("Stopped. Nothing was changed.", "info"); logLine("Cancelled by user."); }
        else {
            String first = String.valueOf(e.getMessage()).split("\\R")[0];
            setStatus(first + "  (open Details below for the full output)", "error");
            logLine("ERROR: " + e);
        }
    }); }


    /** Every message the app shows uses the same in-app dialog (no system pop-ups). Messages
     *  passed here are almost always "you need to fix something first", so they read as notices. */
    private void alert(String s){
        ui(() -> {
            String lower = String.valueOf(s).toLowerCase(Locale.ROOT);
            boolean good = lower.contains("success") || lower.startsWith("saved") || lower.contains("removed");
            showSheet(good ? "success" : "notice", good ? "Done" : "Check this first", s, "OK", null, null, null);
        });
    }

    // ---- In-app dialog ("sheet") ----
    private StackPane modalLayer;
    private Runnable sheetPrimary, sheetSecondary;

    /** Shows a dialog card over a dimmed window. kind: "notice", "success" or "danger" (sets the
     *  icon colour and, for danger, a red main button). Enter = main button, Esc = the other one
     *  (or closes). Card rises and fades in; closing reverses it. */
    private void showSheet(String kind, String title, String body,
                           String primaryText, Runnable onPrimary, String secondaryText, Runnable onSecondary) {
        Label icon = new Label(switch (kind) { case "success" -> "\u2713"; case "danger" -> "!"; default -> "i"; });
        icon.getStyleClass().addAll("sheet-icon", "sheet-icon-" + kind);
        Label t = new Label(title);
        t.getStyleClass().add("sheet-title");
        t.setWrapText(true);
        Label b = new Label(body);
        b.getStyleClass().add("sheet-body");
        b.setWrapText(true);
        VBox text = new VBox(6, t, b);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox top = new HBox(14, icon, text);
        top.setAlignment(Pos.TOP_LEFT);

        Button primary = new Button(primaryText);
        primary.getStyleClass().add("danger".equals(kind) ? "danger-btn" : "primary-btn");
        primary.setDefaultButton(true);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        if (secondaryText != null) {
            Button secondary = new Button(secondaryText);
            secondary.getStyleClass().add("ghost-btn");
            secondary.setCancelButton(true);
            secondary.setOnAction(e -> closeSheet(onSecondary));
            buttons.getChildren().add(secondary);
        } else primary.setCancelButton(true);
        primary.setOnAction(e -> closeSheet(onPrimary));
        buttons.getChildren().add(primary);

        VBox card = new VBox(20, top, buttons);
        card.getStyleClass().add("sheet-card");
        card.setMaxWidth(460);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        sheetPrimary = onPrimary;
        sheetSecondary = onSecondary;

        modalLayer.getChildren().setAll(card);
        modalLayer.setVisible(true);
        // Clicking the dimmed area outside the card behaves like the secondary choice.
        modalLayer.setOnMouseClicked(e -> { if (e.getTarget() == modalLayer) closeSheet(secondaryText != null ? onSecondary : onPrimary); });
        if (ANIMATE) {
            modalLayer.setOpacity(0);
            card.setScaleX(0.96); card.setScaleY(0.96); card.setTranslateY(14);
            FadeTransition f = new FadeTransition(Duration.millis(160), modalLayer);
            f.setToValue(1);
            ScaleTransition sc = new ScaleTransition(Duration.millis(300), card);
            sc.setToX(1); sc.setToY(1); sc.setInterpolator(EASE_OUT);
            TranslateTransition tr = new TranslateTransition(Duration.millis(300), card);
            tr.setToY(0); tr.setInterpolator(EASE_OUT);
            new ParallelTransition(f, sc, tr).play();
        } else modalLayer.setOpacity(1);
        javafx.application.Platform.runLater(primary::requestFocus);
    }

    private void closeSheet(Runnable then) {
        Runnable done = () -> {
            modalLayer.setVisible(false);
            modalLayer.getChildren().clear();
            if (then != null) then.run();
        };
        if (!ANIMATE || modalLayer.getChildren().isEmpty()) { done.run(); return; }
        Node card = modalLayer.getChildren().get(0);
        FadeTransition f = new FadeTransition(Duration.millis(150), modalLayer);
        f.setToValue(0);
        ScaleTransition sc = new ScaleTransition(Duration.millis(150), card);
        sc.setToX(0.97); sc.setToY(0.97);
        ParallelTransition pt = new ParallelTransition(f, sc);
        pt.setOnFinished(e -> done.run());
        pt.play();
    }

    /** Short, non-blocking confirmation that fades away on its own (used for successes, which
     *  used to need an extra click on a pop-up every time). */
    private PauseTransition toastTimer;
    private void toast(String text) {
        ui(() -> {
            toastLabel.setText("\u2713  " + text);
            toastLabel.setVisible(true);
            if (ANIMATE) {
                toastLabel.setOpacity(0);
                toastLabel.setTranslateY(18);
                FadeTransition fi = new FadeTransition(Duration.millis(200), toastLabel);
                fi.setToValue(1);
                TranslateTransition ti = new TranslateTransition(Duration.millis(340), toastLabel);
                ti.setToY(0); ti.setInterpolator(EASE_OUT);
                new ParallelTransition(fi, ti).play();
            } else toastLabel.setOpacity(1);
            if (toastTimer != null) toastTimer.stop();
            toastTimer = new PauseTransition(Duration.seconds(3.2));
            toastTimer.setOnFinished(e -> {
                FadeTransition f = new FadeTransition(Duration.millis(260), toastLabel);
                f.setFromValue(1); f.setToValue(0);
                f.setOnFinished(x -> toastLabel.setVisible(false));
                f.play();
            });
            toastTimer.play();
        });
    }
    private String escapeFilterPath(Path p){ return p.toAbsolutePath().toString().replace("\\","/").replace(":","\\\\:").replace("'","\\\\'"); }

    @Override public void stop(){
        if (player != null) player.dispose();
        if (subtitlePlayer != null) subtitlePlayer.dispose();
        if (watermarkResultPlayer != null) watermarkResultPlayer.dispose();
        // Don't leave ffmpeg/yt-dlp/python running in the background after the window closes.
        for (Process p : runningProcesses) p.destroyForcibly();
        if (rangeProxy != null) rangeProxy.stop();
        executor.shutdownNow();
        try { if (workDirLock != null) workDirLock.release(); if (workDirLockChannel != null) workDirLockChannel.close(); } catch (IOException ignored) {}
    }
}
