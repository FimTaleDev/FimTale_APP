package com.fimtale.editor;

import java.util.*;
import com.fimtale.bbcode.BbCodeParser;

/** Source offsets for BBCode. Unlike rendering, parsing never normalizes the author's text. */
public final class BbCodeSyntax {
    private BbCodeSyntax() {}

    public static final class Node {
        public final String name, argument;
        public final Map<String, String> attributes;
        public final int start, contentStart, contentEnd, end;
        private Node(String name, String argument, Map<String, String> attributes, int start, int contentStart, int contentEnd, int end) {
            this.name = name; this.argument = argument; this.attributes = attributes;
            this.start = start; this.contentStart = contentStart; this.contentEnd = contentEnd; this.end = end;
        }
    }

    public static List<Node> parse(String source) {
        List<Node> result = new ArrayList<>();
        collect(BbCodeParser.parse(source), result);
        return result;
    }

    private static void collect(BbCodeParser.Node parent, List<Node> result) {
        for (BbCodeParser.Node node : parent.children) {
            if (node.text != null) continue;
            if (node.closed) result.add(new Node(node.name, node.argument, node.attributes,
                    node.start, node.contentStart, node.contentEnd, node.end));
            collect(node, result);
        }
    }

    /** The paragraph under the caret stays editable, including its delimiters. */
    public static int[] activeParagraph(String source, int selectionStart, int selectionEnd) {
        if (selectionStart < 0 || selectionEnd < 0) return new int[]{-1, -1};
        int from = Math.min(source.length(), Math.min(selectionStart, selectionEnd));
        int to = Math.min(source.length(), Math.max(selectionStart, selectionEnd));
        int start = from == 0 ? 0 : source.lastIndexOf('\n', from - 1) + 1;
        int end = source.indexOf('\n', to);
        return new int[]{start, end < 0 ? source.length() : end};
    }
}
