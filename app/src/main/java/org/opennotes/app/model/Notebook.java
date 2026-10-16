package org.opennotes.app.model;

public class Notebook {
    public long id;
    public String title;
    public String folder;
    public int coverColor;
    public PaperTemplate defaultTemplate;
    public int defaultBgColor;
    public boolean defaultIsInfinite;
    public int pageCount;
    public boolean isFavorite;
    public long createdAt;
    public long updatedAt;

    public Notebook(String title, int coverColor, PaperTemplate defaultTemplate) {
        this.id = 0;
        this.title = title;
        this.folder = "Default";
        this.coverColor = coverColor;
        this.defaultTemplate = defaultTemplate;
        this.defaultBgColor = 0xFF121214;
        this.defaultIsInfinite = false;
        this.pageCount = 1;
        this.isFavorite = false;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public Notebook() {
        this("Untitled Notebook", 0xFF1E3A8A, PaperTemplate.RULED);
    }
}
