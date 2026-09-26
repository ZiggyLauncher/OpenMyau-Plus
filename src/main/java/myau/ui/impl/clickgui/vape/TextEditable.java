package myau.ui.impl.clickgui.vape;

/** A component that takes typed text while it has the GUI's focus. */
interface TextEditable {
    /** Handles a key while focused; returns true when the key was used. */
    boolean editKey(char typedChar, int keyCode);

    /** Focus moved elsewhere: commit whatever was typed. */
    void focusLost();
}
