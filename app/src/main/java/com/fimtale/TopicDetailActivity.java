package com.fimtale;

import com.fimtale.utils.MdiIcons;
import com.fimtale.utils.TagChipLayout;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.os.Bundle;
import android.text.TextUtils;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.fimtale.ui.BottomSheetMenu;
import com.fimtale.ui.ShimmerSkeletonView;
import com.fimtale.ui.WorkActions;
import com.fimtale.ui.WorkCommentsSection;
import com.fimtale.ui.FtemojiPicker;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.ObjectAnimator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fimtale.adapter.ChapterAdapter;
import com.fimtale.model.AuthorInfo;
import com.fimtale.model.ChapterMenuItem;
import com.fimtale.model.TopicDetailResponse;
import com.fimtale.model.TopicInfo;
import com.fimtale.model.TopicTags;
import com.fimtale.network.ApiErrors;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.target.Target;

import java.security.MessageDigest;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.internal.LinkedTreeMap;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View.MeasureSpec;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import io.noties.markwon.Markwon;
import com.fimtale.utils.BbCodeRendering;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.image.AsyncDrawable;
import io.noties.markwon.image.glide.GlideImagesPlugin;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TopicDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TOPIC_ID = "topic_id";

    private AppBarLayout appBarLayout;
    private CollapsingToolbarLayout collapsingToolbarLayout;
    private MaterialToolbar toolbar;
    private MaterialCardView toolbarContainer;

    private MaterialCardView imageContainer;
    private ImageView coverImageView;
    private View authorDivider;

    private TextView contentTextView, authorNameTextView;
    private TextView wordCountTextView, viewCountTextView, commentCountTextView, favoriteCountTextView;
    private TextView tagType, tagSource, tagLength, tagRate;
    private ShapeableImageView authorAvatarImageView;
    private LinearLayout authorLayout;
    private ChipGroup tagChipGroup;
    private ShimmerSkeletonView loadingSkeleton;
    private boolean detailLoading;
    private TextView loadError;
    private com.fimtale.ui.PageErrorView pageError;
    private Call<TopicDetailResponse> detailCall;
    private NestedScrollView scrollView;
    private Button startReadingButton;
    private View readingActionsBar;
    private View commentComposerBar;
    private TextInputEditText commentComposerInput;
    private MaterialButton commentComposerSend;
    private View commentsRoot;
    private WorkActions workActions;
    private WorkCommentsSection commentsSection;
    private BottomSheetDialog chaptersSheet;
    private BottomSheetMenu editorActionsSheet;
    private Call<Void> commentCall;
    private boolean commentSending;
    private boolean commentMode;

    private Markwon markwon;
    private int currentTopicId;
    private AuthorInfo currentAuthor;
    private TopicDetailResponse editableWork;
    private final com.fimtale.editor.AuthoringAccess editorAccess = new com.fimtale.editor.AuthoringAccess(this);
    private long editorVersion;
    
    private String currentTopicTitle;
    private String currentTopicIntro;
    private String currentTopicCoverUrl;
    private TopicTags currentTopicTags;
    private int currentWordCount;
    private int currentViewCount;
    private int currentCommentCount;
    private int currentFavoriteCount;
    
    private boolean isToolbarElevated = false;
    private ObjectAnimator elevationAnimator;
    private int firstChapterId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_topic_detail);

        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        getWindow().setStatusBarColor(typedValue.data);

        setupViews();

        int cornerRadius = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
        int verticalPadding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());

        markwon = Markwon.builder(this)
                .usePlugin(BbCodeRendering.htmlPlugin(this))
                .usePlugin(TablePlugin.create(this))
                .usePlugin(GlideImagesPlugin.create(new GlideImagesPlugin.GlideStore() {
                    @NonNull
                    @Override
                    public RequestBuilder<Drawable> load(@NonNull AsyncDrawable drawable) {
                        RequestBuilder<Drawable> builder;
                        if (isDestroyed() || isFinishing()) {
                            builder = Glide.with(getApplicationContext()).load(drawable.getDestination());
                        } else {
                            builder = Glide.with(TopicDetailActivity.this).load(drawable.getDestination());
                        }
                        return drawable.getDestination().contains("/img/ftemoji/") ? builder
                                : builder.transform(new RoundedCorners(cornerRadius), new VerticalPaddingTransformation(verticalPadding));
                    }

                    @Override
                    public void cancel(@NonNull Target<?> target) {
                        if (!isDestroyed() && !isFinishing()) {
                            Glide.with(TopicDetailActivity.this).clear(target);
                        }
                    }
                }))
                .build();

        currentTopicId = getIntent().getIntExtra(EXTRA_TOPIC_ID, -1);
        commentsSection = new WorkCommentsSection(this, currentTopicId, this::updateCommentCount);
        workActions = new WorkActions(this, currentTopicId,
                () -> startActivity(new Intent(this, LoginActivity.class)),
                data -> updateInteractionCounts(data.getTopicInfo()));
        editorVersion = com.fimtale.editor.EditorChanges.version(currentTopicId);
        if (currentTopicId != -1) {
            fetchTopicDetail(currentTopicId);
        } else {
            finish();
        }

        setupClickListeners();
        com.fimtale.ui.PullToRefresh.attach(scrollView, () -> {
            fetchTopicDetail(currentTopicId);
            commentsSection.refresh();
        }, () -> !detailLoading && commentCall == null);
    }

    private void setupViews() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeAsUpIndicator(MdiIcons.drawable(this, "arrow-left"));
        toolbar.setNavigationOnClickListener(v -> finish());

        appBarLayout = findViewById(R.id.app_bar);
        collapsingToolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarContainer = findViewById(R.id.toolbarContainer);

        imageContainer = findViewById(R.id.imageContainer);
        coverImageView = findViewById(R.id.detailCoverImageView);

        contentTextView = findViewById(R.id.detailContentTextView);

        authorLayout = findViewById(R.id.authorLayout);
        authorDivider = findViewById(R.id.authorDivider);
        authorAvatarImageView = findViewById(R.id.authorAvatarImageView);
        authorNameTextView = findViewById(R.id.authorNameTextView);
        
        wordCountTextView = findViewById(R.id.wordCountTextView);
        viewCountTextView = findViewById(R.id.viewCountTextView);
        commentCountTextView = findViewById(R.id.commentCountTextView);
        favoriteCountTextView = findViewById(R.id.favoriteCountTextView);

        tagType = findViewById(R.id.tagType);
        tagSource = findViewById(R.id.tagSource);
        tagLength = findViewById(R.id.tagLength);
        tagRate = findViewById(R.id.tagRate);

        tagChipGroup = findViewById(R.id.tagChipGroup);

        loadingSkeleton = findViewById(R.id.detailLoadingSkeleton);
        loadingSkeleton.setSkeletonLayout(ShimmerSkeletonView.Layout.TOPIC_DETAIL);
        loadError = findViewById(R.id.detailLoadError);
        pageError = com.fimtale.ui.PageErrorView.wrap(findViewById(R.id.scrollView));
        loadError.setOnClickListener(v -> fetchTopicDetail(currentTopicId));
        scrollView = findViewById(R.id.scrollView);
        startReadingButton = findViewById(R.id.startReadingButton);
        readingActionsBar = findViewById(R.id.readingActionsBar);
        commentComposerBar = findViewById(R.id.commentComposerBar);
        commentComposerInput = findViewById(R.id.commentComposerInput);
        commentComposerSend = findViewById(R.id.commentComposerSend);
        commentsRoot = findViewById(R.id.workCommentsSection);
        scrollView.setNestedScrollingEnabled(true);
        scrollView.setSmoothScrollingEnabled(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        float targetElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics());
        scrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (commentsSection != null && editableWork != null) commentsSection.loadIfVisible();
            updateBottomActionMode();
            boolean shouldElevate = scrollY > 0;
            
            if (shouldElevate != isToolbarElevated) {
                isToolbarElevated = shouldElevate;
                
                if (elevationAnimator != null && elevationAnimator.isRunning()) {
                    elevationAnimator.cancel();
                }
                
                float start = toolbarContainer.getCardElevation();
                float end = shouldElevate ? targetElevation : 0;
                
                elevationAnimator = ObjectAnimator.ofFloat(toolbarContainer, "cardElevation", start, end);
                elevationAnimator.setDuration(200);
                elevationAnimator.start();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MdiIcons.inflateMenu(this, getMenuInflater(), R.menu.menu_topic_detail, menu);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_edit_work).setVisible(editorAccess.canEdit(currentAuthor));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override protected void onResume() {
        super.onResume();
        if (workActions != null) workActions.onResume();
        editorAccess.refresh(this::invalidateOptionsMenu);
        long version = com.fimtale.editor.EditorChanges.version(currentTopicId);
        boolean detailRefreshed = version != editorVersion;
        if (detailRefreshed) { editorVersion = version; fetchTopicDetail(currentTopicId); }
    }

    @Override protected void onDestroy() {
        if (workActions != null) workActions.close();
        if (commentsSection != null) commentsSection.close();
        if (chaptersSheet != null) chaptersSheet.dismiss();
        if (editorActionsSheet != null) editorActionsSheet.dismiss();
        if (detailCall != null) { detailCall.cancel(); detailCall = null; }
        if (commentCall != null) { commentCall.cancel(); commentCall = null; }
        if (elevationAnimator != null) elevationAnimator.cancel();
        editorAccess.close();
        super.onDestroy();
    }

    private void showEditorActions() {
        if (editableWork == null || !editorAccess.canEdit(currentAuthor)) return;
        if (editorActionsSheet != null && editorActionsSheet.isShowing()) return;
        editorActionsSheet = new BottomSheetMenu(this, R.menu.topic_edit_menu, item -> {
            if (editableWork == null || !editorAccess.canEdit(currentAuthor)) return true;
            if (item.getItemId() == R.id.action_edit_work_info) startActivity(EditorActivity.workIntent(this, currentTopicId));
            else if (item.getItemId() == R.id.action_publish_chapter) startActivity(EditorActivity.chapterIntent(this, currentTopicId, 0));
            else if (item.getItemId() == R.id.action_edit_chapter) showChapterEditorPicker();
            else return false;
            return true;
        });
        ViewCompat.setAccessibilityPaneTitle(editorActionsSheet.findViewById(R.id.bottomMenuItems), getString(R.string.editor_edit_work_and_chapters));
        editorActionsSheet.setOnDismissListener(dialog -> editorActionsSheet = null);
        editorActionsSheet.show();
    }

    private void showChapterEditorPicker() {
        List<ChapterMenuItem> chapters = editableWork.getMenu();
        if (chapters.isEmpty()) { Toast.makeText(this, getString(R.string.editor_no_chapters_hint), Toast.LENGTH_SHORT).show(); return; }
        String[] titles = new String[chapters.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = chapters.get(i).getTitle();
        new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.editor_choose_chapter))
                .setItems(titles, (dialog, index) -> startActivity(EditorActivity.chapterIntent(this, currentTopicId, chapters.get(index).getId())))
                .setNegativeButton(getString(R.string.common_cancel), null).show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_edit_work) { showEditorActions(); return true; }
        if (item.getItemId() == R.id.action_more) {
            showMoreMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showMoreMenu() {
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_topic_more, null);
        
        View parent = (View) view.getParent();
        if (parent != null) {
            parent.setBackgroundColor(Color.TRANSPARENT);
        }
        
        TextView btnCopyLink = view.findViewById(R.id.btnCopyLink);
        btnCopyLink.setOnClickListener(v -> {
            String link = com.fimtale.network.SiteUrls.work(currentTopicId);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.work_link_clipboard_label), link);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, getString(R.string.common_link_copied), Toast.LENGTH_SHORT).show();
            }
            bottomSheetDialog.dismiss();
        });

        TextView btnSaveShareImage = view.findViewById(R.id.btnSaveShareImage);
        btnSaveShareImage.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            generateAndSaveShareImage();
        });

        TextView report = view.findViewById(R.id.btnReportWork);
        report.setEnabled(currentTopicId > 0);
        report.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            com.fimtale.report.ReportDialog.show(this, com.fimtale.report.ReportRequest.WORK, currentTopicId,
                    getString(R.string.report_work_target, TextUtils.isEmpty(currentTopicTitle) ? String.valueOf(currentTopicId) : currentTopicTitle));
        });
        
        bottomSheetDialog.setContentView(view);
        
        View bottomSheet = (View) view.getParent();
        if (bottomSheet != null) {
            bottomSheet.setBackgroundColor(Color.TRANSPARENT);
        }
        
        bottomSheetDialog.show();
    }

    private void generateAndSaveShareImage() {
        View shareView = LayoutInflater.from(this).inflate(R.layout.layout_share_image, null);
        
        ImageView coverImage = shareView.findViewById(R.id.shareCoverImage);
        TextView titleText = shareView.findViewById(R.id.shareTitleText);
        com.fimtale.ui.FadingTextView introText = shareView.findViewById(R.id.shareIntroText);
        ChipGroup tagChipGroup = shareView.findViewById(R.id.shareTagChipGroup);
        TextView wordCountText = shareView.findViewById(R.id.shareWordCount);
        TextView viewCountText = shareView.findViewById(R.id.shareViewCount);
        TextView commentCountText = shareView.findViewById(R.id.shareCommentCount);
        TextView favoriteCountText = shareView.findViewById(R.id.shareFavoriteCount);
        ImageView qrCodeImage = shareView.findViewById(R.id.shareQrCodeImage);
        ImageView logoImage = shareView.findViewById(R.id.shareLogoImage);

        try {
            java.io.InputStream is = getAssets().open("img/logo-full.png");
            android.graphics.drawable.Drawable d = android.graphics.drawable.Drawable.createFromStream(is, null);
            logoImage.setImageDrawable(d);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }

        TextView tagType = shareView.findViewById(R.id.shareTagType);
        TextView tagSource = shareView.findViewById(R.id.shareTagSource);
        TextView tagLength = shareView.findViewById(R.id.shareTagLength);
        TextView tagRate = shareView.findViewById(R.id.shareTagRate);

        if (currentTopicTags != null) {
            if (!TextUtils.isEmpty(currentTopicTags.getType())) {
                tagType.setVisibility(View.VISIBLE);
                tagType.setText(currentTopicTags.getType());
            }
            if (!TextUtils.isEmpty(currentTopicTags.getSource())) {
                tagSource.setVisibility(View.VISIBLE);
                tagSource.setText(currentTopicTags.getSource());
            }
            if (!TextUtils.isEmpty(currentTopicTags.getLength())) {
                tagLength.setVisibility(View.VISIBLE);
                tagLength.setText(currentTopicTags.getLength());
            }
            if (!TextUtils.isEmpty(currentTopicTags.getRating())) {
                tagRate.setVisibility(View.VISIBLE);
                String rating = currentTopicTags.getRating();
                tagRate.setText(rating);

                int backgroundColor = 0x80000000;
                if (rating.equalsIgnoreCase("Everyone") || rating.equalsIgnoreCase("E")) {
                    backgroundColor = 0xFF4CAF50;
                } else if (rating.equalsIgnoreCase("Teen") || rating.equalsIgnoreCase("T")) {
                    backgroundColor = 0xFFFFC107;
                } else if (rating.equalsIgnoreCase("Restricted") || rating.equalsIgnoreCase("Mature") || rating.equalsIgnoreCase("M")) {
                    backgroundColor = 0xFFF44336;
                }

                Drawable background = tagRate.getBackground();
                if (background instanceof android.graphics.drawable.GradientDrawable) {
                    ((android.graphics.drawable.GradientDrawable) background.mutate()).setColor(backgroundColor);
                }
            }
            
            if (!TextUtils.isEmpty(currentTopicTags.getStatus())) {
                addShareTagChip(tagChipGroup, currentTopicTags.getStatus(), true);
            }
            if (currentTopicTags.getOtherTags() != null) {
                for (String tag : currentTopicTags.getOtherTags()) {
                    addShareTagChip(tagChipGroup, tag, false);
                }
            }
            TagChipLayout.alignHeights(tagChipGroup);
        }

        titleText.setText(currentTopicTitle != null ? currentTopicTitle : "");
        
        String plainIntro = "";
        if (currentTopicIntro != null) {
            plainIntro = Html.fromHtml(currentTopicIntro, Html.FROM_HTML_MODE_LEGACY).toString().trim();
        }
        introText.setText(plainIntro);
        
        wordCountText.setText(String.valueOf(currentWordCount));
        viewCountText.setText(String.valueOf(currentViewCount));
        commentCountText.setText(String.valueOf(currentCommentCount));
        favoriteCountText.setText(String.valueOf(currentFavoriteCount));

        String link = com.fimtale.network.SiteUrls.work(currentTopicId);
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(link, BarcodeFormat.QR_CODE, 400, 400);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap qrBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    qrBitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            qrCodeImage.setImageBitmap(qrBitmap);
        } catch (WriterException e) {
            e.printStackTrace();
        }

        if (currentTopicCoverUrl != null) {
            int cornerRadius = 32;
            Glide.with(this)
                .asBitmap()
                .load(currentTopicCoverUrl)
                .override(984, 600)
                .transform(new CenterCrop(), new RoundedCorners(cornerRadius))
                .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                        coverImage.setImageBitmap(resource);
                        renderAndSaveView(shareView);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                    
                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        renderAndSaveView(shareView);
                    }
                });
        } else {
            renderAndSaveView(shareView);
        }
    }

    private void renderAndSaveView(View view) {
        int widthSpec = MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY);
        int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        view.draw(canvas);

        showSharePreviewDialog(bitmap);
    }

    private void showSharePreviewDialog(Bitmap bitmap) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_share_preview, null);
        ImageView previewImageView = dialogView.findViewById(R.id.previewImageView);
        previewImageView.setImageBitmap(bitmap);

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.work_share_preview))
                .setView(dialogView)
                .setNegativeButton(getString(R.string.common_cancel), (dialog, which) -> dialog.dismiss())
                .setPositiveButton(getString(R.string.common_save), (dialog, which) -> {
                    saveBitmapToGallery(bitmap);
                    dialog.dismiss();
                })
                .show();
    }

    private void saveBitmapToGallery(Bitmap bitmap) {
        String fileName = "FimTale_Share_" + System.currentTimeMillis() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FimTale");

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.work_share_saved), Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.common_save_failed), Toast.LENGTH_SHORT).show());
            }
        } else {
            runOnUiThread(() -> Toast.makeText(this, getString(R.string.common_save_failed), Toast.LENGTH_SHORT).show());
        }
    }

    private void fetchTopicDetail(int topicId) {
        pageError.hide();
        if (detailCall != null) detailCall.cancel();
        detailLoading = true;
        boolean pulling = com.fimtale.ui.PullToRefresh.isRefreshing(scrollView);
        loadingSkeleton.setVisibility(pulling ? View.GONE : View.VISIBLE);
        loadError.setVisibility(View.GONE);
        scrollView.setVisibility(pulling ? View.VISIBLE : View.INVISIBLE);
        if (editableWork == null && getSupportActionBar() != null) getSupportActionBar().setTitle(getString(R.string.work_detail_title));
        readingActionsBar.setVisibility(View.INVISIBLE);
        commentMode = false;
        commentComposerBar.setVisibility(View.GONE);
        commentComposerBar.setAlpha(1f);

        String token = UserPreferences.getToken(this);
        detailCall = RetrofitClient.getInstance().getWorkViewer(token, topicId);
        detailCall.enqueue(new Callback<TopicDetailResponse>() {
            @Override
            public void onResponse(Call<TopicDetailResponse> call, Response<TopicDetailResponse> response) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != detailCall) return;
                if (!token.equals(UserPreferences.getToken(TopicDetailActivity.this))) { fetchTopicDetail(topicId); return; }
                if (response.isSuccessful() && response.body() != null && response.body().getTopicInfo() != null) {
                    TopicDetailResponse data = response.body();
                    updateUI(data);
                    workActions.bind(data, token);
                    detailLoading = false;
                    com.fimtale.ui.PullToRefresh.finish(scrollView);
                    loadingSkeleton.setVisibility(View.GONE);
                    scrollView.setVisibility(View.VISIBLE);
                    scrollView.post(() -> { if (!isFinishing() && !isDestroyed()) commentsSection.loadIfVisible(); });
                } else {
                    showDetailLoadError(com.fimtale.network.ApiErrors.message(response));
                }
            }

            @Override
            public void onFailure(Call<TopicDetailResponse> call, Throwable t) {
                if (isFinishing() || isDestroyed() || call.isCanceled() || call != detailCall) return;
                showDetailLoadError(getString(R.string.common_load_failed_retry));
            }
        });
    }

    private void showDetailLoadError(String message) {
        detailLoading = false;
        com.fimtale.ui.PullToRefresh.finish(scrollView);
        loadingSkeleton.setVisibility(View.GONE);
        loadError.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
        if (editableWork != null) readingActionsBar.setVisibility(View.VISIBLE);
        pageError.show(message, () -> fetchTopicDetail(currentTopicId), editableWork != null);
    }

    private void updateUI(TopicDetailResponse data) {
        editableWork = data;
        TopicInfo topic = data.getTopicInfo();
        AuthorInfo author = data.getAuthorInfo();
        this.currentAuthor = author;
        invalidateOptionsMenu();

        currentTopicTitle = topic.getTitle();
        currentWordCount = topic.getWordCount();
        currentViewCount = topic.getViewCount();
        currentCommentCount = topic.getCommentCount();
        currentFavoriteCount = topic.getFavoriteCount();
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(topic.getTitle());
        updateInteractionCounts(topic);

        boolean isIntroPage = true;
        String finalCoverUrl = null;
        if (finalCoverUrl == null && !TextUtils.isEmpty(topic.getBackground())) {
            finalCoverUrl = topic.getBackground();
        }
        currentTopicCoverUrl = finalCoverUrl;
        com.fimtale.ui.ImagePreview.bind(coverImageView, finalCoverUrl);

        if (isIntroPage || finalCoverUrl != null) {
            imageContainer.setVisibility(View.VISIBLE);
            if (finalCoverUrl != null) {
                Glide.with(this)
                        .load(finalCoverUrl)
                        .placeholder(MdiIcons.coverPlaceholder(this))
                        .error(MdiIcons.coverPlaceholder(this))
                        .into(coverImageView);
            } else {
                coverImageView.setImageDrawable(MdiIcons.coverPlaceholder(this));
            }
        } else {
            imageContainer.setVisibility(View.GONE);
        }

        if (isIntroPage && author != null) {
            authorLayout.setVisibility(View.VISIBLE);
            authorDivider.setVisibility(View.VISIBLE);
            findViewById(R.id.statsLayout).setVisibility(View.VISIBLE);

            authorNameTextView.setText(author.getUserName());
            
            String authorAvatarUrl = author.getAvatar();
            authorAvatarImageView.setContentDescription(getString(R.string.profile_named_description, author.getUserName()));
            authorAvatarImageView.setOnClickListener(v -> authorLayout.performClick());
            Glide.with(this)
                    .load(authorAvatarUrl)
                    .placeholder(MdiIcons.drawable(this, "account"))
                    .error(MdiIcons.drawable(this, "account"))
                    .into(authorAvatarImageView);

            wordCountTextView.setText(String.valueOf(topic.getWordCount()));
            viewCountTextView.setText(String.valueOf(topic.getViewCount()));
            commentCountTextView.setText(String.valueOf(topic.getCommentCount()));
            favoriteCountTextView.setText(String.valueOf(topic.getFavoriteCount()));

            tagChipGroup.removeAllViews();
            tagChipGroup.setVisibility(View.VISIBLE);
            
            TopicTags tags = topic.getTags();
            currentTopicTags = tags;
            if (tags != null) {
                if (!TextUtils.isEmpty(tags.getStatus())) {
                    addTagChip(tags.getStatus(), true);
                }
                
                if (tags.getOtherTags() != null) {
                    for (String tag : tags.getOtherTags()) {
                        addTagChip(tag, false);
                    }
                }
                TagChipLayout.alignHeights(tagChipGroup);

                updateCoverTags(tags);
            }
        } else {
            authorLayout.setVisibility(View.GONE);
            authorDivider.setVisibility(View.GONE);
            findViewById(R.id.statsLayout).setVisibility(View.GONE);
            tagChipGroup.setVisibility(View.GONE);
        }

        String intro = topic.getIntro();
        currentTopicIntro = intro == null ? "" : intro;
        String body = topic.getContent() == null ? "" : topic.getContent();
        // Never truncate raw BBCode to manufacture a summary: it can split a tag or reveal a spoiler.
        BbCodeRendering.setText(markwon, contentTextView,
                TextUtils.isEmpty(intro) ? body : intro + "\n\n" + body);

        // Preface belongs to the work; every directory entry is a real chapter.
        java.util.List<TopicDetailResponse.ChapterEdge> roots = com.fimtale.model.ChapterNavigation.choices(data, 0);
        firstChapterId = roots.size() == 1 && roots.get(0).to != null ? roots.get(0).to : 0;
        findViewById(R.id.showChaptersButton).setVisibility(data.getMenu().isEmpty() ? View.GONE : View.VISIBLE);
        setCommentMode(false);
        readingActionsBar.setVisibility(View.VISIBLE);
    }

    private void updateInteractionCounts(TopicInfo topic) {
        currentFavoriteCount = topic.getFavoriteCount();
        favoriteCountTextView.setText(String.valueOf(currentFavoriteCount));
        updateCommentCount(topic.getCommentCount());
    }

    private void updateCommentCount(int count) {
        currentCommentCount = count;
        commentCountTextView.setText(String.valueOf(count));
        if (commentsSection != null) commentsSection.setCount(count);
    }

    private void showComments() {
        if (editableWork == null) return;
        commentsSection.open();
    }

    private void showChapters() {
        if (editableWork == null) return;
        List<ChapterMenuItem> chapters = editableWork.getMenu();
        if (chapters.isEmpty()) return;
        if (chaptersSheet != null && chaptersSheet.isShowing()) return;
        chaptersSheet = new BottomSheetDialog(this);
        BottomSheetDialog sheet = chaptersSheet;
        View content = getLayoutInflater().inflate(R.layout.dialog_work_list, null);
        sheet.setContentView(content);
        content.setLayoutParams(new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * .82f)));
        ((TextView) content.findViewById(R.id.workListTitle)).setText(getString(R.string.reader_catalog_count, chapters.size()));
        content.findViewById(R.id.workListSort).setVisibility(View.GONE);
        content.findViewById(R.id.workListPager).setVisibility(View.GONE);
        content.findViewById(R.id.workListSkeleton).setVisibility(View.GONE);
        TextView empty = content.findViewById(R.id.workListStatus);
        empty.setText(getString(R.string.reader_no_chapters)); empty.setVisibility(chapters.isEmpty() ? View.VISIBLE : View.GONE);
        RecyclerView list = content.findViewById(R.id.workList);
        list.setLayoutManager(new LinearLayoutManager(this)); list.setItemAnimator(null);
        list.setAdapter(new ChapterAdapter(chapters, item -> {
            sheet.dismiss();
            startActivity(new Intent(this, ReaderActivity.class)
                    .putExtra(ReaderActivity.EXTRA_WORK_ID, currentTopicId)
                    .putExtra(ReaderActivity.EXTRA_CHAPTER_ID, item.getId()));
        }));
        sheet.setOnShowListener(dialog -> {
            sheet.getBehavior().setSkipCollapsed(true);
            sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        });
        sheet.show();
    }

    private void updateCoverTags(TopicTags tags) {
        if (tags == null) return;

        if (!TextUtils.isEmpty(tags.getType())) {
            tagType.setVisibility(View.VISIBLE);
            tagType.setText(tags.getType());
        } else {
            tagType.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(tags.getSource())) {
            tagSource.setVisibility(View.VISIBLE);
            tagSource.setText(tags.getSource());
        } else {
            tagSource.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(tags.getLength())) {
            tagLength.setVisibility(View.VISIBLE);
            tagLength.setText(tags.getLength());
        } else {
            tagLength.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(tags.getRating())) {
            tagRate.setVisibility(View.VISIBLE);
            String rating = tags.getRating();
            tagRate.setText(rating);

            int backgroundColor = 0x80000000;
            if (rating != null) {
                if (rating.equalsIgnoreCase("Everyone") || rating.equalsIgnoreCase("E")) {
                    backgroundColor = 0xFF4CAF50;
                } else if (rating.equalsIgnoreCase("Teen") || rating.equalsIgnoreCase("T")) {
                    backgroundColor = 0xFFFFC107;
                } else if (rating.equalsIgnoreCase("Restricted") || rating.equalsIgnoreCase("Mature") || rating.equalsIgnoreCase("M")) {
                    backgroundColor = 0xFFF44336;
                }
            }

            Drawable background = tagRate.getBackground();
            if (background instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) background.mutate()).setColor(backgroundColor);
            }
        } else {
            tagRate.setVisibility(View.GONE);
        }
    }

    private void addShareTagChip(ChipGroup chipGroup, String text, boolean isStatus) {
        Chip chip = new Chip(this);
        chip.setText(text);
        chip.setCheckable(false);
        chip.setClickable(false);
        chip.setChipStrokeWidth(0);
        chip.setEnsureMinTouchTargetSize(false);
        
        chip.setChipStartPadding(12f);
        chip.setChipEndPadding(12f);
        chip.setChipMinHeight(24f);
        
        if (isStatus) {
            chip.setChipBackgroundColor(ColorStateList.valueOf(Color.parseColor("#FFEBEE")));
            chip.setTextColor(Color.parseColor("#D32F2F"));
        } else {
            chip.setChipBackgroundColor(ColorStateList.valueOf(Color.parseColor("#FFF5F5")));
            chip.setTextColor(Color.parseColor("#D32F2F"));
        }
        
        chipGroup.addView(chip);
    }

    private void addTagChip(String text, boolean isStatus) {
        Chip chip = new Chip(this);
        chip.setText(text);
        chip.setCheckable(false);
        chip.setClickable(false);
        chip.setChipStrokeWidth(0);
        chip.setEnsureMinTouchTargetSize(false);
        
        chip.setChipStartPadding(12f);
        chip.setChipEndPadding(12f);
        chip.setChipMinHeight(24f);
        
        if (isStatus) {
            TypedValue typedValue = new TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true);
            chip.setChipBackgroundColor(ColorStateList.valueOf(typedValue.data));
            
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, typedValue, true);
            chip.setTextColor(typedValue.data);
        } else {
            TypedValue typedValue = new TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, typedValue, true);
            chip.setChipBackgroundColor(ColorStateList.valueOf(typedValue.data));
            
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
            chip.setTextColor(typedValue.data);
        }

        if (!isStatus) chip.setOnClickListener(v -> {
            Intent intent = new Intent(this, TagArticlesActivity.class);
            intent.putExtra(TagArticlesActivity.EXTRA_TAG_NAME, text);
            startActivity(intent);
        });
        
        tagChipGroup.addView(chip);
    }

    private static class VerticalPaddingTransformation extends BitmapTransformation {
        private final int padding;

        public VerticalPaddingTransformation(int padding) {
            this.padding = padding;
        }

        @Override
        protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
            int width = toTransform.getWidth();
            int height = toTransform.getHeight();
            Bitmap result = pool.get(width, height + 2 * padding, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.TRANSPARENT);
            canvas.drawBitmap(toTransform, 0, padding, null);
            return result;
        }

        @Override
        public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
            messageDigest.update(("VerticalPaddingTransformation" + padding).getBytes(CHARSET));
        }
    }

    private void setupClickListeners() {
        findViewById(R.id.showChaptersButton).setOnClickListener(v -> showChapters());
        commentCountTextView.setOnClickListener(v -> showComments());
        startReadingButton.setOnClickListener(v -> openReaderFromDetail());

        commentComposerSend.setOnClickListener(v -> submitComment());
        findViewById(R.id.commentComposerEmoji).setOnClickListener(v -> FtemojiPicker.show(this,
                name -> insertCommentText(":ftemoji_" + name + ":")));
        commentComposerInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitComment();
                return true;
            }
            return false;
        });

        authorLayout.setOnClickListener(v -> {
            if (currentAuthor != null && !TextUtils.isEmpty(currentAuthor.getUserName())) {
                Intent intent = new Intent(this, UserDetailActivity.class);
                intent.putExtra(UserDetailActivity.EXTRA_USERNAME, currentAuthor.getUserName());
                startActivity(intent);
            }
        });
    }

    private void openReaderFromDetail() {
        if (firstChapterId == -1) return;
        Intent intent = new Intent(this, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_WORK_ID, currentTopicId)
                .putExtra(ReaderActivity.EXTRA_CHAPTER_ID, firstChapterId);
        startActivity(intent);
    }

    private void updateBottomActionMode() {
        if (editableWork == null || scrollView == null || commentsRoot == null
                || scrollView.getVisibility() != View.VISIBLE) return;
        android.graphics.Rect viewport = new android.graphics.Rect();
        android.graphics.Rect comments = new android.graphics.Rect();
        if (!scrollView.getGlobalVisibleRect(viewport)) return;
        int threshold = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                getResources().getDisplayMetrics());
        setCommentMode(commentsRoot.getGlobalVisibleRect(comments)
                && comments.top <= viewport.bottom - threshold);
    }

    private void setCommentMode(boolean showComposer) {
        if (commentMode == showComposer && (showComposer
                ? commentComposerBar.getVisibility() == View.VISIBLE
                : readingActionsBar.getVisibility() == View.VISIBLE)) return;
        commentMode = showComposer;
        if (showComposer) {
            readingActionsBar.animate().cancel();
            readingActionsBar.setVisibility(View.GONE);
            commentComposerBar.setVisibility(View.VISIBLE);
            commentComposerBar.setAlpha(0f);
            commentComposerBar.animate().alpha(1f).setDuration(160).start();
        } else {
            commentComposerBar.animate().cancel();
            commentComposerBar.setVisibility(View.GONE);
            readingActionsBar.setVisibility(View.VISIBLE);
            readingActionsBar.setAlpha(0f);
            readingActionsBar.animate().alpha(1f).setDuration(160).start();
        }
    }

    private void submitComment() {
        if (commentSending) return;
        String content = commentComposerInput.getText() == null
                ? "" : commentComposerInput.getText().toString().trim();
        if (content.isEmpty()) {
            MdiIcons.setError(commentComposerInput, getString(R.string.comments_content_required));
            return;
        }
        if (!UserPreferences.isLoggedIn(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }

        String token = UserPreferences.getToken(this);
        commentSending = true;
        commentComposerInput.setEnabled(false);
        commentComposerSend.setEnabled(false);
        commentCall = RetrofitClient.getInstance().createUpdateComment(token,
                new com.fimtale.model.WorkCommentRequest(currentTopicId, content));
        commentCall.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (call != commentCall || call.isCanceled() || isFinishing() || isDestroyed()) return;
                commentCall = null;
                commentSending = false;
                commentComposerInput.setEnabled(true);
                commentComposerSend.setEnabled(true);
                if (response.isSuccessful()) {
                    commentComposerInput.setText("");
                    commentComposerInput.clearFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(commentComposerInput.getWindowToken(), 0);
                    commentsSection.refreshLatest();
                    Toast.makeText(TopicDetailActivity.this, getString(R.string.comments_sent), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(TopicDetailActivity.this, ApiErrors.message(response), Toast.LENGTH_LONG).show();
                    if (response.code() == 401) startActivity(new Intent(TopicDetailActivity.this, LoginActivity.class));
                }
            }

            @Override public void onFailure(Call<Void> call, Throwable error) {
                if (call != commentCall || call.isCanceled() || isFinishing() || isDestroyed()) return;
                commentCall = null;
                commentSending = false;
                commentComposerInput.setEnabled(true);
                commentComposerSend.setEnabled(true);
                Toast.makeText(TopicDetailActivity.this, getString(R.string.comments_send_failed), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void insertCommentText(String value) {
        int start = Math.max(0, commentComposerInput.getSelectionStart());
        int end = Math.max(start, commentComposerInput.getSelectionEnd());
        commentComposerInput.getText().replace(start, end, value);
        commentComposerInput.requestFocus();
        commentComposerInput.setSelection(Math.min(commentComposerInput.length(), start + value.length()));
    }

}
