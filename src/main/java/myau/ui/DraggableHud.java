package myau.ui;

/**
 * A HUD element the {@link myau.ui.impl.hud.HudEditorScreen} can pick up and move.
 * <p>
 * Implementations keep their position in their own properties (usually an anchor plus an offset,
 * so the layout survives a resolution change); the editor only works in absolute screen pixels and
 * lets the module convert. {@link #getHudBounds()} must describe exactly what the module draws, so
 * the outline the editor shows matches what the player sees.
 */
public interface DraggableHud {
    /** Label shown in the editor. */
    String getHudName();

    /** {x, y, width, height} in scaled screen pixels, scale already applied, or null if nothing is drawn. */
    float[] getHudBounds();

    /** Moves the element so its top-left corner lands on the given scaled screen pixel. */
    void setHudPosition(float x, float y);

    /** Puts the element back where it started. */
    void resetHudPosition();

    /** Whether the element is currently on screen (a disabled module still appears in the editor, greyed). */
    boolean isHudEnabled();
}
