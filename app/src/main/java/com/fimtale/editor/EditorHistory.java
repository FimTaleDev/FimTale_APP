package com.fimtale.editor;

import java.util.ArrayList;
import java.util.List;

/** A bounded source history. Formatting and IME batch edits are restored as complete documents. */
public final class EditorHistory {
    private static final int MAX_STATES = 100;
    private static final int MAX_CHARACTERS = 2_000_000;
    private static final long TYPING_INTERVAL_MS = 1000;

    public static final class State {
        public final String text;
        public final int selectionStart, selectionEnd;
        State(String text, int start, int end) {
            this.text = text;
            selectionStart = Math.max(0, Math.min(text.length(), Math.min(start, end)));
            selectionEnd = Math.max(selectionStart, Math.min(text.length(), Math.max(start, end)));
        }
    }

    private final List<State> states = new ArrayList<>();
    private int position = -1;
    private long characters;
    private long lastEditAt;
    private boolean mergeTyping;

    public void reset(String text, int start, int end) {
        states.clear();
        states.add(new State(text, start, end));
        position = 0;
        characters = text.length();
        breakGroup();
    }

    public State current() { return position < 0 ? null : states.get(position); }
    public boolean canUndo() { return position > 0; }
    public boolean canRedo() { return position >= 0 && position + 1 < states.size(); }
    public void breakGroup() { mergeTyping = false; }

    public void record(String before, String after, int beforeStart, int beforeEnd,
                       int afterStart, int afterEnd, long now) {
        if (before.equals(after)) return;
        if (current() == null || !current().text.equals(before)) reset(before, beforeStart, beforeEnd);
        State previous = current();
        boolean typing = smallChange(before, after) && beforeStart == beforeEnd && afterStart == afterEnd;
        boolean merge = typing && mergeTyping && canUndo() && now >= lastEditAt
                && now - lastEditAt <= TYPING_INTERVAL_MS
                && previous.selectionStart == beforeStart && previous.selectionEnd == beforeEnd;
        while (states.size() > position + 1) characters -= states.remove(states.size() - 1).text.length();
        if (merge) {
            characters -= states.set(position, new State(after, afterStart, afterEnd)).text.length();
        } else {
            // A caret move without typing must also become the undo destination.
            states.set(position, new State(before, beforeStart, beforeEnd));
            states.add(new State(after, afterStart, afterEnd));
            position++;
        }
        characters += after.length();
        if (merge && states.get(position - 1).text.equals(after)) {
            characters -= states.remove(position).text.length();
            position--;
            states.set(position, new State(after, afterStart, afterEnd));
            breakGroup();
            return;
        }
        while (states.size() > 2 && (states.size() > MAX_STATES || characters > MAX_CHARACTERS)) {
            characters -= states.remove(0).text.length();
            position--;
        }
        mergeTyping = typing;
        lastEditAt = now;
    }

    public State undo() {
        breakGroup();
        return canUndo() ? states.get(--position) : null;
    }

    public State redo() {
        breakGroup();
        return canRedo() ? states.get(++position) : null;
    }

    private static boolean smallChange(String before, String after) {
        int start = 0, limit = Math.min(before.length(), after.length());
        while (start < limit && before.charAt(start) == after.charAt(start)) start++;
        int oldEnd = before.length(), newEnd = after.length();
        while (oldEnd > start && newEnd > start && before.charAt(oldEnd - 1) == after.charAt(newEnd - 1)) {
            oldEnd--; newEnd--;
        }
        if (oldEnd - start + newEnd - start > 64) return false;
        int oldNewline = before.indexOf('\n', start), newNewline = after.indexOf('\n', start);
        return (oldNewline < 0 || oldNewline >= oldEnd) && (newNewline < 0 || newNewline >= newEnd);
    }
}
