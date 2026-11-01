package ccsuite.cclocalusagestats.ui;

import java.util.*;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.*;

final class ResultTable {

    private ResultTable() {}

    static void fill(TableView<Map<String, Object>> t, List<Map<String, Object>> rows) {
        var keys = rows.isEmpty() ? List.<String>of() : new ArrayList<>(rows.get(0).keySet());
        if (!keys.equals(t.getColumns().stream().map(TableColumnBase::getText).toList()))
            t.getColumns().setAll(keys.stream().map(ResultTable::column).toList());
        t.getItems().setAll(rows);
    }

    static TableColumn<Map<String, Object>, Object> column(String key) {
        var c = new TableColumn<Map<String, Object>, Object>(key);
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().get(key)));
        c.setCellFactory(col -> new NumberCell());
        c.setSortable(false);
        c.setPrefWidth(key.equals("key") ? 320 : 110);
        return c;
    }
}
