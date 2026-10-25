package com.fimtale.editor;

import org.junit.Test;
import static org.junit.Assert.*;

public class EditorHistoryTest {
    @Test public void composingTextAndParagraphMarkupUndoAsOneTypingGroup() {
        EditorHistory history = new EditorHistory();
        history.reset("", 0, 0);
        String composing = "[indent=2]ni[/indent]", committed = "[indent=2]你好[/indent]";
        history.record("", composing, 0, 0, 12, 12, 100);
        history.record(composing, committed, 12, 12, 12, 12, 200);
        assertEquals("", history.undo().text);
        assertFalse(history.canUndo());
        assertEquals(committed, history.redo().text);
    }

    @Test public void formattingIsIndependentAndRestoresTheOriginalSelection() {
        EditorHistory history = new EditorHistory();
        history.reset("正文", 2, 2);
        history.record("正文", "正文。", 2, 2, 3, 3, 100);
        history.breakGroup();
        history.record("正文。", "[b]正文[/b]。", 0, 2, 3, 5, 200);
        history.breakGroup();
        EditorHistory.State restored = history.undo();
        assertEquals("正文。", restored.text);
        assertEquals(0, restored.selectionStart);
        assertEquals(2, restored.selectionEnd);
        assertEquals("正文", history.undo().text);
    }

    @Test public void typingAfterUndoReplacesTheRedoBranch() {
        EditorHistory history = new EditorHistory();
        history.reset("原文", 2, 2);
        history.record("原文", "原文甲", 2, 2, 3, 3, 100);
        history.undo();
        history.record("原文", "原文乙", 2, 2, 3, 3, 200);
        assertFalse(history.canRedo());
        assertEquals("原文", history.undo().text);
        assertEquals("原文乙", history.redo().text);
    }

    @Test public void changingDocumentsCannotRestoreAnotherDocumentsText() {
        EditorHistory history = new EditorHistory();
        history.reset("甲", 1, 1);
        history.record("甲", "甲乙", 1, 1, 2, 2, 100);
        history.reset("另一篇文章", 0, 0);
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
    }

    @Test public void typingThenDeletingTheSameTextDoesNotLeaveAnEmptyUndoStep() {
        EditorHistory history = new EditorHistory();
        history.reset("正文", 2, 2);
        history.record("正文", "正文甲", 2, 2, 3, 3, 100);
        history.record("正文甲", "正文", 3, 3, 2, 2, 200);
        assertFalse(history.canUndo());
        assertEquals("正文", history.current().text);
    }

    @Test public void historyEvictionKeepsUndoAndRedoConsistent() {
        EditorHistory history = new EditorHistory();
        history.reset("", 0, 0);
        String text = "";
        for (int i = 0; i < 150; i++) {
            history.breakGroup();
            history.record(text, text + "字", text.length(), text.length(), text.length() + 1, text.length() + 1, i * 2000L);
            text += "字";
        }
        int undo = 0;
        while (history.canUndo()) { history.undo(); undo++; }
        assertTrue(undo <= 99);
        int redo = 0;
        while (history.canRedo()) { history.redo(); redo++; }
        assertEquals(undo, redo);
        assertEquals(text, history.current().text);
    }
}
