package com.fimtale.editor;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import com.fimtale.R;
import com.google.android.material.color.MaterialColors;

/** The focused cell is a real EditText positioned inside the document's table. */
public final class BbCodeEditorLayout extends FrameLayout implements BbCodeEditText.TableCellListener {
    private BbCodeEditText body, cell;
    private final Object cellRange = new Object();
    private boolean syncing;
    private boolean positioning;
    private boolean blockMode;
    private String expectedSource;
    private int cellSelectionStart, cellSelectionEnd;

    public BbCodeEditorLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (body == null && child instanceof BbCodeEditText) {
            body = (BbCodeEditText) child;
            body.setTableCellListener(this);
            body.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!syncing && cell != null) finishCellEditing();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }
    public BbCodeEditText activeEditor() { return cell == null ? body : cell; }
    public boolean isEditingCell() { return cell != null; }
    public int[] sourceSelection() {
        BbCodeEditText active = activeEditor();
        int offset = cell == null ? 0 : Math.max(0, body.getText().getSpanStart(cellRange));
        int start = Math.max(0, Math.min(active.getSelectionStart(), active.getSelectionEnd()));
        int end = Math.max(start, Math.max(active.getSelectionStart(), active.getSelectionEnd()));
        return new int[]{Math.min(body.length(), offset + start), Math.min(body.length(), offset + end)};
    }

    @Override public void edit(BbCodeEditText.TableHit hit) {
        finishCellEditing();
        blockMode = false;
        beginInput(hit.start(), hit.end(), body.getText().subSequence(hit.start(), hit.end()).toString(), hit.bounds,
                hit.table.padding, hit.cell.source.node.name.equals("th"), hit.cell.layout.getAlignment(),
                getContext().getString(R.string.editor_table_cell_position, hit.cell.source.row + 1, hit.cell.source.column + 1));
    }
    @Override public void editBlock(BbCodeEditText.BlockHit hit) {
        finishCellEditing(); blockMode = true;
        beginInput(hit.start, hit.end, hit.content, hit.bounds, 0, false, android.text.Layout.Alignment.ALIGN_NORMAL, getContext().getString(R.string.reader_edit_content));
    }
    private void beginInput(int start, int end, String content, RectF bounds, int padding, boolean header,
            android.text.Layout.Alignment alignment, String description) {
        Editable source = body.getText();
        if (source == null || end > source.length()) return;
        expectedSource = source.subSequence(start, end).toString();
        source.setSpan(cellRange, start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        cell = new BbCodeEditText(getContext(), null);
        cell.setEditingListener(body.getEditingListener());
        cell.setHistoryOwner(body);
        cell.setLinkClickListener(body.getLinkClickListener());
        cell.setMarkdownClickListener(body.getMarkdownClickListener());
        cell.setParagraphIndentEnabled(blockMode && body.isParagraphIndentEnabled());
        cell.setId(R.id.editorTableCell);
        cell.setContentDescription(description);
        cell.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        cell.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        cell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, body.getTextSize());
        cell.setTextColor(body.getCurrentTextColor());
        cell.setTypeface(body.getTypeface(), header ? Typeface.BOLD : Typeface.NORMAL);
        cell.setLineSpacing(body.getLineSpacingExtra(), body.getLineSpacingMultiplier());
        cell.setGravity(Gravity.TOP | (alignment == android.text.Layout.Alignment.ALIGN_CENTER
                ? Gravity.CENTER_HORIZONTAL : alignment == android.text.Layout.Alignment.ALIGN_OPPOSITE ? Gravity.RIGHT : Gravity.LEFT));
        cell.setPadding(padding, padding, padding, padding);
        cell.setIncludeFontPadding(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface));
        background.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)),
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary));
        cell.setBackground(background);
        body.setSelection(end);
        cell.setText(content); cell.setSourceVisible(false);
        cell.setSelection(cell.length());
        addView(cell, new FrameLayout.LayoutParams(1, 1));
        position(bounds);
        cell.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (cell == null) return;
                cellSelectionStart = Math.max(0, Math.min(cell.getSelectionStart(), cell.getSelectionEnd()));
                cellSelectionEnd = Math.max(cellSelectionStart, Math.max(cell.getSelectionStart(), cell.getSelectionEnd()));
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateSource(s.toString()); }
        });
        BbCodeEditText input = cell;
        // LayoutParams alone do not move a newly added view away from (0, 0).
        // Focus/reveal only once it occupies the tapped cell in the document.
        input.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                input.removeOnLayoutChangeListener(this);
                if (cell != input) return;
                input.requestFocus();
                input.post(() -> {
                    if (cell != input || !input.hasFocus()) return;
                    revealActiveInput();
                    ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                });
            }
        });
    }
    private void updateSource(String value) {
        if (cell == null || syncing || expectedSource.equals(value)) return;
        Editable source = body.getText();
        int start = source.getSpanStart(cellRange), end = source.getSpanEnd(cellRange);
        if (start < 0 || end < start || !source.subSequence(start, end).toString().equals(expectedSource)) {
            finishCellEditing(); return;
        }
        syncing = true;
        body.beginBatchEdit();
        try {
            body.setSelection(start + Math.min(expectedSource.length(), cellSelectionStart),
                    start + Math.min(expectedSource.length(), cellSelectionEnd));
            source.replace(start, end, value);
            source.setSpan(cellRange, start, start + value.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            expectedSource = value;
            int selectionStart = Math.max(0, Math.min(value.length(), Math.min(cell.getSelectionStart(), cell.getSelectionEnd())));
            int selectionEnd = Math.max(selectionStart, Math.min(value.length(), Math.max(cell.getSelectionStart(), cell.getSelectionEnd())));
            body.setSelection(start + selectionStart, start + selectionEnd);
        } finally { body.endBatchEdit(); syncing = false; }
    }
    @Override public void layoutChanged() {
        if (cell == null || positioning) return;
        int start = body.getText().getSpanStart(cellRange);
        if (start < 0) return;
        if (blockMode) {
            RectF bounds = body.blockBounds(start, body.getText().getSpanEnd(cellRange));
            if (bounds != null) position(bounds);
            return;
        }
        BbCodeEditText.TableHit hit = body.tableCellAtSource(start);
        if (hit != null) position(hit.bounds);
    }
    private void position(RectF bounds) {
        if (cell == null) return;
        int left = body.getLeft() + Math.round(bounds.left);
        int top = body.getTop() + Math.round(bounds.top);
        int width = Math.max(1, Math.round(bounds.width()));
        int height = Math.max(1, Math.round(bounds.height()));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cell.getLayoutParams();
        if (params.leftMargin == left && params.topMargin == top && params.width == width && params.height == height) return;
        positioning = true;
        params.leftMargin = left; params.topMargin = top; params.width = width; params.height = height;
        cell.setLayoutParams(params);
        positioning = false;
    }
    public void finishCellEditing() {
        if (cell == null) return;
        BbCodeEditText previous = cell; cell = null;
        if (body.getText() != null) body.getText().removeSpan(cellRange);
        removeView(previous); expectedSource = null;
    }
    private void revealActiveInput() {
        if (cell == null || !cell.isLaidOut() || !cell.hasFocus() || cell.getSelectionEnd() < 0) return;
        // Let the document's ScrollView reveal the caret without scrolling the body
        // independently of the table/block editor positioned over it.
        cell.bringPointIntoView(cell.getSelectionEnd());
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::revealActiveInput);
    }
    @Override public void sourceModeChanged() { finishCellEditing(); }
    @Override public void textTouched() { finishCellEditing(); }
    @Override protected void onDetachedFromWindow() { finishCellEditing(); super.onDetachedFromWindow(); }
}
