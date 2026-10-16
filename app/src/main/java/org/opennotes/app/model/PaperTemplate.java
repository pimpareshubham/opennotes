package org.opennotes.app.model;

public enum PaperTemplate {
    BLANK("Blank"),
    RULED("Ruled Lines"),
    GRID("Math Grid"),
    DOTS("Dot Grid"),
    CORNELL("Cornell Note");

    private final String displayName;

    PaperTemplate(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PaperTemplate fromString(String name) {
        if (name == null) return BLANK;
        try {
            return PaperTemplate.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BLANK;
        }
    }
}
