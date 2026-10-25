package com.fimtale.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Counts visible non-space code points rather than BBCode delimiters or image URLs. */
public final class EditorTextStats {
    private EditorTextStats() { }

    public static int count(String source) { return count(source, 0, source.length()); }

    public static int count(String source, int start, int end) {
        start = Math.max(0, Math.min(source.length(), start));
        end = Math.max(start, Math.min(source.length(), end));
        List<int[]> hidden = new ArrayList<>();
        for (BbCodeSyntax.Node node : BbCodeSyntax.parse(source)) {
            if (node.name.equals("img") || node.name.equals("hr") || node.name.equals("handbook")) {
                hidden.add(new int[]{node.start, node.end});
            } else {
                hidden.add(new int[]{node.start, node.contentStart});
                hidden.add(new int[]{node.contentEnd, node.end});
            }
        }
        hidden.sort(Comparator.comparingInt(range -> range[0]));
        int cursor = start, count = 0;
        for (int[] range : hidden) {
            if (range[1] <= cursor || range[0] >= end) continue;
            if (range[0] > cursor) count += visibleCount(source.substring(cursor, range[0]));
            cursor = Math.min(end, Math.max(cursor, range[1]));
        }
        if (cursor < end) count += visibleCount(source.substring(cursor, end));
        return count;
    }

    private static int visibleCount(String text) {
        return (int) VisualEditing.decodeEntities(text).codePoints()
                .filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).count();
    }
}
