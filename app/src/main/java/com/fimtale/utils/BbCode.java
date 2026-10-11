package com.fimtale.utils;

import com.fimtale.R;
import com.fimtale.bbcode.BbCodeParser;
import com.fimtale.bbcode.BbCodeParser.Node;

import com.fimtale.network.SiteUrls;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Link;
import org.commonmark.node.Image;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FimTale's BBCode dialect. Only [markdown] opts into Markdown; all other text is literal. */
public final class BbCode {
    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "b", "i", "u", "s", "sub", "sup", "color", "bg-color", "size", "font", "url",
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "quote", "left", "center", "right", "justify",
            "indent", "list", "*", "table", "tr", "th", "td", "img", "code", "markdown", "handbook",
            "collapse", "spoiler", "mention", "hash", "ref", "br", "hr"));
    private static final Pattern EMOJI = Pattern.compile(":ftemoji_([a-zA-Z0-9_]+):");
    private static final List<Extension> MD_EXTENSIONS = Arrays.asList(TablesExtension.create(), StrikethroughExtension.create());
    private static final Parser MARKDOWN = Parser.builder().extensions(MD_EXTENSIONS).build();
    private static final HtmlRenderer MARKDOWN_HTML = HtmlRenderer.builder().extensions(MD_EXTENSIONS)
            .escapeHtml(true).softbreak("<br>").build();
    private BbCode() {}

    /** Returns safe HTML accepted by Markwon, retaining the existing public entry point. */
    public static String toMarkdown(String source) {
        if (source == null || source.isEmpty()) return "";
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        return "<div>" + render(normalized, BbCodeParser.parse(normalized, TAGS), new Style()) + "</div>";
    }

    private static final class Style {
        String color = "", background = "", font = "";
        int size;
        Style() {}
        Style(Style other) { color = other.color; background = other.background; font = other.font; size = other.size; }
        String apply(String text) {
            String attrs = attribute("data-color", color) + attribute("data-background", background)
                    + attribute("data-font", font) + (size == 0 ? "" : attribute("data-size", String.valueOf(size)));
            return attrs.isEmpty() || text.isEmpty() ? text : "<span" + attrs + ">" + text + "</span>";
        }
    }

    private static String render(String source, Node tag, Style inherited) {
        if (tag.text != null) return inherited.apply(literal(tag.text));
        String name = tag.name, value = decode(tag.argument);
        Style style = new Style(inherited);
        if (tag.closed) {
            if (name.equals("color") && safeColor(value)) style.color = value;
            if (name.equals("bg-color") && safeColor(value)) style.background = value;
            if (name.equals("size")) {
                if (value.equalsIgnoreCase("larger")) style.size = Math.min(8, style.size + 1);
                if (value.equalsIgnoreCase("smaller")) style.size = Math.max(-8, style.size - 1);
            }
            if (name.equals("font") && value.matches("[\\p{L} ,_-]{1,80}")) style.font = value;
        }
        if (tag.closed && name.equals("collapse")) {
            String title = value.trim().isEmpty() ? AppStrings.get(R.string.bbcode_expand_default) : value.replace('\n', ' ');
            String encoded = Base64.getEncoder().encodeToString(source.substring(tag.contentStart, tag.contentEnd).getBytes(StandardCharsets.UTF_8));
            // The body is rendered only in its bottom sheet, never in the surrounding article.
            return "<p><span data-collapse=\"" + encoded + "\"" + attribute("data-title", title)
                    + ">" + escape(title) + "</span></p>";
        }
        StringBuilder children = new StringBuilder();
        for (Node child : tag.children) {
            if ((name.equals("list") || name.equals("table") || name.equals("tr"))
                    && child.text != null && child.text.trim().isEmpty()) continue;
            children.append(render(source, child, style));
        }
        String body = children.toString();
        if (!tag.closed && !name.equals("root")) return literal(source.substring(tag.start, tag.contentStart)) + body;
        if (name.matches("b|i|u|s|sub|sup|h[1-6]|p|table|tr|th|td")) {
            String attrs = "";
            if (name.equals("td") || name.equals("th")) {
                attrs = positiveAttr(tag, "colspan", 32) + positiveAttr(tag, "rowspan", 100)
                        + attribute("align", alignment(tag.attributes.get("align")));
                // Keep even an empty cell addressable by the native table renderer.
                if (body.isEmpty()) body = "&#160;";
            }
            return "<" + name + attrs + ">" + body + "</" + name + ">";
        }
        switch (name) {
            case "spoiler": return "<span data-spoiler=\"true\">" + body + "</span>";
            case "br": return "<br>";
            case "hr": return "<hr>";
            case "quote": return "<blockquote>" + (value.isEmpty() ? "" : "<b>" + literal(value) + "</b><br>") + body + "</blockquote>";
            case "left": case "right": case "center": case "justify":
                return "<div data-align=\"" + name + "\">" + body + "</div>";
            case "indent":
                String indent = value.isEmpty() ? "2em" : value;
                return "<div" + attribute("data-indent", indent.matches("(?:\\d{1,4}(?:\\.\\d{1,3})?)(?:em|rem|px|pt|%)") ? indent : "2em") + ">" + body + "</div>";
            case "list":
                String list = value.matches("[1aAiI]") ? "ol" : "ul";
                return "<" + list + ">" + body + "</" + list + ">";
            case "*": return "<li>" + body + "</li>";
            case "url": return link(safeUrl(value.isEmpty() ? plain(source, tag).trim() : value, false), body);
            case "mention":
                String user = plain(source, tag).trim().replaceFirst("^@", "");
                if (user.isEmpty()) {
                    for (Node child : tag.children) {
                        Matcher legacyName = Pattern.compile("/user/@([^/?#]+)").matcher(child.argument);
                        if (child.name.equals("url") && legacyName.find()) { user = legacyName.group(1); break; }
                    }
                }
                String uid = positive(value, Integer.MAX_VALUE);
                return link(SiteUrls.SITE + "/user/@" + encode(user) + (uid.isEmpty() ? "" : "?uid=" + uid), "@" + escape(user));
            case "hash":
                String hash = plain(source, tag).trim().replaceAll("^#+|#+$", "");
                String query = "hashtags:\"" + escapeLucene(hash) + "\"";
                return link(SiteUrls.SITE + "/search/work?filter=" + encode(query), "#" + escape(hash) + "#");
            case "ref":
                String id = positive(tag.attributes.get("id"), Integer.MAX_VALUE);
                String type = tag.attributes.getOrDefault("type", "");
                if (id.isEmpty() || !type.matches("[13457]")) return body;
                String label = plain(source, tag).trim();
                if (label.isEmpty()) label = AppStrings.get(R.string.common_entity_number, (type.equals("1") ? AppStrings.get(R.string.work_label) : type.equals("3") ? AppStrings.get(R.string.work_chapter_label) : type.equals("4") ? AppStrings.get(R.string.comments_title) : type.equals("5") ? AppStrings.get(R.string.report_target_channel) : AppStrings.get(R.string.profile_user_label)), id);
                return "<p><span data-ref=\"" + type + ":" + id + "\">" + escape(label) + "</span></p>";
            case "img":
                String src = safeUrl(decode(source.substring(tag.contentStart, tag.contentEnd)).trim(), true);
                if (src == null) return "";
                return "<p><img" + attribute("src", src) + attribute("alt", decode(tag.attributes.getOrDefault("alt", "")))
                        + dimensionAttr(tag, "width") + dimensionAttr(tag, "height") + "></p>";
            case "code": return "<pre><code>" + escape(decode(source.substring(tag.contentStart, tag.contentEnd))).replace("\n", "&#10;") + "</code></pre>";
            case "markdown": return markdown(decode(source.substring(tag.contentStart, tag.contentEnd)));
            case "handbook": return "<p>" + escape(AppStrings.get(R.string.bbcode_handbook_unavailable)) + "</p>";
            default: return body;
        }
    }

    private static String markdown(String source) {
        org.commonmark.node.Node doc = MARKDOWN.parse(source.replaceAll("(?i)<br\\s*/?>", "\n\n"));
        doc.accept(new AbstractVisitor() {
            @Override public void visit(Link link) {
                String url = safeUrl(link.getDestination(), false);
                visitChildren(link);
                if (url == null) unwrap(link); else link.setDestination(url);
            }
            @Override public void visit(Image img) {
                String url = safeUrl(img.getDestination(), true);
                if (url == null) unwrap(img); else img.setDestination(url);
            }
            private void unwrap(org.commonmark.node.Node node) {
                while (node.getFirstChild() != null) node.insertBefore(node.getFirstChild());
                node.unlink();
            }
        });
        String html = MARKDOWN_HTML.render(doc);
        Matcher code = Pattern.compile("(?s)<pre>.*?</pre>").matcher(html);
        StringBuffer result = new StringBuffer();
        while (code.find()) code.appendReplacement(result, Matcher.quoteReplacement(code.group().replace("\n", "&#10;")));
        code.appendTail(result);
        return result.toString().replace("\n", "");
    }

    private static String plain(String source, Node tag) {
        if (tag.text != null) return decode(tag.text);
        if (tag.closed && BbCodeParser.literal(tag.name)) return decode(source.substring(tag.contentStart, tag.contentEnd));
        StringBuilder out = new StringBuilder();
        for (Node child : tag.children) out.append(plain(source, child));
        return out.toString();
    }
    private static String literal(String value) {
        String decoded = decode(value);
        Matcher emoji = EMOJI.matcher(decoded);
        StringBuilder out = new StringBuilder(); int end = 0;
        while (emoji.find()) {
            out.append(escapeLines(decoded.substring(end, emoji.start())));
            out.append("<img data-emoji=\"true\" width=\"1.2em\" height=\"1.2em\"")
                    .append(attribute("src", SiteUrls.SITE + "/img/ftemoji/" + emoji.group(1) + ".png"))
                    .append(attribute("alt", emoji.group())).append(">");
            end = emoji.end();
        }
        return out.append(escapeLines(decoded.substring(end))).toString();
    }
    private static String escapeLines(String s) { return escape(s).replace("\n", "<br>").replace("  ", " &#160;"); }
    private static String decode(String s) {
        return s == null ? "" : s.replace("&#91;", "[").replace("&#93;", "]");
    }
    static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String attribute(String key, String value) { return value == null || value.isEmpty() ? "" : " " + key + "=\"" + escape(value) + "\""; }
    private static String link(String url, String body) { return url == null ? body : "<a" + attribute("href", url) + ">" + body + "</a>"; }
    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String escapeLucene(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("+-!(){}[]^\"~*?:\\/".indexOf(c) >= 0) out.append('\\');
            else if ((c == '&' || c == '|') && i + 1 < value.length() && value.charAt(i + 1) == c) {
                out.append('\\').append(c).append(c); i++; continue;
            }
            out.append(c);
        }
        return out.toString();
    }
    private static String alignment(String s) { return s != null && s.matches("left|center|right|justify") ? s : ""; }
    private static String positive(String value, int max) {
        try { int number = Integer.parseInt(value); return number > 0 ? String.valueOf(Math.min(max, number)) : ""; }
        catch (RuntimeException e) { return ""; }
    }
    private static String positiveAttr(Node tag, String attr, int max) { return attribute(attr, positive(tag.attributes.get(attr), max)); }
    private static String dimensionAttr(Node tag, String attr) {
        String value = tag.attributes.getOrDefault(attr, "");
        return attribute(attr, value.matches("\\d{1,4}(?:\\.\\d{1,2})?(?:px|%|em)?") ? value : "");
    }
    static boolean safeColor(String value) {
        return value.matches("#[a-fA-F0-9]{3,8}|[a-zA-Z]{1,24}|(?:rgb|hsl)a?\\([0-9.,% +\\-]+\\)");
    }
    public static String safeUrl(String value, boolean image) {
        if (value == null) return null;
        String url = value.trim();
        if (url.isEmpty() || url.matches("(?s).*[\\x00-\\x20\\x7f\\\\].*") || url.startsWith("//")) return null;
        if (image && url.matches("(?i)data:image/(?:png|jpe?g|gif|webp);base64,[a-z0-9+/]+={0,2}")) return url;
        try {
            URI uri = URI.create(url);
            if (url.startsWith("/") || (!image && url.startsWith("#"))) uri = URI.create(SiteUrls.SITE + "/").resolve(uri);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) { return null; }
    }
}
