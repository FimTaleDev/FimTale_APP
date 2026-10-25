package com.fimtale.utils;

import com.fimtale.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.Layout;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.*;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.ColorUtils;
import com.fimtale.model.ChapterResponse;
import com.fimtale.model.CommentResponse;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import io.noties.markwon.*;
import io.noties.markwon.core.spans.*;
import io.noties.markwon.ext.tables.*;
import io.noties.markwon.html.*;
import io.noties.markwon.html.tag.SimpleTagHandler;
import io.noties.markwon.image.glide.GlideImagesPlugin;
import java.util.*;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Native spans shared by work descriptions, chapters, comments and revealed BBCode. */
public final class BbCodeRendering {
    private BbCodeRendering() {}

    public static void setText(Markwon renderer, TextView view, String source) {
        Spanned text = BbCodeText.normalizeTables(renderer.toMarkdown(BbCode.toMarkdown(source)));
        int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        if (width <= 0) width = view.getResources().getDisplayMetrics().widthPixels - (int) (48 * view.getResources().getDisplayMetrics().density);
        BbCodeText.prepare(text, view.getPaint(), width);
        renderer.setParsedMarkdown(view, com.fimtale.ui.ImagePreview.images(text));
        BbCodeText.bindWidth(view);
        SpoilerSpan.bind(view);
    }

    public static Markwon create(Context context) {
        return Markwon.builder(context).usePlugin(htmlPlugin(context))
                .usePlugin(TablePlugin.create(context)).usePlugin(GlideImagesPlugin.create(context)).build();
    }

    public static HtmlPlugin htmlPlugin(Context context) {
        return HtmlPlugin.create(plugin -> plugin
                .emptyTagReplacement(new HtmlEmptyTagReplacement() {
                    @Override public String replace(HtmlTag tag) {
                        return tag.name().equals("hr") ? "\u200b" : super.replace(tag);
                    }
                })
                .addHandler(new SimpleTagHandler() {
                    @Override public Collection<String> supportedTags() {
                        return Arrays.asList("span", "div", "pre", "code", "br", "hr", "th");
                    }
                    @Override public Object getSpans(MarkwonConfiguration config, RenderProps props, HtmlTag tag) {
                        List<Object> spans = new ArrayList<>();
                        Map<String, String> attrs = tag.attributes();
                        Integer foreground = cssColor(attrs.get("data-color"));
                        Integer background = cssColor(attrs.get("data-background"));
                        if (foreground != null) spans.add(new ForegroundColorSpan(foreground));
                        if (background != null) spans.add(new BackgroundColorSpan(background));
                        if (attrs.containsKey("data-spoiler")) spans.add(new SpoilerSpan());
                        if (attrs.containsKey("data-size")) spans.add(new RelativeSizeSpan((float) Math.pow(1.2, Integer.parseInt(attrs.get("data-size")))));
                        if (attrs.containsKey("data-font")) spans.add(new TypefaceSpan(fontFamily(attrs.get("data-font"))));
                        String align = attrs.get("data-align");
                        if (align != null) spans.add(new AlignmentSpan.Standard(align.equals("center") ? Layout.Alignment.ALIGN_CENTER
                                : align.equals("right") ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL));
                        if (attrs.containsKey("data-indent")) spans.add(new IndentSpan(attrs.get("data-indent"), context.getResources().getDisplayMetrics().density));
                        switch (tag.name()) {
                            case "br": spans.add(new BbCodeText.SourceLineBreak()); break;
                            case "pre": spans.add(new CodeBlockSpan(config.theme())); break;
                            case "code":
                                // pre already sets the monospace face and size; don't shrink it twice.
                                if (!tag.isBlock() || tag.getAsBlock().parent() == null || !tag.getAsBlock().parent().name().equals("pre"))
                                    spans.add(new TypefaceSpan("monospace"));
                                break;
                            case "hr": spans.add(new ThematicBreakSpan(config.theme())); break;
                            case "th": spans.add(new StyleSpan(android.graphics.Typeface.BOLD)); break;
                        }
                        if (attrs.containsKey("data-collapse")) {
                            String title = attrs.getOrDefault("data-title", context.getString(R.string.bbcode_expand_default));
                            String source = new String(Base64.getDecoder().decode(attrs.get("data-collapse")),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            spans.add(new CollapseSpan(title, source));
                            spans.add(new CollapseButtonSpan(context, title));
                        }
                        if (attrs.containsKey("data-ref")) spans.add(new ClickableSpan() {
                            @Override public void onClick(View widget) { openReference(widget.getContext(), attrs.get("data-ref")); }
                        });
                        return spans.toArray();
                    }
                }).addHandler(new TableHandler(context)));
    }

    public static String fontFamily(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("mono") || lower.contains("courier")) return "monospace";
        if (lower.contains("sans") || lower.contains("arial") || lower.contains(AppStrings.get(R.string.bbcode_font_alias_sans))) return "sans-serif";
        if (lower.contains("serif") || lower.contains("times") || lower.contains(AppStrings.get(R.string.bbcode_font_alias_serif))) return "serif";
        return value;
    }

