package com.fimtale.bbcode;

import org.jparsec.Parser;
import org.jparsec.Parsers;
import org.jparsec.Scanners;
import org.jparsec.Token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * jparsec grammar shared by the reader and source editor. The structural actions implement
 * FimTale's implicit list endings and incomplete-tag recovery; offsets always refer to UTF-16
 * units in the original input. Parser definitions are immutable, and each call owns its tree.
 */
public final class BbCodeParser {
    private static final Parser<Void> SPACE = Scanners.many(c -> " \t\n\r\f\u000b".indexOf(c) >= 0);
    private static final Parser<String> SINGLE_QUOTED = quoted('\'');
    private static final Parser<String> DOUBLE_QUOTED = quoted('"');
    private static final Parser<String> NAME = Parsers.or(
            Scanners.isChar(BbCodeParser::letter).next(Scanners.many(c -> letter(c) || digit(c) || c == '-')).source(),
            Scanners.string("*").source());
    private static final Parser<String> TAIL = Parsers.or(
            Scanners.many1(c -> c != ']' && c != '\'' && c != '"'),
            SINGLE_QUOTED, DOUBLE_QUOTED).skipMany().source();
    private static final Parser<Header> HEADER = Scanners.isChar('[').next(Parsers.sequence(
            Scanners.isChar('/').retn(true).optional(false), NAME, TAIL,
            (closing, name, tail) -> new Header(closing, name.toLowerCase(Locale.ROOT), tail)))
            .followedBy(Scanners.isChar(']'));
    private static final Parser<Map.Entry<String, String>> ATTRIBUTE = Parsers.sequence(
            Scanners.many1(c -> letter(c) || digit(c) || c == '_' || c == '-').source()
                    .followedBy(SPACE).followedBy(Scanners.isChar('=')).followedBy(SPACE),
            Parsers.or(DOUBLE_QUOTED, SINGLE_QUOTED,
                    Scanners.many1(c -> " \t\n\r\f\u000b".indexOf(c) < 0).source()),
            (key, value) -> new java.util.AbstractMap.SimpleImmutableEntry<>(key.toLowerCase(Locale.ROOT), value));
    private static final Parser<List<Map.Entry<String, String>>> ATTRIBUTES = Parsers.or(
            ATTRIBUTE, Scanners.ANY_CHAR.retn((Map.Entry<String, String>) null)).many();

    private BbCodeParser() {}

    public static final class Node {
        public final String name, argument, text;
        public final Map<String, String> attributes;
        public final int start, contentStart, contentEnd, end;
        public final boolean closed;
        public final List<Node> children;

        private Node(Builder builder) {
            name = builder.name; argument = builder.argument; text = builder.text;
            attributes = builder.attributes;
            start = builder.start; contentStart = builder.contentStart;
            contentEnd = builder.contentEnd; end = builder.end; closed = builder.closed;
            List<Node> nodes = new ArrayList<>(builder.children.size());
            for (Builder child : builder.children) nodes.add(new Node(child));
            children = Collections.unmodifiableList(nodes);
        }
    }

    /** Includes unknown tags so the editor can retain their exact source ranges. */
    public static Node parse(String source) { return parse(source, null); }

    /** A renderer can restrict recognized tags; other tokens remain literal text. */
    public static Node parse(String source, Set<String> supportedTags) {
        State state = new State(source == null ? "" : source, supportedTags);
        if (state.source.indexOf(']') < 0) {
            state.text(0, state.source.length());
        } else {
            Parser<Void> tag = HEADER.token().next(state::tag);
            Parser<Void> text = Parsers.or(Scanners.many1(c -> c != '['), Scanners.isChar('['))
                    .token().map(token -> { state.text(token.index(), token.index() + token.length()); return null; });
            Parsers.or(tag, text).skipMany().parse(state.source);
        }
        for (Builder node : state.stack) node.finish(state.source.length(), state.source.length(), false);
        state.root.closed = true;
        return new Node(state.root);
    }

    public static boolean literal(String name) {
        return name.equals("code") || name.equals("markdown") || name.equals("img") || name.equals("handbook");
    }

