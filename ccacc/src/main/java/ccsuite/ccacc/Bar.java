package ccsuite.ccacc;

import javafx.geometry.Pos;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** A bar of fixed width: a grey track and a coloured fill as wide as the percentage; changed in place, never rebuilt. */
final class Bar extends StackPane {

    final Region fill = new Region();
    final double width;

    Bar(double width) {
        this.width = width;
        var track = new Region();
        track.setMinSize(width, 9); track.setPrefSize(width, 9); track.setMaxSize(width, 9);
        track.setStyle("-fx-background-color: #dcdcdc; -fx-background-radius: 3;");
        fill.setMinHeight(9); fill.setPrefHeight(9); fill.setMaxHeight(9);
        getChildren().addAll(track, fill);
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(width);
        set(Double.NaN, "#9a9a9a");
    }

    void set(double pct, String color) {
        var w = Double.isNaN(pct) ? 0 : Math.max(2, width * Math.min(100, pct) / 100);
        fill.setMinWidth(w); fill.setPrefWidth(w); fill.setMaxWidth(w);
        fill.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 3;");
    }
}