    public static Integer cssColor(String value) {
        if (value == null) return null;
        String color = value.toLowerCase(Locale.ROOT).trim();
        try {
            if (color.startsWith("rgb") || color.startsWith("hsl")) {
                String[] parts = color.substring(color.indexOf('(') + 1, color.length() - 1).split("\\s*,\\s*");
                boolean alpha = color.startsWith("rgba") || color.startsWith("hsla");
                if (parts.length != (alpha ? 4 : 3)) return null;
                int a = alpha ? channel(parts[3], 255) : 255;
                if (color.startsWith("rgb")) return Color.argb(a, channel(parts[0], 1), channel(parts[1], 1), channel(parts[2], 1));
                float h = Float.parseFloat(parts[0]);
                float s = Float.parseFloat(parts[1].replace("%", "")) / 100;
                float l = Float.parseFloat(parts[2].replace("%", "")) / 100;
                return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(new float[]{(h % 360 + 360) % 360, Math.max(0, Math.min(1, s)), Math.max(0, Math.min(1, l))}), a);
            }
            if (color.startsWith("#")) {
                String hex = color.substring(1);
                if (hex.length() == 3 || hex.length() == 4) {
                    StringBuilder expanded = new StringBuilder();
                    for (char c : hex.toCharArray()) expanded.append(c).append(c);
                    hex = expanded.toString();
                }
                // CSS alpha is last; Android alpha is first.
                if (hex.length() == 8) hex = hex.substring(6) + hex.substring(0, 6);
                return Color.parseColor("#" + hex);
            }
            switch (color) {
                case "orange": return 0xffffa500;
                case "rebeccapurple": return 0xff663399;
                case "pink": return 0xffffc0cb;
                case "brown": return 0xffa52a2a;
                case "gold": return 0xffffd700;
                case "violet": return 0xffee82ee;
                default: return Color.parseColor(color);
            }
        } catch (IllegalArgumentException e) { return null; }
    }
    private static int channel(String value, int multiplier) {
        float n = value.endsWith("%") ? Float.parseFloat(value.substring(0, value.length() - 1)) * 2.55f : Float.parseFloat(value) * multiplier;
        return Math.max(0, Math.min(255, Math.round(n)));
    }

    /** First-line indent measured with the reader's current font rather than a fixed dp value. */
    public static final class IndentSpan implements LeadingMarginSpan {
        final String value; final float density;
        int firstMargin;
        public IndentSpan(String value, float density) { this.value = value; this.density = density; prepare(16 * density, (int) (320 * density)); }
        public void prepare(float textSize, int width) {
            String unit = value.replaceAll("[0-9.]", "");
            float amount = Float.parseFloat(value.substring(0, value.length() - unit.length()));
            float pixels = amount * (unit.equals("em") || unit.equals("rem") ? textSize : unit.equals("%") ? width / 100f : unit.equals("pt") ? density * 4 / 3 : density);
            firstMargin = Math.max(0, Math.min(width / 2, Math.round(pixels)));
        }
        @Override public int getLeadingMargin(boolean first) { return first ? firstMargin : 0; }
        @Override public void drawLeadingMargin(android.graphics.Canvas c, android.graphics.Paint p, int x, int dir,
                int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {}
    }

    private static boolean alive(Context context) {
        return !(context instanceof Activity) || (!((Activity) context).isFinishing() && !((Activity) context).isDestroyed());
    }
    private static void openUrl(Context context, String url) {
        if (!alive(context) || BbCode.safeUrl(url, false) == null) return;
        if (com.fimtale.modules.ModuleLinks.open(context, url)) return;
        if (com.fimtale.community.CommunityLinks.openComment(context, url)) return;
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(context, context.getString(R.string.error_no_link_handler), Toast.LENGTH_SHORT).show(); }
    }
    private static void referenceError(Context context) {
        if (alive(context)) Toast.makeText(context, context.getString(R.string.bbcode_reference_failed), Toast.LENGTH_SHORT).show();
    }
    private static void openReference(Context context, String ref) {
        String[] parts = ref.split(":");
        int id = Integer.parseInt(parts[1]);
        switch (parts[0]) {
            case "1": openUrl(context, SiteUrls.work(id)); break;
            case "5": openUrl(context, SiteUrls.SITE + "/channel/" + id); break;
            case "7": openUrl(context, SiteUrls.SITE + "/user/" + id); break;
            case "3":
                Toast.makeText(context, context.getString(R.string.bbcode_loading_reference), Toast.LENGTH_SHORT).show();
                RetrofitClient.getInstance().getChapter(id).enqueue(new Callback<ChapterResponse>() {
                    @Override public void onResponse(Call<ChapterResponse> call, Response<ChapterResponse> response) {
                        ChapterResponse data = response.body();
                        if (response.isSuccessful() && data != null && data.chapter != null && data.chapter.workId > 0)
                            openUrl(context, SiteUrls.chapter(data.chapter.workId, id));
                        else referenceError(context);
                    }
                    @Override public void onFailure(Call<ChapterResponse> call, Throwable t) { referenceError(context); }
                }); break;
            case "4":
                Toast.makeText(context, context.getString(R.string.bbcode_loading_reference), Toast.LENGTH_SHORT).show();
                RetrofitClient.getInstance().getComment(id).enqueue(new Callback<CommentResponse>() {
                    @Override public void onResponse(Call<CommentResponse> call, Response<CommentResponse> response) {
                        CommentResponse data = response.body();
                        if (response.isSuccessful() && data != null && data.comment != null && data.comment.workId > 0) {
                            String base = data.comment.chapterId > 0 ? SiteUrls.chapter(data.comment.workId, data.comment.chapterId) : SiteUrls.work(data.comment.workId);
                            openUrl(context, base + "#comment-" + id);
                        } else referenceError(context);
                    }
                    @Override public void onFailure(Call<CommentResponse> call, Throwable t) { referenceError(context); }
                }); break;
        }
    }

    /** Normalizes merged cells to a rectangular grid; their text remains in the first cell. */
    private static final class TableHandler extends TagHandler {
        private final TableTheme theme;
        TableHandler(Context context) { theme = TableTheme.create(context); }
        @Override public Collection<String> supportedTags() { return Collections.singleton("table"); }
        @Override public void handle(MarkwonVisitor visitor, MarkwonHtmlRenderer renderer, HtmlTag tag) {
            if (!tag.isBlock()) return;
            visitChildren(visitor, renderer, tag.getAsBlock());
            List<HtmlTag.Block> rows = new ArrayList<>(); collectRows(tag.getAsBlock(), rows);
            List<List<TableRowSpan.Cell>> grid = new ArrayList<>();
            int[] occupied = new int[32]; int columns = 0;
            for (HtmlTag.Block row : rows) {
                List<TableRowSpan.Cell> cells = new ArrayList<>(); int column = 0;
                for (HtmlTag.Block cell : row.children()) {
                    if (!cell.name().equals("td") && !cell.name().equals("th")) continue;
                    while (column < 32 && occupied[column] > 0) { cells.add(emptyCell()); column++; }
                    if (column >= 32) break;
                    String align = cell.attributes().get("align");
                    if (align == null) {
                        String css = cell.attributes().getOrDefault("style", "");
                        align = css.contains("center") ? "center" : css.contains("right") ? "right" : "left";
                    }
                    int gravity = align.equals("center") ? TableRowSpan.ALIGN_CENTER : align.equals("right") ? TableRowSpan.ALIGN_RIGHT : TableRowSpan.ALIGN_LEFT;
                    CharSequence contents = visitor.builder().subSequence(cell.start(), cell.end());
                    SpannableStringBuilder styled = new SpannableStringBuilder(contents);
                    BbCodeText.normalizeLineBreaks(styled);
                    SpoilerSpan.prepare(styled);
                    cells.add(new TableRowSpan.Cell(gravity, styled));
                    int colspan = count(cell, "colspan", 32 - column), rowspan = count(cell, "rowspan", 100);
                    for (int i = 0; i < colspan; i++) { occupied[column++] = rowspan; if (i > 0) cells.add(emptyCell()); }
                }
                while (column < 32 && occupied[column] > 0) { cells.add(emptyCell()); column++; }
                for (int i = 0; i < occupied.length; i++) occupied[i] = Math.max(0, occupied[i] - 1);
                columns = Math.max(columns, cells.size()); grid.add(cells);
            }
            // A narrow phone cannot lay out dozens of padded columns. Keep the original cell
            // paragraphs instead of dropping overflow cells or giving StaticLayout a negative width.
            if (columns > 8) return;
            for (int i = 0; i < rows.size(); i++) {
                HtmlTag.Block row = rows.get(i); List<TableRowSpan.Cell> cells = grid.get(i);
                if (cells.isEmpty() || row.end() <= row.start()) continue;
                while (cells.size() < columns) cells.add(emptyCell());
                boolean header = !row.children().isEmpty() && row.children().get(0).name().equals("th");
                SpannableBuilder.setSpans(visitor.builder(), new FittedTableRowSpan(theme, cells, header, i % 2 != 0), row.start(), row.end());
            }
        }
        private static int count(HtmlTag tag, String name, int max) {
            try { return Math.max(1, Math.min(max, Integer.parseInt(tag.attributes().get(name)))); }
            catch (RuntimeException e) { return 1; }
        }
        private static TableRowSpan.Cell emptyCell() { return new TableRowSpan.Cell(TableRowSpan.ALIGN_LEFT, ""); }
        private static void collectRows(HtmlTag.Block parent, List<HtmlTag.Block> rows) {
            for (HtmlTag.Block child : parent.children()) {
                if (child.name().equals("tr")) rows.add(child);
                else if (!child.name().equals("table")) collectRows(child, rows);
            }
        }
    }
}
