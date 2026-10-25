package com.fimtale;

import com.fimtale.utils.MdiIcons;
import com.fimtale.utils.BbCodeRendering;
import com.fimtale.utils.BbCodeText;
import com.fimtale.utils.ReaderPagination;
import android.widget.ScrollView;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.text.TextUtils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.ColorUtils;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Typeface;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import android.widget.ImageView;

import com.fimtale.adapter.CommentAdapter;
import com.fimtale.adapter.TopicAdapter;
import com.fimtale.db.CacheManager;
import com.fimtale.model.ChapterMenuItem;
import com.fimtale.model.Comment;
import com.fimtale.model.Topic;
import com.fimtale.model.TopicDetailResponse;
import com.fimtale.model.TopicInfo;
import com.fimtale.model.TopicListResponse;
import com.fimtale.model.TopicViewItem;
import com.fimtale.network.RetrofitClient;
import com.fimtale.ui.ReaderCommentsPanel;
import com.fimtale.utils.UserPreferences;
import android.widget.ProgressBar;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.image.glide.GlideImagesPlugin;
import okhttp3.ResponseBody;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.transition.MaterialSharedAxis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_WORK_ID = "work_id";
    public static final String EXTRA_CHAPTER_ID = "chapter_id";
    public static final String EXTRA_INITIAL_PROGRESS = "initial_progress";

    private ViewPager2 viewPager;
    private RecyclerView recyclerView;
    private View menuOverlay;
    private View dimLayer;
    private MaterialToolbar topToolbar;
    private com.fimtale.ui.BottomSheetMenu moreMenu;
    private LinearLayout bottomSheetContainer;
    private View bottomMenu;
    private View btnChapterList;
    private View btnSettings;
    private View guideOverlay;
    private View readerNavigationPanel;
    private TextView tvReaderMenuTitle;
    private TextView tvReaderMenuSubtitle;
    private TextView tvFontSizeValue;
    private TextView tvLineSpacingValue;
    private TextView btnReaderTheme;
    private Slider sliderReaderProgress;
    private MaterialCheckBox checkSystemBrightness;
    private TabLayout tabPageMargin;
    private boolean isScrubbingProgress;
    private boolean isAdjustingTypography;
    private int scrubbingChapterId;
    private int currentPageMargin = 24;
    private int readerTextColor;
    private int readerBackgroundColor;
    
    private View readerHeader;
    private View readerFooter;
    private TextView tvChapterTitle;
    private TextView tvChapterProgress;
    private TextView tvBatteryLevel;
    private android.widget.TextClock tcSystemTime;
    private ImageView ivBattery;
    private ImageView ivCharging;
    private ProgressBar scrollProgressBar;

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return;
            int batteryPct = (int) (level * 100 / (float) scale);
            if (tvBatteryLevel != null) {
                tvBatteryLevel.setText(getString(R.string.common_percent, batteryPct));
            }
            if (ivBattery != null) {
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;
                // Keep the battery horizontal; the charging glyph is shown beside it so the
                // lightning mark remains on the battery's right side.
                ivBattery.setImageDrawable(MdiIcons.battery(ReaderActivity.this, batteryPct, false));
                if (ivCharging != null) ivCharging.setVisibility(isCharging ? View.VISIBLE : View.GONE);
            }
        }
    };
    private boolean batteryReceiverRegistered;

    private View settingsPanel;
    private LinearLayout chapterListPanel;
    private RecyclerView rvChapterList;
    private Slider sliderFontSize;
    private Slider sliderLineSpacing;
    private Slider sliderBrightness;
    private TabLayout tabPageMode;
    private TabLayout tabThemeMode;

    private boolean isMenuVisible = false;
    private List<ReaderPage> pages = new ArrayList<>();
    private List<ReaderPage> verticalPages = new ArrayList<>();
    private List<Integer> chapterStartPageIndices = new ArrayList<>();
    private List<Integer> chapterVerticalIndices = new ArrayList<>();
    private List<Integer> pageStartOffsets = new ArrayList<>();
    private List<Integer> paragraphStartOffsets = new ArrayList<>();
    private ReaderAdapter adapter;
    private ReaderAdapter recyclerAdapter;
    private int currentTopicId;
    private int currentPostId = -1;
    private int initialTopicId;
    private int rootTopicId = -1;
    private double initialProgress = -1d;
    private double currentProgress = 0d;
    private Handler progressSaveHandler = new Handler(Looper.getMainLooper());
    private static final long PROGRESS_SAVE_INTERVAL = 30 * 1000;

    private Runnable progressSaveRunnable = new Runnable() {
        @Override
        public void run() {
            saveReadingProgress();
            progressSaveHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL);
        }
    };
    private boolean initialProgressApplied = false;
    private int lastWidth = 0;
    private int lastHeight = 0;
    private int pagedViewportWidth;
    private int pagedViewportHeight;
    private boolean pageReflowPending;
    private final Set<WindowInsetsAnimationCompat> imeAnimations = new HashSet<>();
    private boolean rebuildingReaderContent;
    private boolean adjacentChapterLoadPosted;
    private VerticalPosition pendingVerticalPosition;

    /** Chapter-relative row and offset from the padded reading area, valid across preloads. */
    private static final class VerticalPosition {
        final int chapterId;
        final int paragraph;
        final int offset;
        final double fraction;

        VerticalPosition(int chapterId, int paragraph, int offset) {
            this(chapterId, paragraph, offset, 0);
        }

        VerticalPosition(int chapterId, int paragraph, int offset, double fraction) {
            this.chapterId = chapterId;
            this.paragraph = paragraph;
            this.offset = offset;
            this.fraction = fraction;
        }
    }
    
    private float currentFontSize = 20f;
    private SharedPreferences prefs;
    private Markwon markwon;

    private String chapterTitle = "";
    private List<ChapterMenuItem> chapterList = new ArrayList<>();
    private List<ChapterMenuItem> filteredChapterList = new ArrayList<>();
    private ChapterListAdapter chapterListAdapter;
    
    private boolean isLoadingChapter = false;
    private boolean canTriggerChapterChange = false;
    private GestureDetector gestureDetector;

    private BottomSheetDialog readerCommentsSheet;
    private ReaderCommentsPanel readerCommentsSheetPanel;

    /** Chapters currently joined into one continuous reader data set. */
    private final List<LoadedChapter> loadedChapters = new ArrayList<>();
    private final Set<Integer> loadingChapterIds = new HashSet<>();

    private static class LoadedChapter {
        final int id;
        final String title;
        final List<ContentSegment> segments;

        LoadedChapter(int id, String title, List<ContentSegment> segments) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.segments = segments == null ? new ArrayList<>() : segments;
        }
    }

    private static class ContentSegment {
        int type;
        CharSequence content;
        ContentSegment(int type, CharSequence content) {
            this.type = type;
            this.content = content;
        }
    }

    private static class ReaderPage {
        static final int TYPE_TEXT = 0;
        static final int TYPE_COMMENT = 1;
        static final int TYPE_LOADING = 2;
        static final int TYPE_NEXT_CHAPTER_TRIGGER = 3;
        static final int TYPE_PREV_CHAPTER_TRIGGER = 4;
        static final int TYPE_IMAGE = 5;
        static final int TYPE_TITLE = 6;
        static final int TYPE_SCROLL_TEXT = 7;
        
        int type;
        CharSequence content;
        int chapterId;
        int titleLength;
        
        ReaderPage(int type, CharSequence content, int chapterId) {
            this(type, content, chapterId, 0);
        }

        ReaderPage(int type, CharSequence content, int chapterId, int titleLength) {
            this.type = type;
            this.content = content;
            this.chapterId = chapterId;
            this.titleLength = titleLength;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        currentTopicId = getIntent().getIntExtra(EXTRA_CHAPTER_ID, 0);
        rootTopicId = getIntent().getIntExtra(EXTRA_WORK_ID, 0);
        if (rootTopicId <= 0 || currentTopicId < 0) {
            finish();
            return;
        }
        initialTopicId = currentTopicId;
        initialProgress = getIntent().getDoubleExtra(EXTRA_INITIAL_PROGRESS, -1d);
        if (initialProgress >= 0) initialProgress = clamp01(initialProgress);

        viewPager = findViewById(R.id.viewPager);
        recyclerView = findViewById(R.id.recyclerView);
        pageError = com.fimtale.ui.PageErrorView.wrap(findViewById(R.id.readerContent));
        menuOverlay = findViewById(R.id.menuOverlay);
        dimLayer = findViewById(R.id.dimLayer);
        topToolbar = findViewById(R.id.topToolbar);
        bottomSheetContainer = findViewById(R.id.bottomSheetContainer);
        bottomMenu = findViewById(R.id.bottomMenu);
        btnChapterList = findViewById(R.id.btnChapterList);
        btnSettings = findViewById(R.id.btnSettings);
        guideOverlay = findViewById(R.id.guideOverlay);

        settingsPanel = findViewById(R.id.settingsPanel);
        chapterListPanel = findViewById(R.id.chapterListPanel);
        rvChapterList = findViewById(R.id.rvChapterList);
        
        readerHeader = findViewById(R.id.readerHeader);
        readerFooter = findViewById(R.id.readerFooter);
        tvChapterTitle = findViewById(R.id.tvChapterTitle);
        tvChapterProgress = findViewById(R.id.tvChapterProgress);
        tvBatteryLevel = findViewById(R.id.tvBatteryLevel);
        tcSystemTime = findViewById(R.id.tcSystemTime);
        ivBattery = findViewById(R.id.ivBattery);
        ivCharging = findViewById(R.id.ivCharging);
        
        scrollProgressBar = findViewById(R.id.scrollProgressBar);
        
        sliderFontSize = findViewById(R.id.sliderFontSize);
        sliderLineSpacing = findViewById(R.id.sliderLineSpacing);
        sliderBrightness = findViewById(R.id.sliderBrightness);
        tabPageMode = findViewById(R.id.tabPageMode);
        tabThemeMode = findViewById(R.id.tabThemeMode);
        tabPageMargin = findViewById(R.id.tabPageMargin);
        readerNavigationPanel = findViewById(R.id.readerNavigationPanel);
        tvReaderMenuTitle = findViewById(R.id.tvReaderMenuTitle);
        tvReaderMenuSubtitle = findViewById(R.id.tvReaderMenuSubtitle);
        tvFontSizeValue = findViewById(R.id.tvFontSizeValue);
        tvLineSpacingValue = findViewById(R.id.tvLineSpacingValue);
        btnReaderTheme = findViewById(R.id.btnReaderTheme);
        sliderReaderProgress = findViewById(R.id.sliderReaderProgress);
        checkSystemBrightness = findViewById(R.id.checkSystemBrightness);

        markwon = Markwon.builder(this)
                .usePlugin(BbCodeRendering.htmlPlugin(this))
                .usePlugin(TablePlugin.create(this))
                .usePlugin(GlideImagesPlugin.create(this))
                .build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        installWindowInsets(findViewById(R.id.readerRoot));
        ViewCompat.setWindowInsetsAnimationCallback(findViewById(R.id.readerRoot),
                new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override public void onPrepare(@NonNull WindowInsetsAnimationCompat animation) {
                        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) imeAnimations.add(animation);
                    }
                    @NonNull @Override public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                            @NonNull List<WindowInsetsAnimationCompat> running) { return insets; }
                    @Override public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                        if (imeAnimations.remove(animation)) viewPager.post(() -> checkPageViewport());
                    }
                });

        topToolbar.setNavigationOnClickListener(v -> finish());
        topToolbar.inflateMenu(R.menu.menu_reader);
        androidx.appcompat.widget.Toolbar.OnMenuItemClickListener menuActions = item -> {
            if (item.getItemId() == R.id.action_more) { moreMenu.show(); return true; }
            if (item.getItemId() == R.id.action_edit_content) {
                if (workData != null && editorAccess.canEdit(workData.getAuthorInfo())) {
                    startActivity(currentTopicId == 0 ? EditorActivity.workIntent(this, rootTopicId)
                            : EditorActivity.chapterIntent(this, rootTopicId, currentTopicId));
                }
                return true;
            }
            if (item.getItemId() == R.id.action_info) {
                if (rootTopicId != -1) {
                    Intent intent = new Intent(this, TopicDetailActivity.class);
                    intent.putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, rootTopicId);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, getString(R.string.common_fetch_failed), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        };
        topToolbar.setOnMenuItemClickListener(menuActions);
        moreMenu = new com.fimtale.ui.BottomSheetMenu(this, R.menu.reader_more_menu, menuActions::onMenuItemClick);

        dimLayer.setOnClickListener(v -> hideMenu());
        
        btnChapterList.setOnClickListener(v -> toggleChapterList());

        btnSettings.setOnClickListener(v -> toggleSettingsPanel());
        findViewById(R.id.btnCloseReaderMenu).setOnClickListener(v -> hideMenu());
        findViewById(R.id.btnLocateChapter).setOnClickListener(v -> locateCurrentChapter());
        findViewById(R.id.btnPreviousChapter).setOnClickListener(v -> {
            int previous = getPrevChapterId();
            if (previous >= 0) jumpToChapter(previous);
        });
        findViewById(R.id.btnNextChapter).setOnClickListener(v -> {
            if (nextChoices().size() > 1) {
                hideMenu();
                showBranchChoices();
            } else {
                int next = getNextChapterId();
                if (next >= 0) jumpToChapter(next);
            }
        });
        findViewById(R.id.btnReaderComments).setOnClickListener(v -> {
            if (!contentReady) return;
            hideMenu();
            showReaderCommentsBottomSheet(currentTopicId);
        });

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentFontSize = Math.max(12f, Math.min(40f, Math.round(prefs.getFloat("reader_font_size", 20f) / 2f) * 2f));
        currentPageMargin = prefs.getInt("reader_page_margin", 24);
        if (currentPageMargin != 16 && currentPageMargin != 24 && currentPageMargin != 32) currentPageMargin = 24;
        boolean isVertical = prefs.getBoolean("reader_is_vertical", false);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerAdapter = new ReaderAdapter(verticalPages, true);
        recyclerView.setAdapter(recyclerAdapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) canTriggerChapterChange = true;
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (recyclerView.getVisibility() != View.VISIBLE) return;
                if (isMenuVisible && dy != 0 && recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING) {
                    hideMenu();
                }
                updateVerticalProgress();
            }
        });
        recyclerView.getViewTreeObserver().addOnPreDrawListener(this::onVerticalPreDraw);

        adapter = new ReaderAdapter(pages, false);
        viewPager.setAdapter(adapter);

        setupReadingControls();

        tabPageMode.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    updatePageMode(false);
                    prefs.edit().putBoolean("reader_is_vertical", false).apply();
                } else {
                    updatePageMode(true);
                    prefs.edit().putBoolean("reader_is_vertical", true).apply();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        viewPager.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                viewPager.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                calculatePages();
                
                if (isVertical) {
                    updatePageMode(true);
                } else {
                    updatePageMode(false);
                }

                topToolbar.setTranslationY(-topToolbar.getBottom());
                bottomSheetContainer.setTranslationY(menuOverlay.getHeight() - bottomSheetContainer.getTop());
                
                loadWorkNavigation();
            }
        });
        viewPager.getViewTreeObserver().addOnGlobalLayoutListener(this::checkPageViewport);

        hideSystemUI();
        
        setupChapterList();
        
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (viewPager.getVisibility() != View.VISIBLE || rebuildingReaderContent) return;
                updateCurrentChapterFromPage(position);
                scheduleAdjacentChapters();
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                super.onPageScrollStateChanged(state);
                if (state == ViewPager2.SCROLL_STATE_DRAGGING && isMenuVisible) {
                    hideMenu();
                }
            }
        });

        checkAndShowGuide();
        
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, ifilter);
        batteryReceiverRegistered = true;
        
        progressSaveHandler.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL);

        initGestureDetector();
        menuOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateReaderPanelHeight());
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (guideOverlay.getVisibility() == View.VISIBLE) guideOverlay.performClick();
                else if (isMenuVisible) hideMenu();
                else finish();
            }
        });
    }

    private void setupReadingControls() {
        sliderFontSize.setValue(currentFontSize);
        tvFontSizeValue.setText(getString(R.string.reader_font_size_value, currentFontSize));
        sliderFontSize.setLabelFormatter(value -> getString(R.string.reader_font_size_value, value));
        float spacing = Math.max(1f, Math.min(3f, 1f + Math.round((UserPreferences.getLineSpacing(this) - 1f) / 0.2f) * 0.2f));
        if (spacing != UserPreferences.getLineSpacing(this)) UserPreferences.setLineSpacing(this, spacing);
        sliderLineSpacing.setValue(spacing);
        tvLineSpacingValue.setText(getString(R.string.reader_line_spacing_value, spacing));
        sliderLineSpacing.setLabelFormatter(value -> getString(R.string.reader_line_spacing_value, value));
        Slider.OnSliderTouchListener typographyTouch = new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(@NonNull Slider slider) { isAdjustingTypography = true; }
            @Override public void onStopTrackingTouch(@NonNull Slider slider) {
                isAdjustingTypography = false;
                if (slider == sliderFontSize) updateFontSize(slider.getValue());
                else rebuildReaderContent(true);
            }
        };
        sliderFontSize.addOnSliderTouchListener(typographyTouch);
        sliderLineSpacing.addOnSliderTouchListener(typographyTouch);
        sliderFontSize.addOnChangeListener((slider, value, fromUser) -> {
            tvFontSizeValue.setText(getString(R.string.reader_font_size_value, value));
            if (!fromUser) return;
            currentFontSize = value;
            saveFontSize();
            if (!isAdjustingTypography) updateFontSize(value);
        });
        sliderLineSpacing.addOnChangeListener((slider, value, fromUser) -> {
            tvLineSpacingValue.setText(getString(R.string.reader_line_spacing_value, value));
            if (!fromUser) return;
            UserPreferences.setLineSpacing(this, value);
            if (!isAdjustingTypography) rebuildReaderContent(true);
        });

        float systemBrightness = 0.5f;
        try {
            systemBrightness = android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (android.provider.Settings.SettingNotFoundException ignored) { }
        float brightness = Math.max(0.01f, Math.min(1f, prefs.getFloat("reader_brightness", systemBrightness)));
        sliderBrightness.setValue(brightness);
        sliderBrightness.setLabelFormatter(value -> getString(R.string.common_percent, Math.round(value * 100)));
        checkSystemBrightness.setChecked(prefs.getBoolean("reader_follow_system_brightness", true));
        applyReaderBrightness();
        checkSystemBrightness.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("reader_follow_system_brightness", checked).apply();
            applyReaderBrightness();
        });
        sliderBrightness.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            prefs.edit().putFloat("reader_brightness", value).apply();
            applyReaderBrightness();
        });

        TabLayout.Tab modeTab = tabPageMode.getTabAt(prefs.getBoolean("reader_is_vertical", false) ? 1 : 0);
        if (modeTab != null) modeTab.select();
        TabLayout.Tab marginTab = tabPageMargin.getTabAt((currentPageMargin - 16) / 8);
        if (marginTab != null) marginTab.select();
        tabPageMargin.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentPageMargin = 16 + tab.getPosition() * 8;
                prefs.edit().putInt("reader_page_margin", currentPageMargin).apply();
                rebuildReaderContent(true);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });

        for (int i = 0; i < tabThemeMode.getTabCount(); i++) {
            TabLayout.Tab tab = tabThemeMode.getTabAt(i);
            if (tab == null) continue;
            tab.setCustomView(R.layout.item_reader_theme);
            ((TextView) tab.getCustomView().findViewById(R.id.readerThemeName)).setText(tab.getText());
        }
        int theme = Math.max(0, Math.min(4, UserPreferences.getReaderTheme(this)));
        if (theme != UserPreferences.getReaderTheme(this)) UserPreferences.setReaderTheme(this, theme);
        TabLayout.Tab themeTab = tabThemeMode.getTabAt(theme);
        if (themeTab != null) themeTab.select();
        applyReaderTheme(theme);
        tabThemeMode.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                int selected = tab.getPosition();
                UserPreferences.setReaderTheme(ReaderActivity.this, selected);
                if (!isReaderDark(selected)) prefs.edit().putInt("reader_day_theme", selected).apply();
                applyReaderTheme(selected);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        btnReaderTheme.setOnClickListener(v -> {
            int current = UserPreferences.getReaderTheme(this);
            int target = 4;
            if (isReaderDark(current)) {
                target = Math.max(0, Math.min(3, prefs.getInt("reader_day_theme", 1)));
                if (isReaderDark(target)) target = 1;
            } else {
                prefs.edit().putInt("reader_day_theme", current).apply();
            }
            TabLayout.Tab tab = tabThemeMode.getTabAt(target);
            if (tab != null) tab.select();
        });

        sliderReaderProgress.setLabelFormatter(value -> getString(R.string.reader_percent, value / 10d));
        sliderReaderProgress.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(@NonNull Slider slider) {
                isScrubbingProgress = true;
                scrubbingChapterId = currentTopicId;
            }
            @Override public void onStopTrackingTouch(@NonNull Slider slider) {
                isScrubbingProgress = false;
                if (contentReady && !isLoadingChapter) positionReaderAtProgress(scrubbingChapterId, slider.getValue() / 1000d);
                updateReaderMenu();
            }
        });
        sliderReaderProgress.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser || !contentReady || isLoadingChapter) return;
            tvReaderMenuSubtitle.setText(getString(R.string.reader_chapter_progress_value, value / 10d));
            // Accessibility and keyboard adjustments do not send touch tracking callbacks.
            if (!isScrubbingProgress) positionReaderAtProgress(currentTopicId, value / 1000d);
        });
        updateReaderMenu();
    }

    private void applyReaderBrightness() {
        boolean followSystem = checkSystemBrightness.isChecked();
        sliderBrightness.setEnabled(!followSystem);
        android.view.WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = followSystem
                ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : sliderBrightness.getValue();
        getWindow().setAttributes(params);
    }

    private void initGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                return handleSingleTap(e);
            }
            
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });
    }

    private boolean handleSingleTap(MotionEvent e) {
        if (isMenuVisible) {
            hideMenu();
            return true;
        }

        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float x = e.getRawX();
        float y = e.getRawY();

        boolean isVertical = prefs.getBoolean("reader_is_vertical", false);

        if (isVertical) {
            if (y < height * 0.3) {
                if (recyclerView != null) {
                    recyclerView.smoothScrollBy(0, -height / 2);
                }
            } else if (y > height * 0.7) {
                if (recyclerView != null) {
                    recyclerView.smoothScrollBy(0, height / 2);
                }
            } else {
                toggleMenu();
            }
        } else {
            if (x < width * 0.3) {
                if (viewPager != null) {
                    int current = viewPager.getCurrentItem();
                    if (current > 0) {
                        viewPager.setCurrentItem(current - 1, true);
                    }
                }
            } else if (x > width * 0.7) {
                if (viewPager != null) {
                    int current = viewPager.getCurrentItem();
                    if (adapter != null && current < adapter.getItemCount() - 1) {
                        viewPager.setCurrentItem(current + 1, true);
                    }
                }
            } else {
                toggleMenu();
            }
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (isMenuVisible) {
            return super.dispatchKeyEvent(event);
        }

        int action = event.getAction();
        int keyCode = event.getKeyCode();

        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_VOLUME_UP:
                if (action == android.view.KeyEvent.ACTION_DOWN) {
                    boolean isVertical = prefs.getBoolean("reader_is_vertical", false);
                    if (isVertical) {
                        if (recyclerView != null) {
                            int scrollAmount = recyclerView.getHeight() / 2;
                            recyclerView.smoothScrollBy(0, -scrollAmount);
                        }
                    } else {
                        if (viewPager != null) {
                            int currentItem = viewPager.getCurrentItem();
                            if (currentItem > 0) {
                                viewPager.setCurrentItem(currentItem - 1, true);
                            }
                        }
                    }
                }
                return true;
            case android.view.KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == android.view.KeyEvent.ACTION_DOWN) {
                    boolean isVertical = prefs.getBoolean("reader_is_vertical", false);
                    if (isVertical) {
                        if (recyclerView != null) {
                            int scrollAmount = recyclerView.getHeight() / 2;
                            recyclerView.smoothScrollBy(0, scrollAmount);
                        }
                    } else {
                        if (viewPager != null) {
                            int currentItem = viewPager.getCurrentItem();
                            if (adapter != null && currentItem < adapter.getItemCount() - 1) {
                                viewPager.setCurrentItem(currentItem + 1, true);
                            }
                        }
                    }
                }
                return true;
        }
        
        return super.dispatchKeyEvent(event);
    }
    
    private void fetchChapterContent(int topicId) {
        fetchChapterContent(topicId, false);
    }

    
    private TopicDetailResponse workData;
    private final com.fimtale.editor.AuthoringAccess editorAccess = new com.fimtale.editor.AuthoringAccess(this);
    private long editorVersion;

    private void updateEditorMenu() {
        if (moreMenu != null) {
            moreMenu.getMenu().findItem(R.id.action_edit_content).setVisible(workData != null && editorAccess.canEdit(workData.getAuthorInfo()));
            if (moreMenu.isShowing()) moreMenu.refresh();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        editorAccess.refresh(this::updateEditorMenu);
        long version = com.fimtale.editor.EditorChanges.version(rootTopicId);
        if (version != editorVersion) { editorVersion = version; isLoadingChapter = false; loadWorkNavigation(); }
    }
    private boolean contentReady;
    private com.fimtale.ui.PageErrorView pageError;
    private void loadWorkNavigation() {
        pageError.hide();
        RetrofitClient.getInstance().getWork(rootTopicId).enqueue(new Callback<TopicDetailResponse>() {
            @Override public void onResponse(Call<TopicDetailResponse> call, Response<TopicDetailResponse> response) {
                if (isFinishing() || isDestroyed()) return;
                if (response.isSuccessful() && response.body() != null && response.body().getTopicInfo() != null) {
                    applyNavigation(response.body());
                    CacheManager.getInstance(ReaderActivity.this).cacheChapterMenu(rootTopicId, workData);
                    fetchChapterContent(currentTopicId);
                } else { showReadError(getString(R.string.reader_catalog_failed)); }
            }
            @Override public void onFailure(Call<TopicDetailResponse> call, Throwable t) {
                CacheManager.getInstance(ReaderActivity.this).getChapterMenu(rootTopicId, data -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (data != null) { applyNavigation(data); fetchChapterContent(currentTopicId); }
                    else showReadError(getString(R.string.reader_catalog_offline_unavailable));
                });
            }
        });
    }
    private void applyNavigation(TopicDetailResponse data) {
        workData = data;
        updateEditorMenu();
        chapterList = data.getMenu();
        filteredChapterList.clear(); filteredChapterList.addAll(chapterList);
        if (chapterListAdapter != null) chapterListAdapter.updateData(filteredChapterList);
        updateReaderMenu();
    }
    private int cacheKey(int chapterId) { return chapterId == 0 ? -rootTopicId : chapterId; }

    private void fetchChapterContent(int topicId, boolean scrollToEnd) {
        pageError.hide();
        LoadedChapter existing = findLoadedChapter(topicId);
        if (existing != null) {
            activateChapter(existing, scrollToEnd, false);
            ensureAdjacentChapters(topicId);
            return;
        }

        if (loadedChapters.isEmpty()) {
            isLoadingChapter = true;
            contentReady = false;
            if (viewPager.getVisibility() == View.VISIBLE) calculatePages(); else prepareVerticalContent();
        }
        requestChapter(topicId, true, scrollToEnd);
    }

    private LoadedChapter findLoadedChapter(int chapterId) {
        for (LoadedChapter chapter : loadedChapters) if (chapter.id == chapterId) return chapter;
        return null;
    }

    private LoadedChapter createLoadedChapter(int id, String title, String content) {
        String source = content == null || content.isEmpty() ? getString(R.string.common_no_content) : content;
        Spanned rendered = BbCodeText.normalizeTables(markwon.toMarkdown(com.fimtale.utils.BbCode.toMarkdown(source)));
        return new LoadedChapter(id, title, parseSegments(rendered));
    }

    private void requestChapter(int chapterId, boolean activate, boolean scrollToEnd) {
        if (loadingChapterIds.contains(chapterId)) return;
        loadingChapterIds.add(chapterId);

        if (chapterId == 0 && workData != null) {
            TopicInfo work = workData.getTopicInfo();
            loadingChapterIds.remove(chapterId);
            acceptLoadedChapter(createLoadedChapter(0, work.getTitle(), work.getContent()), activate, scrollToEnd);
            return;
        }

        CacheManager.getInstance(this).getChapter(cacheKey(chapterId), cached -> {
            if (isFinishing() || isDestroyed()) return;
            if (cached != null && cached.rootTopicId == rootTopicId) {
                loadingChapterIds.remove(chapterId);
                acceptLoadedChapter(createLoadedChapter(cached.postId, cached.title, cached.content), activate, scrollToEnd);
            } else {
                fetchChapterFromNetwork(chapterId, activate, scrollToEnd);
            }
        });
    }

    private void showReadError(String message) {
        showReadError(message, this::loadWorkNavigation);
    }
    private void showReadError(String message, Runnable retry) {
        isLoadingChapter = false;
        pageError.show(message, retry, contentReady);
    }

    private void acceptLoadedChapter(LoadedChapter chapter, boolean activate, boolean scrollToEnd) {
        if (findLoadedChapter(chapter.id) != null) {
            if (activate) activateChapter(findLoadedChapter(chapter.id), scrollToEnd, false);
            return;
        }

        // A directory/branch jump starts a fresh continuous window around the target.
        // Background adjacent loads use activate=false and extend the existing window.
        boolean reset = activate;
        if (reset) loadedChapters.clear();
        insertLoadedChapter(chapter);

        if (activate) {
            activateChapter(chapter, scrollToEnd, false);
        } else {
            rebuildReaderContent(true);
        }
    }

    private void activateChapter(LoadedChapter chapter, boolean scrollToEnd, boolean reset) {
        pageError.hide();
        if (reset) loadedChapters.clear();
        if (findLoadedChapter(chapter.id) == null) insertLoadedChapter(chapter);
        contentReady = true;
        isLoadingChapter = false;
        chapterTitle = chapter.title;
        currentPostId = chapter.id;
        currentTopicId = chapter.id;
        currentProgress = 0;
        tvChapterTitle.setText(chapterTitle);
        rebuildReaderContent(false);
        positionReader(currentTopicId, scrollToEnd);
        ensureAdjacentChapters(currentTopicId);
    }

    private void insertLoadedChapter(LoadedChapter chapter) {
        if (findLoadedChapter(chapter.id) != null) return;
        if (loadedChapters.isEmpty()) { loadedChapters.add(chapter); return; }

        int currentIndex = indexOfLoadedChapter(currentTopicId);
        int previousId = previousChapterId(currentTopicId);
        int nextId = nextChapterId(currentTopicId);
        if (chapter.id == previousId && currentIndex >= 0) {
            loadedChapters.add(currentIndex, chapter);
        } else if (chapter.id == nextId && currentIndex >= 0) {
            loadedChapters.add(currentIndex + 1, chapter);
        } else if (chapter.id == 0) {
            loadedChapters.add(0, chapter);
        } else {
            loadedChapters.add(chapter);
        }
    }

    private int indexOfLoadedChapter(int chapterId) {
        for (int i = 0; i < loadedChapters.size(); i++) if (loadedChapters.get(i).id == chapterId) return i;
        return -1;
    }

    private int nextChapterId(int chapterId) {
        List<TopicDetailResponse.ChapterEdge> choices = com.fimtale.model.ChapterNavigation.choices(workData, chapterId);
        return choices.size() == 1 ? (choices.get(0).to == null ? 0 : choices.get(0).to) : -1;
    }

    private int previousChapterId(int chapterId) {
        return com.fimtale.model.ChapterNavigation.previous(workData, chapterId);
    }

    private void ensureAdjacentChapters(int chapterId) {
        if (!contentReady || chapterId < 0) return;
        int nextId = nextChapterId(chapterId);
        int previousId = previousChapterId(chapterId);
        if (nextId != -1 && findLoadedChapter(nextId) == null) requestChapter(nextId, false, false);
        if (previousId != -1 && findLoadedChapter(previousId) == null) requestChapter(previousId, false, false);
    }

    private void scheduleAdjacentChapters() {
        if (adjacentChapterLoadPosted || !contentReady) return;
        adjacentChapterLoadPosted = true;
        recyclerView.post(() -> {
            adjacentChapterLoadPosted = false;
            if (!isFinishing() && !isDestroyed() && !rebuildingReaderContent && pendingVerticalPosition == null)
                ensureAdjacentChapters(currentTopicId);
        });
    }

    private void fetchChapterFromNetwork(int chapterId, boolean activate, boolean scrollToEnd) {
        RetrofitClient.getInstance().getChapter(chapterId).enqueue(new Callback<com.fimtale.model.ChapterResponse>() {
            @Override public void onResponse(Call<com.fimtale.model.ChapterResponse> call, Response<com.fimtale.model.ChapterResponse> response) {
                if (isFinishing() || isDestroyed()) return;
                loadingChapterIds.remove(chapterId);
                com.fimtale.model.ChapterResponse data = response.body();
                if (response.isSuccessful() && data != null && data.chapter != null && data.chapter.workId == rootTopicId) {
                    CacheManager.getInstance(ReaderActivity.this).cacheChapter(data.chapter.id, rootTopicId,
                            data.chapter.id, data.chapter.title, data.chapter.content, null);
                    acceptLoadedChapter(createLoadedChapter(data.chapter.id, data.chapter.title, data.chapter.content), activate, scrollToEnd);
                } else if (activate) showReadError(getString(R.string.reader_chapter_failed), () -> requestChapter(chapterId, true, scrollToEnd));
            }
            @Override public void onFailure(Call<com.fimtale.model.ChapterResponse> call, Throwable t) {
                loadingChapterIds.remove(chapterId);
                if (activate && !isFinishing() && !isDestroyed()) showReadError(getString(R.string.error_network_retry), () -> requestChapter(chapterId, true, scrollToEnd));
            }
        });
    }

    private void positionReader(int topicId, boolean scrollToEnd) {
        double progress = scrollToEnd ? 1 : 0;
        if (!scrollToEnd && shouldApplyInitialProgress(topicId)) {
            progress = initialProgress;
            initialProgressApplied = true;
        }
        positionReaderAtProgress(topicId, progress);
    }

    private void positionReaderAtProgress(int topicId, double progress) {
        if (viewPager.getVisibility() == View.VISIBLE) {
            if (pages.isEmpty()) return;
            int page = computeTargetPagedIndex(topicId, progress);
            viewPager.setCurrentItem(page, false);
            updateCurrentChapterFromPage(page);
        } else if (!verticalPages.isEmpty() && paragraphStartOffsets.size() == verticalPages.size()) {
            recyclerView.stopScroll();
            restoreVerticalPosition(verticalPositionForProgress(topicId, progress));
        }
    }

    private VerticalPosition captureVerticalPosition() {
        // Several cached chapters can arrive before the next layout. Keep the original anchor.
        if (pendingVerticalPosition != null) return pendingVerticalPosition;
        LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (manager == null) return null;
        int first = manager.findFirstVisibleItemPosition();
        if (first < 0 || first >= verticalPages.size()) return null;
        View view = manager.findViewByPosition(first);
        if (view == null) return null;
        int chapterId = verticalPages.get(first).chapterId;
        return new VerticalPosition(chapterId, first - chapterStartIndex(verticalPages, chapterId),
                verticalItemTop(manager, view) - recyclerView.getPaddingTop());
    }

    private void restoreVerticalPosition(VerticalPosition position) {
        LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (manager == null || verticalPages.isEmpty()) return;
        pendingVerticalPosition = position;
        int target = Math.min(chapterEndIndex(verticalPages, position.chapterId),
                chapterStartIndex(verticalPages, position.chapterId) + position.paragraph);
        manager.scrollToPositionWithOffset(target, position.offset);
    }

    private boolean onVerticalPreDraw() {
        if (recyclerView.getVisibility() != View.VISIBLE || rebuildingReaderContent
                || recyclerView.hasPendingAdapterUpdates()) return true;
        VerticalPosition position = pendingVerticalPosition;
        if (position != null) {
            if (position.fraction > 0) {
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                int target = chapterStartIndex(verticalPages, position.chapterId) + position.paragraph;
                View view = manager == null ? null : manager.findViewByPosition(target);
                if (view == null || view.getHeight() <= 0) return true;
                // The row must be measured before restoring an offset inside a long paragraph.
                restoreVerticalPosition(new VerticalPosition(position.chapterId, position.paragraph,
                        -(int) Math.round(verticalItemHeight(manager, view) * position.fraction)));
                return false;
            }
            pendingVerticalPosition = null;
        }
        updateVerticalProgress();
        return true;
    }

    private int chapterStartIndex(List<ReaderPage> data, int chapterId) {
        for (int i = 0; i < data.size(); i++) if (data.get(i).chapterId == chapterId) return i;
        return data.isEmpty() ? 0 : 0;
    }

    private int chapterEndIndex(List<ReaderPage> data, int chapterId) {
        int start = chapterStartIndex(data, chapterId);
        for (int i = start + 1; i < data.size(); i++) {
            if (data.get(i).chapterId != chapterId) return i - 1;
        }
        return data.isEmpty() ? 0 : data.size() - 1;
    }

    private void rebuildReaderContent(boolean preservePosition) {
        boolean vertical = recyclerView.getVisibility() == View.VISIBLE;
        VerticalPosition verticalPosition = preservePosition && vertical ? captureVerticalPosition() : null;
        int oldPage = Math.min(viewPager.getCurrentItem(), Math.max(0, pages.size() - 1));
        int pageChapterId = oldPage < pages.size() ? pages.get(oldPage).chapterId : currentTopicId;
        int oldChapterStart = chapterStartIndex(pages, pageChapterId);
        int relativeTextOffset = oldPage < pageStartOffsets.size() && oldChapterStart < pageStartOffsets.size()
                ? pageStartOffsets.get(oldPage) - pageStartOffsets.get(oldChapterStart) : 0;
        int pageTarget = 0;
        if (!preservePosition) pendingVerticalPosition = null;
        rebuildingReaderContent = true;
        try {
            calculatePages();
            prepareVerticalContent();
            if (preservePosition) {
                if (vertical && verticalPosition != null) {
                    restoreVerticalPosition(verticalPosition);
                } else if (!vertical && !pages.isEmpty()) {
                    pageTarget = chapterStartIndex(pages, pageChapterId);
                    if (pageStartOffsets.size() == pages.size()) {
                        int targetOffset = pageStartOffsets.get(pageTarget) + relativeTextOffset;
                        while (pageTarget + 1 < pages.size() && pages.get(pageTarget + 1).chapterId == pageChapterId
                                && pageStartOffsets.get(pageTarget + 1) <= targetOffset) pageTarget++;
                    }
                    viewPager.setCurrentItem(pageTarget, false);
                }
            }
        } finally {
            rebuildingReaderContent = false;
        }
        if (preservePosition && !vertical) updateCurrentChapterFromPage(pageTarget);
    }


    @Override
    protected void onPause() {
        super.onPause();
        saveReadingProgress();
    }

    @Override
    protected void onDestroy() {
        View root = findViewById(R.id.readerRoot);
        if (root != null) ViewCompat.setWindowInsetsAnimationCallback(root, null);
        imeAnimations.clear();
        if (viewPager != null) viewPager.setAdapter(null);
        if (recyclerView != null) recyclerView.setAdapter(null);
        if (menuOverlay != null) TransitionManager.endTransitions((ViewGroup) menuOverlay);
        if (moreMenu != null) moreMenu.dismiss();
        if (readerCommentsSheetPanel != null) readerCommentsSheetPanel.close();
        if (readerCommentsSheet != null) readerCommentsSheet.dismiss();
        editorAccess.close();
        progressSaveHandler.removeCallbacks(progressSaveRunnable);
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver);
            batteryReceiverRegistered = false;
        }
        super.onDestroy();
    }

    private void checkAndShowGuide() {
        boolean hasShownGuide = prefs.getBoolean("has_shown_reader_guide", false);
        if (!hasShownGuide) {
            guideOverlay.setAlpha(1f);
            guideOverlay.setVisibility(View.VISIBLE);
            guideOverlay.setOnClickListener(v -> {
                guideOverlay.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction(() -> guideOverlay.setVisibility(View.GONE))
                        .start();
                prefs.edit().putBoolean("has_shown_reader_guide", true).apply();
            });
        }
    }
    
    private void setupChapterList() {
        rvChapterList.setLayoutManager(new LinearLayoutManager(this));
        chapterListAdapter = new ChapterListAdapter();
        rvChapterList.setAdapter(chapterListAdapter);
    }

    private void jumpToChapter(int chapterId) {
        jumpToChapter(chapterId, false);
    }
    
    private void jumpToChapter(int chapterId, boolean scrollToEnd) {
        if (isLoadingChapter) return;
        saveReadingProgress();
        if (chapterId == 0 && currentTopicId != 0) {
            Intent intent = new Intent(this, TopicDetailActivity.class);
            intent.putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, rootTopicId);
            startActivity(intent); finish(); return;
        }
        fetchChapterContent(chapterId, scrollToEnd);
        hideMenu();
    }

    private void showReaderCommentsBottomSheet(int chapterId) {
        if (isFinishing() || isDestroyed()) return;
        if (readerCommentsSheet != null && readerCommentsSheet.isShowing()) return;

        Context commentsContext = readerCommentsContext();
        View content = LayoutInflater.from(commentsContext).inflate(R.layout.item_reader_comment_page, null);
        readerCommentsSheet = new BottomSheetDialog(commentsContext);
        readerCommentsSheet.setContentView(content);
        readerCommentsSheetPanel = new ReaderCommentsPanel(this, content, rootTopicId);
        readerCommentsSheetPanel.setWorkAuthor(workData == null || workData.getAuthorInfo() == null ? 0 : workData.getAuthorInfo().getId());
        readerCommentsSheetPanel.bind(chapterId);
        if (readerCommentsSheet.getWindow() != null)
            readerCommentsSheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        readerCommentsSheet.setOnDismissListener(dialog -> {
            if (readerCommentsSheetPanel != null) {
                readerCommentsSheetPanel.close();
                readerCommentsSheetPanel = null;
            }
            readerCommentsSheet = null;
        });
        readerCommentsSheet.show();

        content.post(() -> {
            View parent = content.getParent() instanceof View ? (View) content.getParent() : null;
            if (parent == null) return;
            ViewGroup.LayoutParams params = parent.getLayoutParams();
            if (params != null) {
                params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
                parent.setLayoutParams(params);
            }
            try {
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(parent);
                behavior.setSkipCollapsed(true);
                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            } catch (IllegalArgumentException ignored) {
                // The dialog theme may provide a non-standard bottom sheet parent.
            }
        });
    }

    private void updateCurrentChapterFromPage(int pageIndex) {
        if (!contentReady || rebuildingReaderContent || viewPager.getVisibility() != View.VISIBLE) return;
        if (pageIndex < 0 || pageIndex >= pages.size()) return;
        ReaderPage page = pages.get(pageIndex);
        LoadedChapter chapter = findLoadedChapter(page.chapterId);
        if (chapter == null) return;
        currentTopicId = chapter.id;
        currentPostId = chapter.id;
        chapterTitle = chapter.title;
        int chapterStart = chapterStartIndex(pages, chapter.id);
        int chapterEnd = chapterEndIndex(pages, chapter.id);
        int realTotal = Math.max(1, chapterEnd - chapterStart + 1);
        int realIndex = Math.max(0, Math.min(realTotal - 1, pageIndex - chapterStart));
        if (realTotal > 1) {
            currentProgress = (double) realIndex / (realTotal - 1);
        } else {
            currentProgress = 1.0;
        }
        updateHeader(chapter.title, (realIndex + 1) + "/" + realTotal);
    }

    private void updateVerticalProgress() {
        if (!contentReady || rebuildingReaderContent || pendingVerticalPosition != null
                || recyclerView.getVisibility() != View.VISIBLE || recyclerView.hasPendingAdapterUpdates()) return;
        LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (manager == null || paragraphStartOffsets.size() != verticalPages.size()) return;
        int paragraphIndex = manager.findFirstVisibleItemPosition();
        if (paragraphIndex < 0 || paragraphIndex >= verticalPages.size()) return;
        int viewportBottom = recyclerView.getHeight() - recyclerView.getPaddingBottom();
        // A short final chapter may fit below the previous chapter without its title ever
        // reaching the top. Once it is fully visible, report that chapter as completed.
        int last = Math.min(manager.findLastVisibleItemPosition(), verticalPages.size() - 1);
        for (int i = paragraphIndex + 1; i <= last; i++) {
            ReaderPage candidate = verticalPages.get(i);
            if (candidate.type != ReaderPage.TYPE_COMMENT
                    || candidate.chapterId == verticalPages.get(paragraphIndex).chapterId) continue;
            View end = manager.findViewByPosition(i);
            if (end != null && verticalItemTop(manager, end) + verticalItemHeight(manager, end) <= viewportBottom)
                paragraphIndex = i;
        }
        ReaderPage page = verticalPages.get(paragraphIndex);
        LoadedChapter chapter = findLoadedChapter(page.chapterId);
        View first = manager.findViewByPosition(paragraphIndex);
        if (chapter == null || first == null) return;
        currentTopicId = chapter.id;
        currentPostId = chapter.id;
        chapterTitle = chapter.title;
        int chapterStart = chapterStartIndex(verticalPages, chapter.id);
        int chapterEnd = chapterEndIndex(verticalPages, chapter.id);
        int chapterOffset = paragraphStartOffsets.get(chapterStart);
        int total = paragraphStartOffsets.get(chapterEnd) - chapterOffset;
        int paragraphOffset = paragraphStartOffsets.get(paragraphIndex);
        int weight = paragraphIndex < chapterEnd ? paragraphStartOffsets.get(paragraphIndex + 1) - paragraphOffset : 0;
        int height = verticalItemHeight(manager, first);
        double fraction = height > 0
                ? clamp01((double) (recyclerView.getPaddingTop() - verticalItemTop(manager, first)) / height) : 0;
        // Content offsets are stable when other chapters load; RecyclerView's estimated scroll
        // range is not, because text, images and the comments entry have different heights.
        currentProgress = total > 0 ? clamp01((paragraphOffset - chapterOffset + weight * fraction) / total) : 1;
        View end = manager.findViewByPosition(chapterEnd);
        if (end != null && verticalItemTop(manager, end) + verticalItemHeight(manager, end)
                <= viewportBottom) currentProgress = 1;
        updateHeader(chapter.title, getString(R.string.reader_percent, currentProgress * 100));
        if (scrollProgressBar != null) scrollProgressBar.setProgress((int) Math.round(currentProgress * 1000));
        scheduleAdjacentChapters();
    }

    private int verticalItemTop(LinearLayoutManager manager, View view) {
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) view.getLayoutParams();
        return manager.getDecoratedTop(view) - params.topMargin;
    }

    private int verticalItemHeight(LinearLayoutManager manager, View view) {
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) view.getLayoutParams();
        return manager.getDecoratedMeasuredHeight(view) + params.topMargin + params.bottomMargin;
    }
    
    private void updateHeader(String title, String progressText) {
        if (!TextUtils.equals(tvChapterTitle.getText(), title)) tvChapterTitle.setText(title);
        if (!TextUtils.equals(tvChapterProgress.getText(), progressText)) tvChapterProgress.setText(progressText);
        updateReaderMenu();
    }

    private void saveReadingProgress() {
        if (!contentReady || currentPostId < 0 || rootTopicId <= 0 || isLoadingChapter
                || rebuildingReaderContent || pendingVerticalPosition != null || !UserPreferences.isLoggedIn(this)) return;
        RetrofitClient.getInstance().saveReadingProgress(new com.fimtale.model.ReadProgress(rootTopicId, currentPostId, currentProgress))
                .enqueue(new Callback<com.fimtale.model.ReadProgress>() {
            @Override public void onResponse(Call<com.fimtale.model.ReadProgress> call, Response<com.fimtale.model.ReadProgress> response) {}
            @Override public void onFailure(Call<com.fimtale.model.ReadProgress> call, Throwable t) {}
        });
    }

    private void updateFontSize(float size) {
        currentFontSize = size;
        rebuildReaderContent(true);
    }

    private void saveFontSize() {
        prefs.edit().putFloat("reader_font_size", currentFontSize).apply();
    }

    private List<ContentSegment> parseSegments(Spanned rendered) {
        List<ContentSegment> segments = new ArrayList<>();
        for (BbCodeText.Segment segment : BbCodeText.segments(rendered)) {
            segments.add(new ContentSegment(segment.image == null ? ReaderPage.TYPE_TEXT : ReaderPage.TYPE_IMAGE,
                    segment.image == null ? segment.text : segment.image));
        }
        return segments;
    }

    private void prepareVerticalContent() {
        verticalPages.clear();
        paragraphStartOffsets.clear();
        chapterVerticalIndices.clear();
        
        if (loadedChapters.isEmpty()) {
            verticalPages.add(new ReaderPage(ReaderPage.TYPE_LOADING, null, currentTopicId));
            if (recyclerAdapter != null) {
                recyclerAdapter.notifyDataSetChanged();
            }
            return;
        }

        int currentOffset = 0;
        String paragraphSpacing = "\n";

        for (LoadedChapter chapter : loadedChapters) {
            chapterVerticalIndices.add(verticalPages.size());
            verticalPages.add(new ReaderPage(ReaderPage.TYPE_TITLE, chapter.title + paragraphSpacing, chapter.id));
            paragraphStartOffsets.add(currentOffset);
            currentOffset += chapter.title.length() + paragraphSpacing.length();

            for (ContentSegment segment : chapter.segments) {
                if (segment.type == ReaderPage.TYPE_TEXT) {
                    for (CharSequence paragraph : BbCodeText.verticalChunks(segment.content)) {
                        verticalPages.add(new ReaderPage(ReaderPage.TYPE_TEXT, paragraph, chapter.id));
                        paragraphStartOffsets.add(currentOffset);
                        currentOffset += paragraph.length();
                    }
                } else if (segment.type == ReaderPage.TYPE_IMAGE) {
                    verticalPages.add(new ReaderPage(ReaderPage.TYPE_IMAGE, segment.content, chapter.id));
                    paragraphStartOffsets.add(currentOffset);
                    currentOffset += 400;
                }
            }

            // Keep a comments entry between every chapter in continuous mode.
            verticalPages.add(new ReaderPage(ReaderPage.TYPE_COMMENT, null, chapter.id));
            paragraphStartOffsets.add(currentOffset);
        }
        
        if (recyclerAdapter != null) {
            recyclerAdapter.notifyDataSetChanged();
        }
    }

    private void updatePageMode(boolean isVertical) {
        int topicId = currentTopicId;
        double progress = currentProgress;
        pendingVerticalPosition = null;
        recyclerView.stopScroll();
        if (isVertical) {
            viewPager.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            if (scrollProgressBar != null) {
                if (UserPreferences.isShowReaderProgress(this)) {
                    scrollProgressBar.setVisibility(View.VISIBLE);
                } else {
                    scrollProgressBar.setVisibility(View.GONE);
                }
            }
            
            applyReaderTheme(UserPreferences.getReaderTheme(this));
            
            prepareVerticalContent();
        } else {
            recyclerView.setVisibility(View.GONE);
            viewPager.setVisibility(View.VISIBLE);
            if (scrollProgressBar != null) {
                scrollProgressBar.setVisibility(View.GONE);
            }
            
            applyReaderTheme(UserPreferences.getReaderTheme(this));
            
            calculatePages();
        }
        if (contentReady) positionReaderAtProgress(topicId, progress);
    }
    
    private int readerBackground(int theme) {
        switch (theme) {
            case 1: return getColor(R.color.reader_paper_background);
            case 2: return getColor(R.color.reader_matcha_background);
            case 3: return getColor(R.color.reader_ocean_background);
            case 4: return getColor(R.color.reader_night_background);
            default: return getThemeColor(android.R.attr.colorBackground);
        }
    }

    private int readerForeground(int theme) {
        switch (theme) {
            case 1: return getColor(R.color.reader_paper_text);
            case 2: return getColor(R.color.reader_matcha_text);
            case 3: return getColor(R.color.reader_ocean_text);
            case 4: return getColor(R.color.reader_night_text);
            default: return getThemeColor(com.google.android.material.R.attr.colorOnBackground);
        }
    }

    private boolean isReaderDark(int theme) {
        return theme == 4 || theme == 0 && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private Context readerCommentsContext() {
        android.content.res.Configuration config = new android.content.res.Configuration(getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | (isReaderDark(UserPreferences.getReaderTheme(this))
                ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO);
        android.view.ContextThemeWrapper context = new android.view.ContextThemeWrapper(this, R.style.Theme_Fimtale);
        context.applyOverrideConfiguration(config);
        return context;
    }

    private ColorStateList enabledReaderColor(int color) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{ColorUtils.blendARGB(readerBackgroundColor, color, 0.3f), color});
    }

    private int readerAccent() {
        int accent = getThemeColor(com.google.android.material.R.attr.colorPrimary);
        for (int step = 0; step <= 10; step++) {
            int candidate = ColorUtils.blendARGB(accent, readerTextColor, step / 10f);
            if (ColorUtils.calculateContrast(candidate, readerBackgroundColor) >= 4.5) return candidate;
        }
        return readerTextColor;
    }

    private void applyReaderTheme(int theme) {
        readerBackgroundColor = readerBackground(theme);
        readerTextColor = readerForeground(theme);
        int secondary = ColorUtils.blendARGB(readerBackgroundColor, readerTextColor, 0.75f);
        findViewById(R.id.readerRoot).setBackgroundColor(readerBackgroundColor);
        viewPager.setBackgroundColor(readerBackgroundColor);
        recyclerView.setBackgroundColor(readerBackgroundColor);
        readerHeader.setBackgroundColor(readerBackgroundColor);
        readerFooter.setBackgroundColor(readerBackgroundColor);
        for (TextView label : new TextView[]{tvChapterTitle, tvChapterProgress, tvBatteryLevel, tcSystemTime}) {
            label.setTextColor(secondary);
        }
        ivBattery.setImageTintList(ColorStateList.valueOf(secondary));
        ivCharging.setImageTintList(ColorStateList.valueOf(secondary));

        topToolbar.getBackground().mutate().setTint(readerBackgroundColor);
        bottomSheetContainer.getBackground().mutate().setTint(readerBackgroundColor);
        tintReaderControls(menuOverlay, secondary);
        topToolbar.setTitleTextColor(readerTextColor);
        topToolbar.setNavigationIconTint(readerTextColor);
        for (int i = 0; i < topToolbar.getMenu().size(); i++) {
            android.graphics.drawable.Drawable icon = topToolbar.getMenu().getItem(i).getIcon();
            if (icon != null) icon.mutate().setTint(readerTextColor);
        }
        tvReaderMenuSubtitle.setTextColor(secondary);
        tvFontSizeValue.setTextColor(secondary);
        tvLineSpacingValue.setTextColor(secondary);
        TextView locate = findViewById(R.id.btnLocateChapter);
        int accent = readerAccent();
        locate.setTextColor(enabledReaderColor(accent));
        locate.setCompoundDrawableTintList(enabledReaderColor(accent));
        btnReaderTheme.setText(isReaderDark(theme) ? R.string.reader_theme_day : R.string.reader_theme_night);
        btnReaderTheme.setCompoundDrawablesRelativeWithIntrinsicBounds(null,
                MdiIcons.drawable(this, isReaderDark(theme) ? "weather-sunny" : "weather-night"), null, null);
        btnReaderTheme.setCompoundDrawableTintList(ColorStateList.valueOf(secondary));
        updateThemeSwatches(theme, secondary);
        updateBottomMenuButtons();

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!isReaderDark(theme));
        controller.setAppearanceLightNavigationBars(!isReaderDark(theme));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (recyclerAdapter != null) recyclerAdapter.notifyDataSetChanged();
        if (chapterListAdapter != null) chapterListAdapter.notifyDataSetChanged();
    }

    private void tintReaderControls(View view, int secondary) {
        int accent = readerAccent();
        if (view instanceof TabLayout) {
            TabLayout tabs = (TabLayout) view;
            tabs.setTabTextColors(secondary, accent);
            tabs.setSelectedTabIndicatorColor(accent);
            return;
        }
        if (view instanceof Slider) {
            Slider slider = (Slider) view;
            slider.setThumbTintList(enabledReaderColor(accent));
            slider.setTrackActiveTintList(enabledReaderColor(accent));
            slider.setTrackInactiveTintList(ColorStateList.valueOf(
                    ColorUtils.blendARGB(readerBackgroundColor, readerTextColor, 0.15f)));
            slider.setHaloTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 35)));
            return;
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(enabledReaderColor(readerTextColor));
            text.setCompoundDrawableTintList(enabledReaderColor(readerTextColor));
        }
        if (view instanceof MaterialCheckBox) {
            ((MaterialCheckBox) view).setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{accent, secondary}));
        }
        if (view instanceof ImageView) ((ImageView) view).setImageTintList(enabledReaderColor(readerTextColor));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintReaderControls(group.getChildAt(i), secondary);
        }
    }

    private void updateThemeSwatches(int selected, int secondary) {
        int accent = readerAccent();
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < tabThemeMode.getTabCount(); i++) {
            TabLayout.Tab tab = tabThemeMode.getTabAt(i);
            View custom = tab == null ? null : tab.getCustomView();
            if (custom == null) continue;
            MaterialCardView swatch = custom.findViewById(R.id.readerThemeSwatch);
            swatch.setCardBackgroundColor(readerBackground(i));
            swatch.setStrokeColor(i == selected ? accent
                    : ColorUtils.blendARGB(readerBackgroundColor, readerTextColor, 0.2f));
            swatch.setStrokeWidth(Math.round((i == selected ? 2 : 1) * density));
            ((TextView) custom.findViewById(R.id.readerThemeSample)).setTextColor(readerForeground(i));
            ((TextView) custom.findViewById(R.id.readerThemeName)).setTextColor(i == selected ? accent : secondary);
        }
    }

    private int getThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    private void setReaderTextPadding(TextView text, boolean vertical) {
        int horizontal = readerDp(currentPageMargin);
        if (vertical) {
            int bottom = Math.round(readerDp(16) * UserPreferences.getLineSpacing(this));
            text.setPadding(horizontal, 0, horizontal, bottom);
        } else {
            text.setPadding(horizontal, readerDp(24), horizontal, readerDp(24));
        }
    }

    private void calculatePages() {
        int width = viewPager.getWidth();
        int height = viewPager.getHeight();

        if (loadedChapters.isEmpty()) {
            pages.clear();
            pages.add(new ReaderPage(ReaderPage.TYPE_LOADING, null, currentTopicId));
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }

        if (width > 0 && height > 0) {
            lastWidth = width;
            lastHeight = height;
        } else if (lastWidth > 0 && lastHeight > 0) {
            width = lastWidth;
            height = lastHeight;
        } else {
            width = getResources().getDisplayMetrics().widthPixels;
            height = getResources().getDisplayMetrics().heightPixels;
            lastWidth = width;
            lastHeight = height;
        }
        
        TextView prototype = LayoutInflater.from(this).inflate(R.layout.item_reader_page, null, false)
                .findViewById(R.id.pageContentTextView);
        setReaderTextPadding(prototype, false);
        boolean keepPagination = imeChangesViewport() && pagedViewportWidth > 0 && pagedViewportHeight > 0;
        int viewportWidth = keepPagination ? pagedViewportWidth : width - viewPager.getPaddingLeft() - viewPager.getPaddingRight();
        int viewportHeight = keepPagination ? pagedViewportHeight : height - viewPager.getPaddingTop() - viewPager.getPaddingBottom();
        int contentWidth = viewportWidth - prototype.getCompoundPaddingLeft() - prototype.getCompoundPaddingRight();
        int contentHeight = viewportHeight - prototype.getCompoundPaddingTop() - prototype.getCompoundPaddingBottom();

        if (contentWidth <= 0 || contentHeight <= 0) return;
        pagedViewportWidth = viewportWidth;
        pagedViewportHeight = viewportHeight;

        pages.clear();
        chapterStartPageIndices.clear();
        pageStartOffsets.clear();
        int globalOffset = 0;
        float lineSpacingMultiplier = UserPreferences.getLineSpacing(this);

        for (LoadedChapter chapter : loadedChapters) {
            chapterStartPageIndices.add(pages.size());
            int segmentStart = 0;
            SpannableStringBuilder firstBlock = new SpannableStringBuilder(chapter.title == null ? "" : chapter.title)
                    .append("\n\n");
            while (segmentStart < chapter.segments.size()
                    && chapter.segments.get(segmentStart).type == ReaderPage.TYPE_TEXT) {
                CharSequence text = chapter.segments.get(segmentStart).content;
                firstBlock.append(text);
                segmentStart++;
            }
            SpannableString titleBlock = new SpannableString(firstBlock);
            int titleLength = Math.min(chapter.title == null ? 0 : chapter.title.length(), titleBlock.length());
            if (titleLength > 0) {
                titleBlock.setSpan(new StyleSpan(Typeface.BOLD), 0, titleLength,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                titleBlock.setSpan(new RelativeSizeSpan(1.25f), 0, titleLength,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            addPagedText(titleBlock, chapter.id, ReaderPage.TYPE_TEXT, titleLength,
                    contentWidth, contentHeight, lineSpacingMultiplier, globalOffset);
            globalOffset += titleBlock.length();

            for (int i = segmentStart; i < chapter.segments.size();) {
                ContentSegment segment = chapter.segments.get(i);
                if (segment.type == ReaderPage.TYPE_IMAGE) {
                    pages.add(new ReaderPage(ReaderPage.TYPE_IMAGE, segment.content, chapter.id));
                    pageStartOffsets.add(globalOffset++);
                    i++;
                    continue;
                }

                SpannableStringBuilder textBlock = new SpannableStringBuilder();
                int next = i;
                while (next < chapter.segments.size()
                        && chapter.segments.get(next).type == ReaderPage.TYPE_TEXT) {
                    CharSequence text = chapter.segments.get(next).content;
                    textBlock.append(text);
                    next++;
                }
                CharSequence formattedContent = textBlock;
                addPagedText(formattedContent, chapter.id, ReaderPage.TYPE_TEXT, 0,
                        contentWidth, contentHeight, lineSpacingMultiplier, globalOffset);
                globalOffset += formattedContent.length();
                i = next;
            }

            // A full comments page separates adjacent chapters in page mode.
            pages.add(new ReaderPage(ReaderPage.TYPE_COMMENT, null, chapter.id));
            pageStartOffsets.add(globalOffset);
        }
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void addPagedText(CharSequence content, int chapterId, int pageType, int titleLength,
                              int contentWidth, int contentHeight, float lineSpacingMultiplier,
                              int globalOffset) {
        TextView prototype = LayoutInflater.from(this).inflate(R.layout.item_reader_page, null, false)
                .findViewById(R.id.pageContentTextView);
        ReaderPagination.configure(prototype, currentFontSize, lineSpacingMultiplier, false);
        for (ReaderPagination.Page page : ReaderPagination.paginate(content, prototype, contentWidth, contentHeight)) {
            int pageTitleLength = Math.max(0, Math.min(titleLength, page.end) - page.start);
            pages.add(new ReaderPage(page.scrollable ? ReaderPage.TYPE_SCROLL_TEXT : pageType,
                    page.text, chapterId, pageTitleLength));
            pageStartOffsets.add(globalOffset + page.start);
        }
    }

    /** Keyboard geometry affects the comment composer without changing book pagination. */
    private boolean imeChangesViewport() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(viewPager);
        return !imeAnimations.isEmpty() || (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()));
    }

    private void checkPageViewport() {
        if (isFinishing() || isDestroyed() || imeChangesViewport()) return;
        int width = viewPager.getWidth() - viewPager.getPaddingLeft() - viewPager.getPaddingRight();
        int height = viewPager.getHeight() - viewPager.getPaddingTop() - viewPager.getPaddingBottom();
        if (width > 0 && height > 0 && (width != pagedViewportWidth || height != pagedViewportHeight)) requestPageReflow();
    }

    /** Reflow for persistent viewport changes, retaining the current chapter and text offset. */
    private void requestPageReflow() {
        if (pageReflowPending || viewPager == null || viewPager.getVisibility() != View.VISIBLE || loadedChapters.isEmpty() || imeChangesViewport()) return;
        pageReflowPending = true;
        viewPager.post(() -> {
            pageReflowPending = false;
            if (isFinishing() || isDestroyed() || pages.isEmpty() || viewPager.getVisibility() != View.VISIBLE || imeChangesViewport()) return;
            int oldIndex = Math.min(viewPager.getCurrentItem(), pages.size() - 1);
            int chapterId = pages.get(oldIndex).chapterId;
            int chapterStart = chapterStartIndex(pages, chapterId);
            int relativeOffset = oldIndex < pageStartOffsets.size() && chapterStart < pageStartOffsets.size()
                    ? pageStartOffsets.get(oldIndex) - pageStartOffsets.get(chapterStart) : 0;
            calculatePages();
            int target = chapterStartIndex(pages, chapterId);
            if (target >= pageStartOffsets.size()) return;
            int offset = pageStartOffsets.get(target) + relativeOffset;
            while (target + 1 < pages.size() && pages.get(target + 1).chapterId == chapterId
                    && pageStartOffsets.get(target + 1) <= offset) target++;
            viewPager.setCurrentItem(target, false);
            updateCurrentChapterFromPage(target);
        });
    }

    private static boolean touchesLink(TextView view, MotionEvent event) {
        return com.fimtale.utils.SpoilerSpan.clickableAt(view, event) != null;
    }

    private void toggleMenu() {
        if (isMenuVisible) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    private float readerTitleFontSize() {
        return currentFontSize * 1.25f;
    }

    private void updateBottomMenuButtons() {
        int accent = readerAccent();
        int normal = ColorUtils.blendARGB(readerBackgroundColor, readerTextColor, 0.75f);
        for (View button : new View[]{btnChapterList, btnSettings, btnReaderTheme, findViewById(R.id.btnReaderComments)}) {
            boolean selected = button == btnChapterList && chapterListPanel.getVisibility() == View.VISIBLE
                    || button == btnSettings && settingsPanel.getVisibility() == View.VISIBLE;
            TextView text = (TextView) button;
            ColorStateList colors = enabledReaderColor(selected ? accent : normal);
            text.setTextColor(colors);
            text.setCompoundDrawableTintList(colors);
            text.setSelected(selected);
        }
    }

    private void updateReaderMenu() {
        if (tvReaderMenuTitle == null) return;
        CharSequence workTitle = workData != null && workData.getTopicInfo() != null
                ? workData.getTopicInfo().getTitle() : chapterTitle;
        if (!TextUtils.equals(topToolbar.getTitle(), workTitle)) topToolbar.setTitle(workTitle);
        if (!isMenuVisible) return;
        boolean catalog = chapterListPanel.getVisibility() == View.VISIBLE;
        boolean settings = settingsPanel.getVisibility() == View.VISIBLE;
        CharSequence title = settings ? getString(R.string.reader_settings)
                : catalog ? getString(R.string.reader_catalog_count, filteredChapterList.size())
                : TextUtils.isEmpty(chapterTitle) ? getString(R.string.work_read_label) : chapterTitle;
        tvReaderMenuTitle.setText(title);
        tvReaderMenuSubtitle.setVisibility(settings ? View.GONE : View.VISIBLE);
        if (!isScrubbingProgress) {
            tvReaderMenuSubtitle.setText(catalog ? getString(R.string.reader_current_chapter, chapterTitle)
                    : getString(R.string.reader_chapter_progress_value, currentProgress * 100));
            sliderReaderProgress.setValue((float) (clamp01(currentProgress) * 1000));
        }
        sliderReaderProgress.setEnabled(contentReady && !isLoadingChapter);
        findViewById(R.id.btnPreviousChapter).setEnabled(contentReady && !isLoadingChapter && getPrevChapterId() >= 0);
        View next = findViewById(R.id.btnNextChapter);
        next.setEnabled(contentReady && !isLoadingChapter && !nextChoices().isEmpty());
        int nextLabel = nextChoices().size() > 1 ? R.string.reader_choose_branch : R.string.reader_next_chapter;
        next.setContentDescription(getString(nextLabel));
        next.setTooltipText(getString(nextLabel));
        findViewById(R.id.btnReaderComments).setEnabled(contentReady && !isLoadingChapter);
        boolean hasCurrentChapter = false;
        for (ChapterMenuItem item : filteredChapterList) {
            if (item.getId() == currentTopicId) { hasCurrentChapter = true; break; }
        }
        findViewById(R.id.btnLocateChapter).setEnabled(hasCurrentChapter);
        rvChapterList.setVisibility(filteredChapterList.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.readerCatalogEmpty).setVisibility(filteredChapterList.isEmpty() ? View.VISIBLE : View.GONE);
        if (chapterListAdapter != null && chapterListAdapter.highlightedChapterId != currentTopicId) {
            chapterListAdapter.highlightedChapterId = currentTopicId;
            chapterListAdapter.notifyDataSetChanged();
        }
    }

    private void updateReaderPanelHeight() {
        if (menuOverlay.getHeight() <= 0) return;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) bottomSheetContainer.getLayoutParams();
        int headerHeight = findViewById(R.id.readerPanelHeader).getHeight();
        int reserved = Math.max(readerDp(48), headerHeight) + Math.max(readerDp(60), bottomMenu.getHeight()) + readerDp(16);
        int available = menuOverlay.getHeight() - topToolbar.getBottom() - margins.bottomMargin - readerDp(12);
        int height = Math.max(readerDp(48), Math.min(readerDp(400), available - reserved));
        for (View panel : new View[]{settingsPanel, chapterListPanel}) {
            ViewGroup.LayoutParams params = panel.getLayoutParams();
            if (params.height != height) {
                params.height = height;
                panel.setLayoutParams(params);
            }
        }
        findViewById(R.id.btnLocateChapter).setVisibility(height < readerDp(140) ? View.GONE : View.VISIBLE);
    }

    private int readerDp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleSettingsPanel() {
        switchReaderPanel(settingsPanel.getVisibility() == View.VISIBLE ? readerNavigationPanel : settingsPanel);
    }

    private void switchReaderPanel(View target) {
        View current = settingsPanel.getVisibility() == View.VISIBLE ? settingsPanel
                : chapterListPanel.getVisibility() == View.VISIBLE ? chapterListPanel : readerNavigationPanel;
        if (current == target) return;
        FastOutSlowInInterpolator easing = new FastOutSlowInInterpolator();
        if (isMenuVisible && ViewCompat.isLaidOut(menuOverlay)) {
            boolean returning = target == readerNavigationPanel;
            int axis = current == readerNavigationPanel || returning ? MaterialSharedAxis.Y : MaterialSharedAxis.X;
            boolean forward = current == readerNavigationPanel || current == chapterListPanel && !returning;
            MaterialSharedAxis content = new MaterialSharedAxis(axis, forward);
            content.addTarget(readerNavigationPanel).addTarget(chapterListPanel).addTarget(settingsPanel);
            ChangeBounds bounds = new ChangeBounds();
            // Include the card itself so its background grows with the content; keep the actions anchored below it.
            bounds.addTarget(bottomSheetContainer).addTarget(findViewById(R.id.readerPanelHeader)).addTarget(bottomMenu);
            TransitionSet transition = new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER)
                    .addTransition(bounds).addTransition(content)
                    .addTransition(new Fade().addTarget(tvReaderMenuSubtitle));
            transition.setDuration(240);
            transition.setInterpolator(easing);
            TransitionManager.beginDelayedTransition((ViewGroup) menuOverlay, transition);
        }
        settingsPanel.setVisibility(target == settingsPanel ? View.VISIBLE : View.GONE);
        chapterListPanel.setVisibility(target == chapterListPanel ? View.VISIBLE : View.GONE);
        readerNavigationPanel.setVisibility(target == readerNavigationPanel ? View.VISIBLE : View.GONE);
        updateReaderMenu();
        updateReaderPanelHeight();
        updateBottomMenuButtons();
        // Keep the page legible while previewing typography and colors.
        dimLayer.animate().alpha(target == settingsPanel ? 0.25f : 1f).setDuration(240).setInterpolator(easing).start();
        if (target == chapterListPanel) locateCurrentChapter();
    }

    private void locateCurrentChapter() {
        rvChapterList.post(() -> {
            LinearLayoutManager manager = (LinearLayoutManager) rvChapterList.getLayoutManager();
            if (manager == null) return;
            for (int i = 0; i < filteredChapterList.size(); i++) {
                if (filteredChapterList.get(i).getId() == currentTopicId) {
                    manager.scrollToPositionWithOffset(i, rvChapterList.getHeight() / 3);
                    return;
                }
            }
        });
    }

    private void toggleChapterList() {
        switchReaderPanel(chapterListPanel.getVisibility() == View.VISIBLE ? readerNavigationPanel : chapterListPanel);
    }

    private void showMenu() {
        TransitionManager.endTransitions((ViewGroup) menuOverlay);
        topToolbar.animate().cancel();
        bottomSheetContainer.animate().cancel();
        dimLayer.animate().cancel();
        recyclerView.stopScroll();
        isMenuVisible = true;
        menuOverlay.setVisibility(View.VISIBLE);
        findViewById(R.id.readerContent).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dimLayer.setClickable(true);
        updateReaderMenu();
        updateReaderPanelHeight();
        updateBottomMenuButtons();
        dimLayer.animate().alpha(settingsPanel.getVisibility() == View.VISIBLE ? 0.25f : 1f).setDuration(220).start();
        showSystemUI();
        topToolbar.animate().translationY(0).setDuration(220).start();
        bottomSheetContainer.animate().translationY(0).setDuration(220).withEndAction(null).start();
    }

    private void hideMenu() {
        if (!isMenuVisible) return;
        TransitionManager.endTransitions((ViewGroup) menuOverlay);
        isMenuVisible = false;
        topToolbar.animate().cancel();
        bottomSheetContainer.animate().cancel();
        dimLayer.animate().alpha(0f).setDuration(220).start();
        topToolbar.animate().translationY(-topToolbar.getBottom()).setDuration(220).start();
        bottomSheetContainer.animate().translationY(menuOverlay.getHeight() - bottomSheetContainer.getTop()).setDuration(220)
                .withEndAction(() -> {
                    if (isMenuVisible) return;
                    menuOverlay.setVisibility(View.INVISIBLE);
                    dimLayer.setClickable(false);
                    settingsPanel.setVisibility(View.GONE);
                    chapterListPanel.setVisibility(View.GONE);
                    readerNavigationPanel.setVisibility(View.VISIBLE);
                    findViewById(R.id.readerContent).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                    updateBottomMenuButtons();
                    hideSystemUI();
                }).start();
    }

    static void installWindowInsets(View root) {
        View header = root.findViewById(R.id.readerHeader);
        View footer = root.findViewById(R.id.readerFooter);
        View toolbar = root.findViewById(R.id.topToolbar);
        View bottomSheet = root.findViewById(R.id.bottomSheetContainer);
        View pager = root.findViewById(R.id.viewPager);
        View scroller = root.findViewById(R.id.recyclerView);
        float density = root.getResources().getDisplayMetrics().density;
        // Keep a stable safe area even if the first dispatch occurs in immersive mode.
        // Listen on the page itself, independently of the reading menu's visibility.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int side = Math.round(16 * density);
            ViewGroup.MarginLayoutParams topParams = (ViewGroup.MarginLayoutParams) toolbar.getLayoutParams();
            int top = insets.top + Math.round(4 * density);
            if (topParams.topMargin != top || topParams.leftMargin != insets.left + side || topParams.rightMargin != insets.right + side) {
                topParams.topMargin = top;
                topParams.leftMargin = insets.left + side; topParams.rightMargin = insets.right + side;
                toolbar.setLayoutParams(topParams);
            }
            ViewGroup.MarginLayoutParams bottomParams = (ViewGroup.MarginLayoutParams) bottomSheet.getLayoutParams();
            if (bottomParams.bottomMargin != insets.bottom + side || bottomParams.leftMargin != insets.left + side || bottomParams.rightMargin != insets.right + side) {
                bottomParams.bottomMargin = insets.bottom + side;
                bottomParams.leftMargin = insets.left + side; bottomParams.rightMargin = insets.right + side;
                bottomSheet.setLayoutParams(bottomParams);
            }
            header.setPadding(insets.left, insets.top + Math.round(12 * density), insets.right, header.getPaddingBottom());
            footer.setPadding(insets.left + side, footer.getPaddingTop(), insets.right + side,
                    insets.bottom + Math.round(8 * density));
            int bodyTop = insets.top + Math.round(50 * density);
            int bodyBottom = insets.bottom + Math.round(30 * density);
            pager.setPadding(insets.left, bodyTop, insets.right, bodyBottom);
            scroller.setPadding(insets.left, bodyTop, insets.right, bodyBottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void showSystemUI() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.show(WindowInsetsCompat.Type.systemBars());
        controller.setAppearanceLightStatusBars(!isReaderDark(UserPreferences.getReaderTheme(this)));
    }

    private class ReaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<ReaderPage> data;
        private boolean isVerticalMode;

        ReaderAdapter(List<ReaderPage> data, boolean isVerticalMode) {
            this.data = data;
            this.isVerticalMode = isVerticalMode;
            setHasStableIds(!isVerticalMode);
        }

        @Override public long getItemId(int position) {
            if (isVerticalMode) return RecyclerView.NO_ID;
            ReaderPage page = data.get(position);
            int chapter = java.util.Collections.binarySearch(chapterStartPageIndices, position);
            if (chapter < 0) chapter = -chapter - 2;
            int relativePage = chapter >= 0 ? position - chapterStartPageIndices.get(chapter) : position;
            // Comment identity survives changes to the chapter's page count and adjacent preloads.
            return ((long) page.chapterId << 32) | (page.type == ReaderPage.TYPE_COMMENT ? 0xffffffffL : relativePage);
        }
        
        @Override
        public int getItemViewType(int position) {
            return data.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ReaderPage.TYPE_LOADING || viewType == ReaderPage.TYPE_NEXT_CHAPTER_TRIGGER || viewType == ReaderPage.TYPE_PREV_CHAPTER_TRIGGER) {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reader_loading, parent, false);
                return new LoadingViewHolder(view);
            } else if (viewType == ReaderPage.TYPE_COMMENT) {
                FrameLayout container = new FrameLayout(parent.getContext());
                container.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        isVerticalMode ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT));
                return new CommentViewHolder(container);
            } else if (viewType == ReaderPage.TYPE_IMAGE) {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reader_image, parent, false);
                
                if (isVerticalMode) {
                    ViewGroup.LayoutParams params = view.getLayoutParams();
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    view.setLayoutParams(params);
                } else {
                    ViewGroup.LayoutParams params = view.getLayoutParams();
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    view.setLayoutParams(params);
                }
                
                return new ImageViewHolder(view);
            } else {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reader_page, parent, false);
                
                if (isVerticalMode) {
                    ViewGroup.LayoutParams params = view.getLayoutParams();
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    view.setLayoutParams(params);
                    
                    TextView textView = view.findViewById(R.id.pageContentTextView);
                    ViewGroup.LayoutParams textParams = textView.getLayoutParams();
                    textParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    textView.setLayoutParams(textParams);
                    
                    setReaderTextPadding(textView, true);
                }
                
                if (viewType == ReaderPage.TYPE_SCROLL_TEXT) {
                    TextView textView = view.findViewById(R.id.pageContentTextView);
                    textView.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    ScrollView scroll = new ScrollView(parent.getContext());
                    scroll.setLayoutParams(new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    scroll.addView(view, new ScrollView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    view = scroll;
                }
                return new TextViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ReaderPage page = data.get(position);
            
            View.OnTouchListener touchListener = (v, event) -> {
                if (v instanceof TextView) {
                    TextView textView = (TextView) v;
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        textView.setTag(R.id.reader_link_touch, touchesLink(textView, event));
                    }
                    if (Boolean.TRUE.equals(textView.getTag(R.id.reader_link_touch))) {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                            textView.setTag(R.id.reader_link_touch, null);
                        // A button/link owns this whole tap, including its release outside the target.
                        // Do not forward any part of it to the reader's page/scroll/menu detector.
                        textView.onTouchEvent(event);
                        return true;
                    }
                }
                boolean handled = gestureDetector != null && gestureDetector.onTouchEvent(event);
                return page.type != ReaderPage.TYPE_SCROLL_TEXT && handled;
            };
            
            if (holder instanceof CommentViewHolder) {
                CommentViewHolder commentHolder = (CommentViewHolder) holder;
                commentHolder.bind(page.chapterId);
            } else if (holder instanceof TextViewHolder) {
                TextViewHolder textHolder = (TextViewHolder) holder;
                boolean isChapterTitle = page.type == ReaderPage.TYPE_TITLE;
                float lineSpacing = UserPreferences.getLineSpacing(ReaderActivity.this);
                ReaderPagination.configure(textHolder.textView,
                        isChapterTitle ? readerTitleFontSize() : currentFontSize, lineSpacing, isChapterTitle);

                textHolder.textView.setTextColor(readerTextColor);
                setReaderTextPadding(textHolder.textView, isVerticalMode);

                if (!isVerticalMode) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) textHolder.textView.getLayoutParams();
                    lp.bottomMargin = 0;
                    textHolder.textView.setLayoutParams(lp);
                }

                Spanned rendered = page.content instanceof Spanned ? (Spanned) page.content : new SpannedString(page.content);
                int textWidth = textHolder.textView.getWidth() - textHolder.textView.getPaddingLeft() - textHolder.textView.getPaddingRight();
                if (textWidth <= 0) textWidth = getResources().getDisplayMetrics().widthPixels - readerDp(currentPageMargin * 2);
                BbCodeText.prepare(rendered, textHolder.textView.getPaint(), textWidth);
                markwon.setParsedMarkdown(textHolder.textView, rendered);
                BbCodeText.bindWidth(textHolder.textView);
                com.fimtale.utils.SpoilerSpan.bind(textHolder.textView);
                if (textHolder.itemView instanceof ScrollView) textHolder.itemView.scrollTo(0, 0);
                textHolder.textView.setOnTouchListener(touchListener);
                textHolder.itemView.setOnTouchListener(touchListener);
            } else if (holder instanceof ImageViewHolder) {
                ImageViewHolder imageHolder = (ImageViewHolder) holder;
                
                int cornerRadius = (int) (12 * holder.itemView.getContext().getResources().getDisplayMetrics().density);
                
                try {
                    Glide.with(imageHolder.imageView.getContext())
                            .load(page.content.toString())
                            .apply(RequestOptions.bitmapTransform(new RoundedCorners(cornerRadius)))
                            .placeholder(MdiIcons.drawable(ReaderActivity.this, "image-outline"))
                            .error(MdiIcons.drawable(ReaderActivity.this, "image-broken-variant"))
                            .into(imageHolder.imageView);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                imageHolder.itemView.setOnTouchListener(touchListener);
            } else if (holder instanceof LoadingViewHolder) {
                LoadingViewHolder loadingHolder = (LoadingViewHolder) holder;

                int textColor = readerTextColor;
                loadingHolder.tvLoading.setTextColor(textColor);
                if (loadingHolder.pbLoading != null) {
                    loadingHolder.pbLoading.setIndicatorColor(textColor);
                }
                
                if (page.type == ReaderPage.TYPE_LOADING) {
                    loadingHolder.tvLoading.setText(getString(R.string.common_loading_dots));
                    if (loadingHolder.pbLoading != null) loadingHolder.pbLoading.setVisibility(View.VISIBLE);
                    loadingHolder.itemView.setOnClickListener(null);
                } else if (page.type == ReaderPage.TYPE_NEXT_CHAPTER_TRIGGER) {
                    if (loadingHolder.pbLoading != null) loadingHolder.pbLoading.setVisibility(View.GONE);
                    if (isVerticalMode) {
                        loadingHolder.tvLoading.setText(getString(R.string.reader_go_next_chapter));
                        TypedValue typedValue = new TypedValue();
                        holder.itemView.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true);
                        loadingHolder.itemView.setBackgroundResource(typedValue.resourceId);
                        
                        ViewGroup.LayoutParams params = loadingHolder.itemView.getLayoutParams();
                        if (params != null) {
                            params.height = (int) (60 * holder.itemView.getContext().getResources().getDisplayMetrics().density);
                            loadingHolder.itemView.setLayoutParams(params);
                        }
                        
                        loadingHolder.itemView.setOnClickListener(v -> {
                            if (!isLoadingChapter) {
                                int nextId = getNextChapterId();
                                if (nextId != -1) {
                                    jumpToChapter(nextId);
                                } else {
                                    Toast.makeText(ReaderActivity.this, getString(R.string.reader_no_next_chapter), Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    } else {
                        loadingHolder.tvLoading.setText(getString(R.string.common_loading_dots));
                        loadingHolder.itemView.setOnClickListener(null);
                        loadingHolder.itemView.setBackground(null);
                    }
                } else if (page.type == ReaderPage.TYPE_PREV_CHAPTER_TRIGGER) {
                    if (loadingHolder.pbLoading != null) loadingHolder.pbLoading.setVisibility(View.GONE);
                    if (isVerticalMode) {
                        loadingHolder.tvLoading.setText(getString(R.string.reader_go_previous_chapter));
                        TypedValue typedValue = new TypedValue();
                        holder.itemView.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true);
                        loadingHolder.itemView.setBackgroundResource(typedValue.resourceId);

                        ViewGroup.LayoutParams params = loadingHolder.itemView.getLayoutParams();
                        if (params != null) {
                            params.height = (int) (60 * holder.itemView.getContext().getResources().getDisplayMetrics().density);
                            loadingHolder.itemView.setLayoutParams(params);
                        }

                        loadingHolder.itemView.setOnClickListener(v -> {
                            if (!isLoadingChapter) {
                                int prevId = getPrevChapterId();
                                if (prevId != -1) {
                                    jumpToChapter(prevId, true);
                                } else {
                                    Toast.makeText(ReaderActivity.this, getString(R.string.reader_no_previous_chapter), Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    } else {
                        loadingHolder.tvLoading.setText(getString(R.string.common_loading_dots));
                        loadingHolder.itemView.setOnClickListener(null);
                        loadingHolder.itemView.setBackground(null);
                    }
                }
            }
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
        
        public void updateData(List<ReaderPage> newData) {
            this.data = newData;
            notifyDataSetChanged();
        }

        class TextViewHolder extends RecyclerView.ViewHolder {
            TextView textView;

            TextViewHolder(View itemView) {
                super(itemView);
                textView = itemView.findViewById(R.id.pageContentTextView);
                textView.getViewTreeObserver().addOnPreDrawListener(() -> {
                    int position = getBindingAdapterPosition();
                    if (!isVerticalMode && !imeChangesViewport() && position != RecyclerView.NO_POSITION && position < data.size()
                            && data.get(position).type == ReaderPage.TYPE_TEXT && textView.getLayout() != null) {
                        int available = textView.getHeight() - textView.getCompoundPaddingTop() - textView.getCompoundPaddingBottom();
                        if (available > 0 && textView.getLayout().getHeight() > available) requestPageReflow();
                    }
                    return true;
                });
            }
        }

        class ImageViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;

            ImageViewHolder(View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.ivReaderImage);
            }
        }

        class LoadingViewHolder extends RecyclerView.ViewHolder {
            TextView tvLoading;
            CircularProgressIndicator pbLoading;
            LoadingViewHolder(View itemView) {
                super(itemView);
                tvLoading = itemView.findViewById(R.id.tvLoading);
                pbLoading = itemView.findViewById(R.id.pbLoading);
            }
        }
        
        class CommentViewHolder extends RecyclerView.ViewHolder {
            TextView tvChapterTitle;
            TextView tvContinueRead;
            View commentsHeader;
            View commentsButton;
            View commentsRefresh;
            View commentsList;
            View commentsSkeleton;
            View commentsStatus;
            View commentsPager;
            View commentsComposer;
            ReaderCommentsPanel commentsPanel;
            private boolean darkPalette;
            
            CommentViewHolder(View itemView) {
                super(itemView);
            }

            private void prepareView() {
                boolean dark = isReaderDark(UserPreferences.getReaderTheme(ReaderActivity.this));
                FrameLayout container = (FrameLayout) itemView;
                if (container.getChildCount() > 0 && darkPalette == dark) return;
                if (commentsPanel != null) { commentsPanel.close(); commentsPanel = null; }
                container.removeAllViews();
                View content = LayoutInflater.from(readerCommentsContext()).inflate(R.layout.item_reader_comment_page, container, false);
                content.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        isVerticalMode ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT));
                container.addView(content);
                darkPalette = dark;
                commentsHeader = itemView.findViewById(R.id.readerCommentsHeader);
                commentsButton = itemView.findViewById(R.id.readerCommentsButton);
                commentsRefresh = itemView.findViewById(R.id.readerCommentsRefreshLayout);
                tvChapterTitle = itemView.findViewById(R.id.readerCommentsTitle);
                tvContinueRead = itemView.findViewById(R.id.readerContinueRead);
                commentsList = itemView.findViewById(R.id.readerCommentsList);
                commentsSkeleton = itemView.findViewById(R.id.readerCommentsSkeleton);
                commentsStatus = itemView.findViewById(R.id.readerCommentsStatus);
                commentsPager = itemView.findViewById(R.id.readerCommentsPager);
                commentsComposer = itemView.findViewById(R.id.readerCommentComposerBar);
            }
            
            void bind(int chapterId) {
                prepareView();
                itemView.setBackgroundColor(readerBackgroundColor);
                int textColor = readerTextColor;

                if (isVerticalMode) {
                    if (commentsPanel != null) { commentsPanel.close(); commentsPanel = null; }
                    commentsHeader.setVisibility(View.GONE);
                    commentsButton.setVisibility(View.VISIBLE);
                    commentsRefresh.setVisibility(View.GONE);
                    commentsList.setVisibility(View.GONE);
                    commentsSkeleton.setVisibility(View.GONE);
                    commentsStatus.setVisibility(View.GONE);
                    commentsPager.setVisibility(View.GONE);
                    commentsComposer.setVisibility(View.GONE);
                    tvContinueRead.setVisibility(View.GONE);
                    commentsButton.setOnClickListener(v -> showReaderCommentsBottomSheet(chapterId));
                } else {
                    commentsButton.setVisibility(View.GONE);
                    commentsRefresh.setVisibility(View.VISIBLE);
                    commentsHeader.setVisibility(View.VISIBLE);
                    commentsComposer.setVisibility(View.VISIBLE);
                    tvChapterTitle.setText(getString(R.string.comments_title));
                    tvChapterTitle.setTextColor(textColor);
                    if (commentsPanel == null) {
                        commentsPanel = new ReaderCommentsPanel(ReaderActivity.this, itemView, rootTopicId);
                        commentsPanel.setWorkAuthor(workData == null || workData.getAuthorInfo() == null ? 0 : workData.getAuthorInfo().getId());
                    }
                    commentsPanel.bind(chapterId);
                }

                int nextChapterId = getNextChapterId(chapterId);
                tvContinueRead.setTextColor(textColor);
                if (isVerticalMode) {
                    tvContinueRead.setVisibility(View.GONE);
                    tvContinueRead.setOnClickListener(null);
                } else if (nextChoices(chapterId).size() > 1) {
                    tvContinueRead.setVisibility(View.VISIBLE);
                    tvContinueRead.setText(getString(R.string.reader_choose_branch));
                    tvContinueRead.setOnClickListener(v -> showBranchChoices(chapterId));
                } else if (nextChapterId != -1) {
                    tvContinueRead.setText(getString(R.string.reader_swipe_to_continue));
                    tvContinueRead.setOnClickListener(v -> jumpToChapter(nextChapterId));
                    tvContinueRead.setVisibility(View.VISIBLE);
                } else {
                    tvContinueRead.setText(getString(R.string.reader_last_chapter));
                    tvContinueRead.setOnClickListener(null);
                    tvContinueRead.setVisibility(View.VISIBLE);
                }
                
                if (!isVerticalMode) {
                    ViewGroup.LayoutParams params = tvContinueRead.getLayoutParams();
                    if (params != null) {
                        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        tvContinueRead.setLayoutParams(params);
                    }
                    tvContinueRead.setGravity(android.view.Gravity.CENTER);
                    tvContinueRead.setTextSize(14);
                }
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof CommentViewHolder) {
                CommentViewHolder commentHolder = (CommentViewHolder) holder;
                if (commentHolder.commentsPanel != null) {
                    commentHolder.commentsPanel.close();
                    commentHolder.commentsPanel = null;
                }
            }
            super.onViewRecycled(holder);
        }
    }
    
    private static double clamp01(double v) {
        if (v < 0d) return 0d;
        if (v > 1d) return 1d;
        return v;
    }
    
    private boolean shouldApplyInitialProgress(int topicId) {
        return !initialProgressApplied && initialProgress >= 0d && topicId == initialTopicId;
    }
    
    private int computeTargetPagedIndex(int topicId, double progress01) {
        if (pages == null || pages.isEmpty()) return 0;
        int start = chapterStartIndex(pages, topicId);
        int last = chapterEndIndex(pages, topicId) - start;
        return start + (int) Math.round(clamp01(progress01) * last);
    }
    
    private VerticalPosition verticalPositionForProgress(int topicId, double progress01) {
        int start = chapterStartIndex(verticalPages, topicId);
        int end = chapterEndIndex(verticalPages, topicId);
        int startOffset = paragraphStartOffsets.get(start);
        double offset = startOffset + clamp01(progress01) * (paragraphStartOffsets.get(end) - startOffset);
        int position = start;
        while (position < end && paragraphStartOffsets.get(position + 1) <= offset) position++;
        int weight = position < end ? paragraphStartOffsets.get(position + 1) - paragraphStartOffsets.get(position) : 0;
        double fraction = weight > 0 ? (offset - paragraphStartOffsets.get(position)) / weight : 0;
        return new VerticalPosition(topicId, position - start, 0, fraction);
    }
    
    private List<TopicDetailResponse.ChapterEdge> nextChoices() {
        return com.fimtale.model.ChapterNavigation.choices(workData, currentTopicId);
    }
    private List<TopicDetailResponse.ChapterEdge> nextChoices(int chapterId) {
        return com.fimtale.model.ChapterNavigation.choices(workData, chapterId);
    }
    private int getNextChapterId() {
        return nextChapterId(currentTopicId);
    }
    private int getNextChapterId(int chapterId) {
        return nextChapterId(chapterId);
    }
    private int getPrevChapterId() {
        return previousChapterId(currentTopicId);
    }
    private void showBranchChoices() {
        showBranchChoices(currentTopicId);
    }
    private void showBranchChoices(int chapterId) {
        List<TopicDetailResponse.ChapterEdge> choices = nextChoices(chapterId);
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            TopicDetailResponse.ChapterEdge edge = choices.get(i);
            labels[i] = edge.label == null || edge.label.isEmpty() ? getString(R.string.reader_continue) : edge.label;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(getString(R.string.reader_choose_branch))
                .setItems(labels, (dialog, which) -> jumpToChapter(choices.get(which).to == null ? 0 : choices.get(which).to)).show();
    }

    private class ChapterListAdapter extends RecyclerView.Adapter<ChapterListAdapter.ViewHolder> {
        private final List<ChapterMenuItem> displayList = new ArrayList<>();
        private int highlightedChapterId = -1;

        public void updateData(List<ChapterMenuItem> newData) {
            displayList.clear();
            if (newData != null) displayList.addAll(newData);
            notifyDataSetChanged();
        }

        @NonNull
        @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reader_chapter, parent, false);
            return new ViewHolder(view);
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ChapterMenuItem item = displayList.get(position);
            boolean current = item.getId() == currentTopicId;
            int accent = readerAccent();
            int secondary = ColorUtils.blendARGB(readerBackgroundColor, readerTextColor, 0.6f);
            holder.number.setText(getString(R.string.reader_chapter_number, position + 1));
            holder.number.setTextColor(current ? accent : secondary);
            holder.title.setText(item.getTitle());
            holder.title.setTextColor(current ? accent : readerTextColor);
            holder.title.setTypeface(Typeface.DEFAULT, current ? Typeface.BOLD : Typeface.NORMAL);
            holder.current.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            holder.current.setImageTintList(ColorStateList.valueOf(accent));
            holder.itemView.setSelected(current);
            holder.itemView.setContentDescription(current
                    ? getString(R.string.reader_current_chapter, item.getTitle()) : item.getTitle());
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(readerDp(12));
            background.setColor(current ? ColorUtils.blendARGB(readerBackgroundColor, accent, 0.12f) : readerBackgroundColor);
            holder.itemView.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(readerTextColor, 30)), background, null));
            holder.itemView.setOnClickListener(v -> jumpToChapter(item.getId()));
        }

        @Override public int getItemCount() { return displayList.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView number;
            final TextView title;
            final ImageView current;
            ViewHolder(View itemView) {
                super(itemView);
                number = itemView.findViewById(R.id.readerChapterNumber);
                title = itemView.findViewById(R.id.readerChapterName);
                current = itemView.findViewById(R.id.readerChapterCurrent);
            }
        }
    }
}
