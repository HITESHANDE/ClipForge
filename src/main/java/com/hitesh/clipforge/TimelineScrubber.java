package com.hitesh.clipforge;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Draggable in/out point scrubber with a live playhead, used to pick a clip range from a full video. */
public class TimelineScrubber extends Region {
    private static final double TOP_LABEL_H = 16, HANDLE_W = 14, HANDLE_H = 54, TRACK_H = 40, TICK_ROW_H = 16;
    private static final int TICK_COUNT = 6;

    private final Rectangle track = new Rectangle();
    private final Rectangle selectedRange = new Rectangle();
    private final Rectangle playheadLine = new Rectangle(2, HANDLE_H);
    private final Polygon inHandle = makeHandle(true);
    private final Polygon outHandle = makeHandle(false);
    private final Text[] tickLabels = new Text[TICK_COUNT];
    private final Text playheadLabel = new Text();

    private final DoubleProperty duration = new SimpleDoubleProperty(0);
    private final DoubleProperty inSeconds = new SimpleDoubleProperty(0);
    private final DoubleProperty outSeconds = new SimpleDoubleProperty(0);
    private final DoubleProperty playhead = new SimpleDoubleProperty(0);

    /** Fired when the user clicks the track or drags a handle, to seek the preview player. */
    private DoubleConsumer onSeek = s -> {};
    /** Fired after either handle finishes moving, with (inSeconds, outSeconds). */
    private Consumer<double[]> onRangeChanged = r -> {};

    public TimelineScrubber() {
        setPrefHeight(TOP_LABEL_H + HANDLE_H + TICK_ROW_H);
        setMinHeight(TOP_LABEL_H + HANDLE_H + TICK_ROW_H);

        track.getStyleClass().add("scrubber-track");
        track.setArcWidth(10); track.setArcHeight(10);
        track.setHeight(TRACK_H);

        selectedRange.getStyleClass().add("scrubber-selected");
        selectedRange.setHeight(TRACK_H);

        playheadLine.getStyleClass().add("scrubber-playhead");

        for (int i = 0; i < TICK_COUNT; i++) {
            tickLabels[i] = new Text("00:00:00");
            tickLabels[i].getStyleClass().add("scrubber-tick-label");
        }
        playheadLabel.getStyleClass().add("scrubber-playhead-label");
        playheadLabel.setText("00:00:00");

        getChildren().addAll(track, selectedRange, playheadLine, inHandle, outHandle, playheadLabel);
        getChildren().addAll(tickLabels);

        track.setOnMouseClicked(e -> {
            // The track rectangle starts at HANDLE_W/2, and e.getX() is in that same local space,
            // so remove the offset — clicks used to land ~7px (a fraction of a second) late.
            double sec = pxToSeconds(e.getX() - HANDLE_W / 2);
            playhead.set(sec);
            onSeek.accept(sec);
        });

        widthProperty().addListener((o, a, b) -> layoutAll());
        duration.addListener((o, a, b) -> layoutAll());
        inSeconds.addListener((o, a, b) -> layoutAll());
        outSeconds.addListener((o, a, b) -> layoutAll());
        playhead.addListener((o, a, b) -> layoutAll());

        layoutAll();
    }

    private Polygon makeHandle(boolean isIn) {
        Polygon p = new Polygon(
                0, 0, HANDLE_W, 0, HANDLE_W, HANDLE_H - 12, HANDLE_W / 2, HANDLE_H, 0, HANDLE_H - 12);
        p.getStyleClass().add(isIn ? "scrubber-handle-in" : "scrubber-handle-out");
        p.setEffect(new DropShadow(3, Color.rgb(0, 0, 0, 0.6)));
        p.setOnMouseDragged(e -> {
            double localX = e.getX() + p.getLayoutX();
            double sec = pxToSeconds(localX - HANDLE_W / 2);
            if (isIn) {
                sec = Math.min(sec, outSeconds.get() - 0.1);
                inSeconds.set(Math.max(0, sec));
            } else {
                sec = Math.max(sec, inSeconds.get() + 0.1);
                outSeconds.set(Math.min(duration.get(), sec));
            }
            playhead.set(sec);
            onSeek.accept(sec);
        });
        p.setOnMouseReleased(e -> onRangeChanged.accept(new double[]{inSeconds.get(), outSeconds.get()}));
        return p;
    }

