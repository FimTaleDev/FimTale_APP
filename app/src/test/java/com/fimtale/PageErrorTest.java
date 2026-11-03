package com.fimtale;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.network.ApiDataConverter;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.fimtale.ui.PageErrorView;
import com.fimtale.ui.PullRefreshLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.fimtale.ResourceApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PageErrorTest {
    private Context context;
    private Field service, updateService;
    private Object originalApi, originalUpdate;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile boolean failing = true;
    private volatile java.util.concurrent.CountDownLatch responseGate;
    private String workContent = "正文";
    private ActivityController<? extends Activity> controller;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("fimtale_session", 0).edit().clear()
                .putString("token", "error-fixture").putString("user_id", "9").putString("user_name", "测试用户").commit();
        Field factory = ViewModelProvider.AndroidViewModelFactory.class.getDeclaredField("sInstance");
        factory.setAccessible(true); factory.set(null, null);
        service = RetrofitClient.class.getDeclaredField("service"); service.setAccessible(true); originalApi = service.get(null);
        updateService = RetrofitClient.class.getDeclaredField("updateService"); updateService.setAccessible(true); originalUpdate = updateService.get(null);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request(); requests.add(request);
            String path = request.url().encodedPath();
            String data = path.endsWith("get_timeline") || path.endsWith("get_review_entries")
                    || path.endsWith("list_tags") || path.endsWith("get_active_sessions") ? "[]" : "{\"items\":[],\"total\":0}";
            if (path.endsWith("get_user_auth")) data = "{\"user_id\":9,\"qualify_status\":1}";
            if (path.endsWith("get_work")) data = "{\"work\":{\"id\":42,\"title\":\"测试文章\",\"preface\":"
                    + new com.google.gson.Gson().toJson(workContent) + "},\"chapters\":[]}";
            if (path.endsWith("get_timeline_update_count")) data = "0";
            boolean auxiliary = path.equals("/update/") || path.endsWith("get_user_auth") || path.endsWith("get_timeline_update_count");
            java.util.concurrent.CountDownLatch gate = responseGate;
            if (!auxiliary && gate != null) {
                try { if (!gate.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Fixture timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            }
            int code = failing && !auxiliary ? 503 : 200;
            return new okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                    .body(ResponseBody.create(MediaType.get("application/json"), "{\"data\":" + data + ",\"msg\":\"服务器暂时不可用\"}")).build();
        }).build();
        FimTaleApiService api = new Retrofit.Builder().baseUrl(SiteUrls.API).client(client)
                .callbackExecutor(command -> new Handler(Looper.getMainLooper()).post(command))
                .addConverterFactory(new ApiDataConverter()).addConverterFactory(GsonConverterFactory.create())
                .build().create(FimTaleApiService.class);
        service.set(null, api); updateService.set(null, api);
    }
    @After public void cleanup() throws Exception {
        if (responseGate != null) responseGate.countDown();
        if (controller != null) controller.pause().stop().destroy();
        service.set(null, originalApi); updateService.set(null, originalUpdate);
    }
    private void await(BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20);
        }
        assertTrue("Expected page state", condition.getAsBoolean());
    }
    private PageErrorView shownError(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof PageErrorView) {
            TextView title = view.findViewById(R.id.pageErrorTitle);
            return title.getText().equals(context.getText(R.string.page_error_title)) ? (PageErrorView) view : null;
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            PageErrorView error = shownError(((ViewGroup) view).getChildAt(i));
            if (error != null) return error;
        }
        return null;
    }
    private <T extends Activity> T launch(Class<T> type, Intent intent) {
        ActivityController<T> active = Robolectric.buildActivity(type, intent).setup();
        controller = active; return active.get();
    }
    private void assertPageError(Class<? extends Activity> type, Intent intent) throws Exception {
        Activity activity = launch(type, intent);
        View root = activity.findViewById(android.R.id.content);
        await(() -> shownError(root) != null);
        PageErrorView error = shownError(root);
        assertEquals("加载失败", ((TextView) error.findViewById(R.id.pageErrorTitle)).getText().toString());
        int before = requests.size(); error.findViewById(R.id.pageErrorRetry).performClick();
        await(() -> requests.size() > before && shownError(root) != null);
    }

    @Test public void wrapperKeepsRefreshChildAndContentWhileRetryFiresOnce() {
        Context themed = new ContextThemeWrapper(context, R.style.Theme_Fimtale);
        FrameLayout host = new FrameLayout(themed);
        PullRefreshLayout refresh = new PullRefreshLayout(themed, null);
        TextView content = new TextView(themed); content.setText("已加载的内容");
        refresh.addView(content); host.addView(refresh);
        PageErrorView error = PageErrorView.wrap(content);
        assertSame(refresh, content.getParent());
        AtomicInteger retries = new AtomicInteger(); error.show(null, retries::incrementAndGet, true);
        error.findViewById(R.id.pageErrorRetry).performClick();
        error.findViewById(R.id.pageErrorRetry).performClick();
        assertEquals(1, retries.get()); assertEquals(View.GONE, error.getVisibility());
        assertEquals("已加载的内容", content.getText().toString());
        error.show("离线", retries::incrementAndGet, true);
        error.findViewById(R.id.pageErrorContinue).performClick();
        error.show("离线", retries::incrementAndGet, true);
        assertEquals(View.GONE, error.getVisibility());
        error.hide(); error.show("离线", retries::incrementAndGet, true);
        assertEquals(View.VISIBLE, error.getVisibility());
    }
    @Test public void emptyAndLoginStatesReplaceErrorActionsAndRestoreContentAccessibility() {
        Context themed = new ContextThemeWrapper(context, R.style.Theme_Fimtale);
        FrameLayout host = new FrameLayout(themed);
        TextView content = new TextView(themed);
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        host.addView(content);
        PageErrorView state = PageErrorView.wrap(content);
        AtomicInteger retries = new AtomicInteger(), refreshes = new AtomicInteger(), logins = new AtomicInteger();
        state.show("离线", retries::incrementAndGet, true);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, content.getImportantForAccessibility());
        state.showEmpty(context.getString(R.string.history_empty), refreshes::incrementAndGet);
        assertEquals(context.getString(R.string.page_empty_title), ((TextView) state.findViewById(R.id.pageErrorTitle)).getText().toString());
        assertEquals(View.GONE, state.findViewById(R.id.pageErrorContinue).getVisibility());
        state.findViewById(R.id.pageErrorRetry).performClick();
        state.findViewById(R.id.pageErrorRetry).performClick();
        assertEquals(0, retries.get()); assertEquals(1, refreshes.get());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, content.getImportantForAccessibility());
        state.showLogin(context.getString(R.string.profile_login_message), logins::incrementAndGet);
        assertEquals(context.getString(R.string.common_login), ((TextView) state.findViewById(R.id.pageErrorRetry)).getText().toString());
        state.findViewById(R.id.pageErrorRetry).performClick();
        assertEquals(1, logins.get());
        state.showEmpty(context.getString(R.string.work_empty));
        assertEquals(View.GONE, state.findViewById(R.id.pageErrorRetry).getVisibility());
        state.findViewById(R.id.pageErrorRetry).performClick();
        assertEquals(View.VISIBLE, state.getVisibility());
    }
    @Test public void emptyListStaysInsidePullRefreshWhileErrorOverlayStaysOutside() {
        Context themed = new ContextThemeWrapper(context, R.style.Theme_Fimtale);
        FrameLayout host = new FrameLayout(themed);
        TextView list = new TextView(themed);
        PageErrorView empty = new PageErrorView(themed);
        host.addView(list); host.addView(empty);
        View surface = PageErrorView.withEmptyState(list, empty);
        PageErrorView error = PageErrorView.wrap(surface);
        com.fimtale.ui.PullToRefresh.attach(surface, () -> {}, () -> true);
        assertSame(list.getParent(), empty.getParent());
        assertTrue(surface.getParent() instanceof PullRefreshLayout);
        assertSame(((View) surface.getParent()).getParent(), error.getParent());
        empty.showEmpty(context.getString(R.string.history_empty));
        assertEquals(View.VISIBLE, empty.getVisibility());
        assertEquals(View.GONE, error.getVisibility());
    }
    @Test public void homeErrorRetainsNavigationAndRetryCanBecomeSuccessfulEmptyContent() throws Exception {
        MainActivity activity = launch(MainActivity.class, new Intent(context, MainActivity.class));
        View root = activity.findViewById(android.R.id.content);
        await(() -> shownError(root) != null);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.bottom_navigation).getVisibility());
        failing = false; shownError(root).findViewById(R.id.pageErrorRetry).performClick();
        await(() -> shownError(root) == null && activity.findViewById(R.id.loadingSkeleton).getVisibility() == View.GONE);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.homeList).getVisibility());
    }

    @Test public void homePullKeepsContentAndSpinnerUntilSuccessOrFailure() throws Exception {
        failing = false;
        MainActivity activity = launch(MainActivity.class, new Intent(context, MainActivity.class));
        await(() -> activity.findViewById(R.id.loadingSkeleton).getVisibility() == View.GONE);
        controller.visible(); layoutReader(activity);
        PullRefreshLayout refresh = activity.findViewById(R.id.swipeRefreshLayout);
        View list = activity.findViewById(R.id.homeList);
        for (boolean failure : new boolean[]{false, true}) {
            responseGate = new java.util.concurrent.CountDownLatch(1);
            refresh.onNestedScrollAccepted(list, list, androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL, androidx.core.view.ViewCompat.TYPE_TOUCH);
            refresh.onNestedScroll(list, 0, 0, 0, -600, androidx.core.view.ViewCompat.TYPE_TOUCH, new int[2]);
            refresh.onStopNestedScroll(list, androidx.core.view.ViewCompat.TYPE_TOUCH);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
            assertTrue(refresh.isRefreshing());
            assertEquals(View.VISIBLE, list.getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.loadingSkeleton).getVisibility());
            assertTrue(list.getTranslationY() > 0);
            failing = failure; responseGate.countDown(); responseGate = null;
            await(() -> !refresh.isRefreshing());
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
            assertEquals(0f, list.getTranslationY(), .01f);
            assertEquals(failure, shownError(activity.findViewById(android.R.id.content)) != null);
        }
    }
    @Test public void anEmptyInlineSectionStillMeasuresAnAccessibleErrorPanel() {
        Context themed = new ContextThemeWrapper(context, R.style.Theme_Fimtale);
        FrameLayout host = new FrameLayout(themed);
        TextView content = new TextView(themed); content.setVisibility(View.GONE);
        host.addView(content, new FrameLayout.LayoutParams(-1, -2));
        PageErrorView error = PageErrorView.wrap(content); error.show(null, () -> {});
        int width = Math.round(360 * context.getResources().getDisplayMetrics().density);
        int height = Math.round(640 * context.getResources().getDisplayMetrics().density);
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());
        assertTrue(error.getHeight() > 0);
        assertTrue(error.getHeight() <= height);
        assertTrue(error.findViewById(R.id.pageErrorRetry).getHeight() > 0);
    }
    @Test public void timelineAndArticlesAndProfileShareTheSameError() throws Exception {
        MainActivity activity = launch(MainActivity.class, new Intent(context, MainActivity.class));
        BottomNavigationView nav = activity.findViewById(R.id.bottom_navigation);
        for (int id : new int[]{R.id.nav_timeline, R.id.nav_article, R.id.nav_profile}) {
            nav.setSelectedItemId(id); activity.getSupportFragmentManager().executePendingTransactions();
            await(() -> shownError(activity.findViewById(R.id.fragment_container)) != null);
        }
    }
    @Test public void historyUsesRetryScreen() throws Exception { assertPageError(HistoryActivity.class, new Intent(context, HistoryActivity.class)); }
    @Test public void favoritesUseRetryScreen() throws Exception { assertPageError(FavoritesActivity.class, new Intent(context, FavoritesActivity.class)); }
    @Test public void tagsUseRetryScreen() throws Exception { assertPageError(TagListActivity.class, new Intent(context, TagListActivity.class)); }
    @Test public void tagArticlesUseRetryScreen() throws Exception { assertPageError(TagArticlesActivity.class, new Intent(context, TagArticlesActivity.class).putExtra(TagArticlesActivity.EXTRA_WORK_TYPE, 3)); }
    @Test public void searchUsesRetryScreen() throws Exception { assertPageError(SearchActivity.class, new Intent(context, SearchActivity.class).putExtra(SearchActivity.EXTRA_QUERY, "小马")); }
    @Test public void profileDetailsUseRetryScreen() throws Exception { assertPageError(UserDetailActivity.class, new Intent(context, UserDetailActivity.class).putExtra(UserDetailActivity.EXTRA_USERNAME, "作者")); }
    @Test public void workDetailsUseRetryScreen() throws Exception { assertPageError(TopicDetailActivity.class, new Intent(context, TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, 42)); }
    @Test public void workReadingActionsReturnAfterScrollingOutOfComments() throws Exception {
        failing = false;
        TopicDetailActivity activity = launch(TopicDetailActivity.class,
                new Intent(context, TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, 42));
        await(() -> activity.findViewById(R.id.detailLoadingSkeleton).getVisibility() == View.GONE);
        controller.visible();
        float density = context.getResources().getDisplayMetrics().density;
        activity.findViewById(R.id.detailContentTextView).setMinimumHeight(Math.round(1600 * density));
        View comments = activity.findViewById(R.id.workCommentsSection);
        comments.setMinimumHeight(Math.round(500 * density));
        View root = activity.getWindow().getDecorView();
        int width = Math.round(360 * density), height = Math.round(800 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        androidx.core.widget.NestedScrollView scroll = activity.findViewById(R.id.scrollView);
        View reading = activity.findViewById(R.id.readingActionsBar);
        View composer = activity.findViewById(R.id.commentComposerBar);
        TextView draft = activity.findViewById(R.id.commentComposerInput);
        draft.setText("尚未发送的评论");
        for (int i = 0; i < 3; i++) {
            scroll.scrollTo(0, comments.getTop());
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertEquals(View.GONE, reading.getVisibility());
            assertEquals(View.VISIBLE, composer.getVisibility());
            // A fast scroll can skip the visibility threshold and hide the entire comments block.
            scroll.scrollTo(0, 0);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertFalse(comments.getGlobalVisibleRect(new android.graphics.Rect()));
            assertEquals(View.VISIBLE, reading.getVisibility());
            assertEquals(1f, reading.getAlpha(), 0.01f);
            assertEquals(View.GONE, composer.getVisibility());
            assertEquals("尚未发送的评论", draft.getText().toString());
        }
        activity.findViewById(R.id.startReadingButton).performClick();
        Intent reader = shadowOf(activity).getNextStartedActivity();
        assertEquals(ReaderActivity.class.getName(), reader.getComponent().getClassName());
        assertEquals(42, reader.getIntExtra(ReaderActivity.EXTRA_WORK_ID, -1));
    }

    @Test public void detailTableFitsItsPaddedArticleColumn() throws Exception {
        failing = false;
        workContent = "[quote][table][tr]" + ("[td]" + "较长的表格内容".repeat(10) + "[/td]").repeat(3) + "[/tr][/table][/quote]";
        TopicDetailActivity activity = launch(TopicDetailActivity.class,
                new Intent(context, TopicDetailActivity.class).putExtra(TopicDetailActivity.EXTRA_TOPIC_ID, 42));
        await(() -> activity.findViewById(R.id.detailLoadingSkeleton).getVisibility() == View.GONE);
        controller.visible(); layoutReader(activity); layoutReader(activity);
        TextView view = activity.findViewById(R.id.detailContentTextView);
        android.text.Spanned text = (android.text.Spanned) view.getText();
        io.noties.markwon.ext.tables.TableRowSpan row = text.getSpans(0, text.length(), io.noties.markwon.ext.tables.TableRowSpan.class)[0];
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(600, view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        int start = text.getSpanStart(row), end = text.getSpanEnd(row);
        assertTrue(view.getLayout().getPrimaryHorizontal(start) + row.getSize(view.getPaint(), text, start, end, null)
                <= view.getWidth() - view.getTotalPaddingLeft() - view.getTotalPaddingRight());
        bitmap.recycle();
    }
    @Test public void accountSessionsUseRetryScreen() throws Exception { assertPageError(AccountSessionsActivity.class, new Intent(context, AccountSessionsActivity.class)); }
    @Test public void filtersUseRetryScreen() throws Exception { assertPageError(ContentFiltersActivity.class, new Intent(context, ContentFiltersActivity.class)); }
    @Test public void reviewsUseRetryScreen() throws Exception { assertPageError(ReviewQueueActivity.class, new Intent(context, ReviewQueueActivity.class)); }
    @Test public void draftsUseRetryScreen() throws Exception { assertPageError(DraftsActivity.class, new Intent(context, DraftsActivity.class)); }
    @Test public void editorLoadFailureUsesRetryScreen() throws Exception { assertPageError(EditorActivity.class, EditorActivity.workIntent(context, 42)); }
    @Test public void readerLoadFailureUsesRetryScreen() throws Exception { assertPageError(ReaderActivity.class,
            new Intent(context, ReaderActivity.class).putExtra(ReaderActivity.EXTRA_WORK_ID, 42).putExtra(ReaderActivity.EXTRA_CHAPTER_ID, 101)); }

    @Test public void readerCollapseOpensASheetWithoutChangingEitherReadingMode() throws Exception {
        failing = false;
        workContent = "前文[collapse=展开标题]" + "长篇折叠正文。\n".repeat(150) + "[/collapse]尾文";
        ReaderActivity activity = launch(ReaderActivity.class, new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_WORK_ID, 42).putExtra(ReaderActivity.EXTRA_CHAPTER_ID, 0));
        Field ready = ReaderActivity.class.getDeclaredField("contentReady"); ready.setAccessible(true);
        await(() -> {
            try { return ready.getBoolean(activity); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
        });
        controller.visible();
        java.lang.reflect.Method mode = ReaderActivity.class.getDeclaredMethod("updatePageMode", boolean.class);
        mode.setAccessible(true); mode.invoke(activity, false);
        layoutReader(activity);
        int collapsedPages = readerPages(activity, "pages").size();
        String originalPages = readerText(activity, "pages");
        TextView titleView = collapseView(activity.findViewById(android.R.id.content));
        assertNotNull(titleView);
        clickCollapse(titleView);
        layoutReader(activity);
        android.app.Dialog sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(sheet instanceof com.google.android.material.bottomsheet.BottomSheetDialog);
        assertTrue(((TextView) sheet.findViewById(R.id.collapseSheetContent)).getText().toString().contains("长篇折叠正文"));
        assertEquals(collapsedPages, readerPages(activity, "pages").size());
        assertEquals(originalPages, readerText(activity, "pages"));
        sheet.dismiss();
        java.lang.reflect.Method font = ReaderActivity.class.getDeclaredMethod("updateFontSize", float.class);
        Field fontSize = ReaderActivity.class.getDeclaredField("currentFontSize"); fontSize.setAccessible(true);
        fontSize.setFloat(activity, 24f);
        font.setAccessible(true); font.invoke(activity, 24f);
        mode.invoke(activity, true); layoutReader(activity);
        assertFalse(readerText(activity, "pages").contains("长篇折叠正文"));
        titleView = collapseView(activity.findViewById(android.R.id.content));
        assertNotNull(titleView);
        clickCollapse(titleView); layoutReader(activity);
        sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(sheet instanceof com.google.android.material.bottomsheet.BottomSheetDialog);
        assertTrue(sheet.isShowing());
        assertFalse(readerText(activity, "pages").contains("长篇折叠正文"));
        assertFalse(readerText(activity, "verticalPages").contains("长篇折叠正文"));
        assertTrue(readerText(activity, "pages").contains("尾文"));
        sheet.cancel();
        assertFalse(sheet.isShowing());
        mode.invoke(activity, false); layoutReader(activity);
        assertEquals(collapsedPages, readerPages(activity, "pages").size());
    }

    private void layoutReader(Activity activity) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 800);
    }

    @Test @Config(qualifiers = "w360dp-h800dp")
    public void collapseTapsWinOverAllReaderTapZones() throws Exception {
        failing = false;
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("has_shown_reader_guide", true).putBoolean("reader_is_vertical", false).commit();
        workContent = ("[collapse=" + "展开按钮标题".repeat(6) + "]折叠正文[/collapse]\n").repeat(30);
        ReaderActivity activity = launch(ReaderActivity.class, new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_WORK_ID, 42).putExtra(ReaderActivity.EXTRA_CHAPTER_ID, 0));
        Field ready = ReaderActivity.class.getDeclaredField("contentReady"); ready.setAccessible(true);
        await(() -> { try { return ready.getBoolean(activity); } catch (Exception e) { throw new AssertionError(e); } });
        controller.visible();
        java.lang.reflect.Method mode = ReaderActivity.class.getDeclaredMethod("updatePageMode", boolean.class);
        mode.setAccessible(true);
        Field menu = ReaderActivity.class.getDeclaredField("isMenuVisible"); menu.setAccessible(true);
        androidx.viewpager2.widget.ViewPager2 pager = activity.findViewById(R.id.viewPager);
        androidx.recyclerview.widget.RecyclerView scroll = activity.findViewById(R.id.recyclerView);
        for (boolean vertical : new boolean[]{false, true}) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("reader_is_vertical", vertical).commit();
            mode.invoke(activity, vertical);
            layoutReader(activity);
            if (!vertical) {
                List<?> pages = readerPages(activity, "pages");
                for (int i = 0; i < pages.size(); i++) {
                    Field content = pages.get(i).getClass().getDeclaredField("content"); content.setAccessible(true);
                    Object text = content.get(pages.get(i));
                    if (text instanceof android.text.Spanned && ((android.text.Spanned) text)
                            .getSpans(0, ((android.text.Spanned) text).length(), com.fimtale.utils.CollapseSpan.class).length > 0) {
                        pager.setCurrentItem(i, false); break;
                    }
                }
                layoutReader(activity);
            }
            for (int zone = 0; zone < 3; zone++) {
                float[] point = readerButtonPoint(activity.findViewById(android.R.id.content), vertical, zone);
                assertNotNull("Visible button in " + (vertical ? "vertical" : "paged") + " zone " + zone, point);
                int page = pager.getCurrentItem();
                int scrollOffset = scroll.computeVerticalScrollOffset();
                long now = android.os.SystemClock.uptimeMillis();
                for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                    android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + 10, action, point[0], point[1], 0);
                    activity.dispatchTouchEvent(event); event.recycle();
                }
                layoutReader(activity);
                android.app.Dialog sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog();
                assertTrue("Button must open its sheet", sheet instanceof com.google.android.material.bottomsheet.BottomSheetDialog && sheet.isShowing());
                assertFalse("Reader menu must stay closed", menu.getBoolean(activity));
                assertEquals("No page tap", page, pager.getCurrentItem());
                assertEquals("No scroll tap", scrollOffset, scroll.computeVerticalScrollOffset());
                sheet.dismiss(); layoutReader(activity);
            }
        }
    }

    private float[] readerButtonPoint(View view, boolean vertical, int zone) {
        if (!view.isShown()) return null;
        if (view instanceof TextView) {
            TextView widget = (TextView) view;
            if (widget.getText() instanceof android.text.Spanned && widget.getLayout() != null) {
                android.text.Spanned text = (android.text.Spanned) widget.getText();
                android.graphics.Rect visible = new android.graphics.Rect();
                int[] location = new int[2]; widget.getLocationOnScreen(location);
                if (widget.getGlobalVisibleRect(visible)) for (com.fimtale.utils.CollapseButtonSpan button : text.getSpans(0, text.length(), com.fimtale.utils.CollapseButtonSpan.class)) {
                    int start = text.getSpanStart(button), end = text.getSpanEnd(button);
                    android.text.Layout layout = widget.getLayout();
                    android.graphics.Paint.FontMetricsInt metrics = new android.graphics.Paint.FontMetricsInt();
                    int width = button.getSize(widget.getPaint(), text, start, end, metrics);
                    float x = location[0] + widget.getTotalPaddingLeft() + layout.getPrimaryHorizontal(start)
                            + width * (vertical ? .8f : new float[]{.1f, .5f, .9f}[zone]);
                    float y = location[1] + widget.getTotalPaddingTop() + layout.getLineBaseline(layout.getLineForOffset(start)) + metrics.ascent / 2f;
                    float fraction = vertical ? y / 800 : x / 360;
                    int actualZone = fraction < .3f ? 0 : fraction > .7f ? 2 : 1;
                    if (visible.contains((int) x, (int) y) && actualZone == zone) return new float[]{x, y};
                }
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            float[] result = readerButtonPoint(((ViewGroup) view).getChildAt(i), vertical, zone);
            if (result != null) return result;
        }
        return null;
    }
    private TextView collapseView(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && ((TextView) view).getText() instanceof android.text.Spanned) {
            android.text.Spanned text = (android.text.Spanned) ((TextView) view).getText();
            if (text.getSpans(0, text.length(), com.fimtale.utils.CollapseSpan.class).length > 0) return (TextView) view;
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = collapseView(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private void clickCollapse(TextView view) {
        android.text.Spanned text = (android.text.Spanned) view.getText();
        text.getSpans(0, text.length(), com.fimtale.utils.CollapseSpan.class)[0].onClick(view);
    }
    private List<?> readerPages(ReaderActivity activity, String name) throws Exception {
        Field field = ReaderActivity.class.getDeclaredField(name); field.setAccessible(true);
        return (List<?>) field.get(activity);
    }
    private String readerText(ReaderActivity activity, String name) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Object page : readerPages(activity, name)) {
            Field field = page.getClass().getDeclaredField("content"); field.setAccessible(true);
            Object content = field.get(page); if (content != null) result.append(content);
        }
        return result.toString();
    }
    @Test public void siteOnlyShowsMainFrameFailuresAndRetriesTheFailedUrl() {
        SiteActivity activity = launch(SiteActivity.class, new Intent(context, SiteActivity.class).putExtra(SiteActivity.EXTRA_PATH, "/channel/7"));
        android.webkit.WebView web = activity.findViewById(R.id.site_webview);
        android.webkit.WebViewClient client = web.getWebViewClient();
        String url = SiteUrls.SITE + "/channel/7";
        client.onPageStarted(web, url, null);
        android.webkit.WebResourceResponse response = new android.webkit.WebResourceResponse("text/html", "UTF-8", 503,
                "Unavailable", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
        client.onReceivedHttpError(web, webRequest(url + "/image.png", false), response);
        View root = activity.findViewById(android.R.id.content);
        assertNull(shownError(root));
        client.onReceivedHttpError(web, webRequest(url, true), response);
        client.onPageFinished(web, url);
        PageErrorView error = shownError(root); assertNotNull(error);
        error.findViewById(R.id.pageErrorRetry).performClick();
        assertEquals(url, shadowOf(web).getLastLoadedUrl());
    }
    private android.webkit.WebResourceRequest webRequest(String url, boolean main) {
        return new android.webkit.WebResourceRequest() {
            @Override public android.net.Uri getUrl() { return android.net.Uri.parse(url); }
            @Override public boolean isForMainFrame() { return main; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public java.util.Map<String, String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
        };
    }
}
