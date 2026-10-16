package com.hitesh.clipforge;

import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/** Lets the user drag/resize a box over a still frame to mark a watermark region. */
public class WatermarkBoxOverlay extends Pane {
    private final ImageView imageView = new ImageView();
    private final Rectangle box = new Rectangle(0, 0, 160, 60);
    private final Rectangle resizeHandle = new Rectangle(0, 0, 14, 14);
    private double dragOffsetX, dragOffsetY;

    public WatermarkBoxOverlay() {
        imageView.setPreserveRatio(true);
        box.getStyleClass().add("watermark-box");
        box.setCursor(javafx.scene.Cursor.MOVE);
        resizeHandle.getStyleClass().add("watermark-resize-handle");
        resizeHandle.setCursor(javafx.scene.Cursor.SE_RESIZE);
        resizeHandle.setEffect(new DropShadow(3, Color.rgb(0, 0, 0, 0.7)));
        getChildren().addAll(imageView, box, resizeHandle);

        // Click anywhere on the frame (outside the box) to instantly move the box there,
        // centered on the click — no need to find and grab a possibly tiny existing box first.
        imageView.setOnMousePressed(e -> {
            if (imageView.getImage() == null) return;
            moveBoxCenterTo(e.getX(), e.getY());
        });
        imageView.setCursor(javafx.scene.Cursor.CROSSHAIR);

        box.setOnMousePressed(e -> {
            dragOffsetX = e.getX() - box.getX();
            dragOffsetY = e.getY() - box.getY();
            e.consume();
        });
        box.setOnMouseDragged(e -> {
            double maxX = imageView.getFitWidth() - box.getWidth();
            double maxY = imageView.getFitHeight() - box.getHeight();
            box.setX(clamp(e.getX() - dragOffsetX, 0, Math.max(0, maxX)));
            box.setY(clamp(e.getY() - dragOffsetY, 0, Math.max(0, maxY)));
            layoutHandle();
            e.consume();
        });

        resizeHandle.setOnMouseDragged(e -> {
            double nw = clamp(e.getX() - box.getX(), 24, imageView.getFitWidth() - box.getX());
            double nh = clamp(e.getY() - box.getY(), 24, imageView.getFitHeight() - box.getY());
            box.setWidth(nw);
            box.setHeight(nh);
            layoutHandle();
            e.consume();
        });
    }

    private void moveBoxCenterTo(double cx, double cy) {
        double maxX = imageView.getFitWidth() - box.getWidth();
        double maxY = imageView.getFitHeight() - box.getHeight();
        box.setX(clamp(cx - box.getWidth() / 2, 0, Math.max(0, maxX)));
        box.setY(clamp(cy - box.getHeight() / 2, 0, Math.max(0, maxY)));
        layoutHandle();
    }

    private double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private void layoutHandle() {
        resizeHandle.setX(box.getX() + box.getWidth() - 7);
        resizeHandle.setY(box.getY() + box.getHeight() - 7);
    }

    public void setImage(Image img, double maxWidth, double maxHeight) {
        imageView.setImage(img);
        double scale = Math.min(maxWidth / img.getWidth(), maxHeight / img.getHeight());
        double w = img.getWidth() * scale;
        double h = img.getHeight() * scale;
        imageView.setFitWidth(w);
        imageView.setFitHeight(h);
        setPrefSize(w, h);
        setMaxSize(w, h);
        box.setWidth(w * 0.28);
        box.setHeight(h * 0.14);
        box.setX(w * 0.68);
        box.setY(h * 0.80);
        layoutHandle();
    }

    public boolean hasImage() { return imageView.getImage() != null; }

    public void clearImage() { imageView.setImage(null); }

    /** Selected box mapped to the source image's native pixel coordinates. */
    public int[] getNativeBox() {
        Image img = imageView.getImage();
        double scaleX = img.getWidth() / imageView.getFitWidth();
        double scaleY = img.getHeight() / imageView.getFitHeight();
        int x = (int) Math.round(box.getX() * scaleX);
        int y = (int) Math.round(box.getY() * scaleY);
        int w = (int) Math.round(box.getWidth() * scaleX);
        int h = (int) Math.round(box.getHeight() * scaleY);
        return new int[]{x, y, w, h};
    }
}