    private double usableWidth() {
        return Math.max(1, getWidth() - HANDLE_W);
    }

    private double pxToSeconds(double x) {
        double d = duration.get();
        if (d <= 0) return 0;
        double clamped = Math.max(0, Math.min(usableWidth(), x));
        return clamped / usableWidth() * d;
    }

    private double secondsToPx(double s) {
        double d = duration.get();
        if (d <= 0) return 0;
        return s / d * usableWidth();
    }

    private void layoutAll() {
        double w = getWidth();
        double d = duration.get();
        double top = TOP_LABEL_H + (HANDLE_H - TRACK_H) / 2;

        track.setX(HANDLE_W / 2); track.setY(top); track.setWidth(Math.max(0, w - HANDLE_W));

        double inX = secondsToPx(inSeconds.get());
        double outX = secondsToPx(outSeconds.get());
        selectedRange.setX(HANDLE_W / 2 + inX);
        selectedRange.setY(top);
        selectedRange.setWidth(Math.max(0, outX - inX));

        inHandle.setLayoutX(inX);
        inHandle.setLayoutY(TOP_LABEL_H);
        outHandle.setLayoutX(outX);
        outHandle.setLayoutY(TOP_LABEL_H);

        double phX = secondsToPx(d > 0 ? playhead.get() : 0);
        playheadLine.setLayoutX(HANDLE_W / 2 + phX - 1);
        playheadLine.setLayoutY(TOP_LABEL_H);

        // Evenly spaced duration ticks along the bottom, so the bar's scale is legible
        // whether the source is 15 seconds or 4 hours long. Before a video is loaded (d <= 0)
        // every tick's position formula collapses to the same spot — hide them rather than
        // stack six overlapping "00:00:00" labels on top of each other.
        boolean hasDuration = d > 0;
        for (int i = 0; i < TICK_COUNT; i++) {
            Text label = tickLabels[i];
            label.setVisible(hasDuration);
            if (!hasDuration) continue;
            double t = d * i / (TICK_COUNT - 1);
            label.setText(fmtHms(t));
            double x = HANDLE_W / 2 + secondsToPx(t);
            double tw = label.getLayoutBounds().getWidth();
            double lx = i == 0 ? x : (i == TICK_COUNT - 1 ? x - tw : x - tw / 2);
            label.setX(Math.max(0, Math.min(w - tw, lx)));
            label.setY(TOP_LABEL_H + HANDLE_H + 13);
        }

        // Always-visible readout of exactly which second the playhead is at.
        playheadLabel.setVisible(hasDuration);
        if (hasDuration) {
            playheadLabel.setText(fmtHms(playhead.get()));
            double pw = playheadLabel.getLayoutBounds().getWidth();
            double px = HANDLE_W / 2 + phX - pw / 2;
            playheadLabel.setX(Math.max(0, Math.min(w - pw, px)));
            playheadLabel.setY(12);
        }
    }

    private static String fmtHms(double sec) {
        int total = (int) Math.round(Math.max(0, sec));
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }

    public void setDurationSeconds(double seconds) {
        duration.set(seconds);
        inSeconds.set(0);
        outSeconds.set(Math.min(seconds, Math.max(1, Math.min(30, seconds))));
        playhead.set(0);
    }

    public double getDurationSeconds() { return duration.get(); }
    public double getInSeconds() { return inSeconds.get(); }
    public double getOutSeconds() { return outSeconds.get(); }

    public void setInSeconds(double s) { inSeconds.set(Math.max(0, Math.min(s, outSeconds.get() - 0.1))); }
    public void setOutSeconds(double s) { outSeconds.set(Math.min(duration.get(), Math.max(s, inSeconds.get() + 0.1))); }
    /** Sets both ends at once. Calling setInSeconds then setOutSeconds clamps the new in point
     *  against the OLD out point, so moving a range later (0-30 -> 40-50) silently became 29.9-50. */
    public void setRange(double in, double out) {
        double d = duration.get();
        double o = Math.min(d, Math.max(0.1, out));
        double i = Math.max(0, Math.min(in, o - 0.1));
        outSeconds.set(o);
        inSeconds.set(i);
    }
    public void setPlayheadSeconds(double s) { playhead.set(s); }

    public void setOnSeek(DoubleConsumer c) { this.onSeek = c; }
    public void setOnRangeChanged(Consumer<double[]> c) { this.onRangeChanged = c; }
}