    private static boolean letter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static Parser<String> quoted(char quote) {
        return Scanners.many(c -> c != quote).source().between(Scanners.isChar(quote), Scanners.isChar(quote));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                && value.charAt(0) == value.charAt(value.length() - 1)) return value.substring(1, value.length() - 1);
        return value;
    }

    private static final class Header {
        final boolean closing;
        final String name, tail;
        Header(boolean closing, String name, String tail) { this.closing = closing; this.name = name; this.tail = tail; }
    }

    private static final class Builder {
        final String name;
        String argument = "", text;
        Map<String, String> attributes = Collections.emptyMap();
        final int start, contentStart;
        int contentEnd, end;
        boolean closed;
        final List<Builder> children = new ArrayList<>();

        Builder(String name, int start, int contentStart) {
            this.name = name; this.start = start; this.contentStart = contentStart;
        }
        void finish(int contentEnd, int end, boolean closed) {
            this.contentEnd = contentEnd; this.end = end; this.closed = closed;
        }
    }

    private static final class State {
        final String source;
        final Set<String> supportedTags;
        final Builder root = new Builder("root", 0, 0);
        final List<Builder> stack = new ArrayList<>();

        State(String source, Set<String> supportedTags) {
            this.source = source; this.supportedTags = supportedTags; stack.add(root);
        }

        void text(int start, int end) {
            if (start == end) return;
            Builder node = new Builder("", start, start);
            node.text = source.substring(start, end); node.finish(end, end, true);
            stack.get(stack.size() - 1).children.add(node);
        }

        Parser<Void> tag(Token token) {
            Header header = (Header) token.value();
            String name = header.name;
            int start = token.index(), end = start + token.length();
            if (supportedTags != null && !supportedTags.contains(name)) {
                text(start, end); return Parsers.always();
            }
            if (header.closing) {
                int match = find(name);
                if (match == 0) text(start, end);
                else while (stack.size() > match) {
                    int index = stack.size() - 1;
                    Builder node = stack.remove(index);
                    node.finish(start, index == match ? end : start, index == match || node.name.equals("*"));
                }
                return Parsers.always();
            }
            if (name.equals("*")) {
                int list = find("list");
                if (list == 0) { text(start, end); return Parsers.always(); }
                while (stack.size() > list + 1) {
                    Builder node = stack.remove(stack.size() - 1);
                    node.finish(start, start, node.name.equals("*"));
                }
            }
            // Preserve the reader's historical root-inclusive limit and the editor's 128 tags.
            if (stack.size() >= (supportedTags == null ? 129 : 128)) {
                text(start, end); return Parsers.always();
            }
            Builder node = new Builder(name, start, end);
            String tail = header.tail.trim();
            if (tail.startsWith("=")) node.argument = unquote(tail.substring(1).trim());
            else {
                Map<String, String> attributes = new HashMap<>();
                for (Map.Entry<String, String> attr : ATTRIBUTES.parse(tail))
                    if (attr != null) attributes.put(attr.getKey(), attr.getValue());
                node.attributes = Collections.unmodifiableMap(attributes);
            }
            stack.get(stack.size() - 1).children.add(node);
            if (name.equals("br") || name.equals("hr")) node.finish(end, end, true);
            else if (literal(name)) return raw(node);
            else stack.add(node);
            return Parsers.always();
        }

        private int find(String name) {
            int index = stack.size() - 1;
            while (index > 0 && !stack.get(index).name.equals(name)) index--;
            return index;
        }

        private Parser<Void> raw(Builder node) {
            Parser<Void> close = Scanners.stringCaseInsensitive("[/" + node.name + "]");
            Parser<Void> body = close.retn(true).or(Parsers.EOF.retn(true)).not()
                    .next(Scanners.ANY_CHAR).skipMany();
            return body.next(Parsers.sequence(Parsers.INDEX, close.retn(true).optional(false), Parsers.INDEX,
                    (contentEnd, closed, end) -> {
                        node.finish(contentEnd, end, closed);
                        if (!closed) {
                            stack.add(node); text(node.contentStart, contentEnd); stack.remove(stack.size() - 1);
                        }
                        return null;
                    }));
        }
    }
}
