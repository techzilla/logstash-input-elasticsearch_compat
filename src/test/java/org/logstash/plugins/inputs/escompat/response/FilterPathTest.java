package org.logstash.plugins.inputs.escompat.response;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterPathTest {
    static final String EXPORTER_DEFAULT =
        "items.*._index,items.*.status,items.*.failure_store,items.*.error.type,items.*.error.reason";

    @Test
    void absentMeansEverything() {
        assertSame(FilterPath.ALL, FilterPath.parse(null));
        assertSame(FilterPath.ALL, FilterPath.parse(" "));
    }

    @Test
    void exporterDefault() {
        final FilterPath f = FilterPath.parse(EXPORTER_DEFAULT);
        assertFalse(f.took());
        assertFalse(f.errors());
        assertTrue(f.items());
        assertTrue(f.itemField("_index"));
        assertTrue(f.itemField("status"));
        assertFalse(f.itemField("_id"));
        assertTrue(f.errorField("type"));
        assertTrue(f.errorField("reason"));
    }

    @Test
    void subsetAndTopLevel() {
        final FilterPath f = FilterPath.parse("took, errors ,items.*.status");
        assertTrue(f.took());
        assertTrue(f.errors());
        assertTrue(f.itemField("status"));
        assertFalse(f.itemField("_index"));
        assertFalse(f.errorField("type"));
    }

    @Test
    void wholeErrorObject() {
        final FilterPath f = FilterPath.parse("items.*.error");
        assertTrue(f.errorField("type"));
        assertTrue(f.errorField("reason"));
        assertFalse(f.itemField("status"));
    }

    @Test
    void allItems() {
        assertTrue(FilterPath.parse("items").itemField("_id"));
        assertTrue(FilterPath.parse("items.*").errorField("reason"));
        assertFalse(FilterPath.parse("items").took());
    }

    @ParameterizedTest
    @ValueSource(strings = {"took,hits.*", "-took", "items.create._index", "items.*.*", "**.status",
        "items.*.error.*", "items.*.", "items.*..x"})
    void unsupportedRejected(final String expr) {
        assertThrows(FilterPath.UnsupportedException.class, () -> FilterPath.parse(expr));
    }
}
