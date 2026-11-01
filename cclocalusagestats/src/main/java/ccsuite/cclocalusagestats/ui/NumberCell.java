package ccsuite.cclocalusagestats.ui;

import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.TableCell;

final class NumberCell extends TableCell<Map<String, Object>, Object> {

    @Override
    protected void updateItem(Object v, boolean empty) {
        super.updateItem(v, empty);
        setText(empty || v == null ? null : v instanceof Long || v instanceof Integer ? String.format("%,d", ((Number) v).longValue()) : v instanceof Double ? String.format("%,.1f", v) : v.toString());
        setAlignment(v instanceof Number ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    }
}
