package org.logstash.plugins.inputs.escompat.bulk;

/** Bulk action names. Only {@link #INDEX} and {@link #CREATE} produce events. */
public enum Action {
    INDEX("index", true),
    CREATE("create", true),
    UPDATE("update", true),
    DELETE("delete", false);

    private final String wireName;
    private final boolean hasSource;

    Action(final String wireName, final boolean hasSource) {
        this.wireName = wireName;
        this.hasSource = hasSource;
    }

    public String wireName() {
        return wireName;
    }

    /** Whether a source line follows the action line. */
    public boolean hasSource() {
        return hasSource;
    }

    public boolean producesEvent() {
        return this == INDEX || this == CREATE;
    }

    /** @return the action, or {@code null} if the name is unknown */
    public static Action fromWireName(final String name) {
        switch (name) {
            case "index": return INDEX;
            case "create": return CREATE;
            case "update": return UPDATE;
            case "delete": return DELETE;
            default: return null;
        }
    }
}
