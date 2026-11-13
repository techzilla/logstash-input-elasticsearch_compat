package org.logstash.plugins.inputs.escompat.response;

import java.util.HashSet;
import java.util.Set;

/**
 * The subset of Elasticsearch {@code filter_path} this receiver supports (research R4):
 * {@code took}, {@code errors}, {@code items}, {@code items.*}, and {@code items.*.<field>} where
 * {@code <field>} is a dotted path without wildcards. Anything else is rejected rather than
 * silently producing a misleading response.
 */
public final class FilterPath {
    public static final FilterPath ALL = new FilterPath(true, true, true, Set.of());

    /** Thrown for expressions outside the supported subset; mapped to request 400. */
    public static final class UnsupportedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UnsupportedException(final String expression) {
            super("unsupported filter_path expression [" + expression
                + "]; supported: took, errors, items, items.*, items.*.<field>");
        }
    }

    private static final String ITEM_PREFIX = "items.*.";

    private final boolean took;
    private final boolean errors;
    private final boolean allItemFields;
    private final Set<String> itemFields;

    private FilterPath(final boolean took, final boolean errors, final boolean allItemFields,
                       final Set<String> itemFields) {
        this.took = took;
        this.errors = errors;
        this.allItemFields = allItemFields;
        this.itemFields = itemFields;
    }

    /** @param expression the raw parameter value, or {@code null} when absent */
    public static FilterPath parse(final String expression) {
        if (expression == null || expression.isBlank()) {
            return ALL;
        }
        boolean took = false;
        boolean errors = false;
        boolean allItems = false;
        final Set<String> fields = new HashSet<>();
        for (final String raw : expression.split(",")) {
            final String path = raw.trim();
            if (path.isEmpty()) {
                continue;
            }
            switch (path) {
                case "took": took = true; break;
                case "errors": errors = true; break;
                case "items":
                case "items.*": allItems = true; break;
                default:
                    final String field = path.startsWith(ITEM_PREFIX)
                        ? path.substring(ITEM_PREFIX.length()) : null;
                    if (field == null || field.isEmpty() || field.indexOf('*') >= 0
                        || field.startsWith(".") || field.endsWith(".") || field.contains("..")) {
                        throw new UnsupportedException(path);
                    }
                    fields.add(field);
            }
        }
        return new FilterPath(took, errors, allItems, Set.copyOf(fields));
    }

    public boolean took() {
        return took;
    }

    public boolean errors() {
        return errors;
    }

    public boolean items() {
        return allItemFields || !itemFields.isEmpty();
    }

    /** Top-level item field such as {@code _index}, {@code _id}, {@code status}. */
    public boolean itemField(final String name) {
        return allItemFields || itemFields.contains(name);
    }

    /** Sub-field of the item's {@code error} object, e.g. {@code type}. */
    public boolean errorField(final String name) {
        return allItemFields || itemFields.contains("error") || itemFields.contains("error." + name);
    }
}
