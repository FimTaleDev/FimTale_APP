package com.fimtale;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.fimtale.network.ApiDataConverter;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.google.android.material.tabs.TabLayout;
import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
@Config(sdk = 34, application = ResourceApplication.class, qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReaderProgressTest {
    private static final String CONTENT = "长段落正文。".repeat(500) + "\n\n"
            + ("后续段落文字。".repeat(180) + "\n\n").repeat(6);
    private String fixtureContent = CONTENT;
    private Context context;
    private Field service;
    private Object originalService;
    private ActivityController<ReaderActivity> controller;
    private ReaderActivity activity;
    private RecyclerView reader;
    private LinearLayoutManager layout;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("fimtale_session", 0).edit().clear().commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("has_shown_reader_guide", true).putBoolean("reader_is_vertical", true).commit();
        service = RetrofitClient.class.getDeclaredField("service");
        service.setAccessible(true);
        originalService = service.get(null);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String data = chain.request().url().encodedPath().endsWith("get_work")
                    ? "{\"work\":{\"id\":42,\"title\":\"第一章\",\"preface\":"
                            + new Gson().toJson(fixtureContent) + "},\"chapters\":[]}"
                    : "{\"items\":[],\"total\":0}";
            return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Fixture")
                    .body(ResponseBody.create(MediaType.get("application/json"), "{\"data\":" + data + "}")).build();
        }).build();
        service.set(null, new Retrofit.Builder().baseUrl(SiteUrls.API).client(client)
                .callbackExecutor(command -> new Handler(Looper.getMainLooper()).post(command))
                .addConverterFactory(new ApiDataConverter()).addConverterFactory(GsonConverterFactory.create())
                .build().create(FimTaleApiService.class));
    }

    @After public void cleanup() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        service.set(null, originalService);
    }

    private void launch(double progress) throws Exception {
        Intent intent = new Intent(context, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_WORK_ID, 42).putExtra(ReaderActivity.EXTRA_CHAPTER_ID, 0);
        if (progress >= 0) intent.putExtra(ReaderActivity.EXTRA_INITIAL_PROGRESS, progress);
        controller = Robolectric.buildActivity(ReaderActivity.class, intent).setup().visible();
        activity = controller.get();
        reader = activity.findViewById(R.id.recyclerView);
        layout = (LinearLayoutManager) reader.getLayoutManager();
        long deadline = System.currentTimeMillis() + 5000;
        while (!(boolean) field(activity, "contentReady") && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("Fixture chapter loaded", (boolean) field(activity, "contentReady"));
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 360, 800);
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }

    private void scrollTo(int position, int offset) {
        layout.scrollToPositionWithOffset(position, offset);
        settle();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ReaderActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, args);
    }

    private List<?> rows() throws Exception { return (List<?>) field(activity, "verticalPages"); }
    private double progress() throws Exception { return (double) field(activity, "currentProgress"); }
    private String label() { return ((TextView) activity.findViewById(R.id.tvChapterProgress)).getText().toString(); }

    private void assertConsistentProgress() throws Exception {
        double progress = progress();
        assertTrue(progress >= 0 && progress <= 1);
        assertEquals(activity.getString(R.string.reader_percent, progress * 100), label());
        assertEquals(Math.round(progress * 1000), ((ProgressBar) activity.findViewById(R.id.scrollProgressBar)).getProgress());
    }

    private void insertChapter(int index, int id, String title) throws Exception {
        insertChapter(index, id, title, "相邻章节文字。".repeat(180) + "\n\n" + CONTENT);
    }

    @SuppressWarnings("unchecked")
    private void insertChapter(int index, int id, String title, String content) throws Exception {
        Object chapter = invoke("createLoadedChapter", new Class<?>[]{int.class, String.class, String.class},
                id, title, content);
        ((List<Object>) field(activity, "loadedChapters")).add(index, chapter);
        invoke("rebuildReaderContent", new Class<?>[]{boolean.class}, true);
    }

    private int firstRowOf(int chapterId) throws Exception {
        List<?> rows = rows();
        for (int i = 0; i < rows.size(); i++) if ((int) field(rows.get(i), "chapterId") == chapterId) return i;
        throw new AssertionError("Missing fixture chapter " + chapterId);
    }

    @Test public void emptyChapterShowsSharedStateInBothModesAndKeepsItsCommentsPage() throws Exception {
        fixtureContent = "  \n";
        launch(-1);
        View empty = layout.findViewByPosition(1);
        assertTrue(empty instanceof com.fimtale.ui.PageErrorView);
        assertEquals(context.getString(R.string.page_empty_title),
                ((TextView) empty.findViewById(R.id.pageErrorTitle)).getText().toString());
        assertEquals(context.getString(R.string.common_no_content),
                ((TextView) empty.findViewById(R.id.pageErrorMessage)).getText().toString());

        ((TabLayout) activity.findViewById(R.id.tabPageMode)).getTabAt(0).select();
        settle();
        ViewPager2 pager = activity.findViewById(R.id.viewPager);
        assertEquals(2, pager.getAdapter().getItemCount());
        RecyclerView pagerList = (RecyclerView) pager.getChildAt(0);
        View page = pagerList.findViewHolderForAdapterPosition(0).itemView;
        assertTrue(page instanceof com.fimtale.ui.PageErrorView);
        assertEquals(context.getString(R.string.page_empty_title),
                ((TextView) page.findViewById(R.id.pageErrorTitle)).getText().toString());
        pager.setCurrentItem(1, false);
        settle();
        assertNotNull(pagerList.findViewHolderForAdapterPosition(1).itemView.findViewById(R.id.readerCommentsRoot));
    }

    @Test public void longParagraphProgressMovesContinuouslyAndMatchesTheBar() throws Exception {
        launch(-1);
        scrollTo(1, -100);
        int paragraph = layout.findFirstVisibleItemPosition();
        double before = progress();
        for (int i = 0; i < 12; i++) {
            reader.scrollBy(0, 40);
            settle();
            assertEquals("Still reading the same long paragraph", paragraph, layout.findFirstVisibleItemPosition());
            assertTrue("Progress must advance inside a paragraph", progress() > before);
            assertConsistentProgress();
            before = progress();
        }
        reader.scrollBy(0, -120);
        settle();
        assertTrue("Scrolling back must reduce progress", progress() < before);
        assertConsistentProgress();
    }

    @Test public void hiddenPagerCannotReplaceTheVisiblePercentageWithPageNumbers() throws Exception {
        launch(-1);
        scrollTo(2, -100);
        String before = label();
        double saved = progress();
        ViewPager2 pager = activity.findViewById(R.id.viewPager);
        pager.setCurrentItem(pager.getCurrentItem() + 1, false);
        settle();
        assertEquals(before, label());
        assertEquals(saved, progress(), 0.000001);
        assertConsistentProgress();
    }

    @Test public void adjacentPreloadsKeepTheSameParagraphAndPaddedOffset() throws Exception {
        launch(-1);
        scrollTo(3, -83);
        int first = layout.findFirstVisibleItemPosition();
        Object originalRow = rows().get(first);
        String content = field(originalRow, "content").toString();
        int top = layout.getDecoratedTop(layout.findViewByPosition(first)) - reader.getPaddingTop();
        double before = progress();
        // Both responses arrive before the RecyclerView has laid out either update.
        insertChapter(0, 7, "上一章");
        insertChapter(2, 9, "下一章");
        settle();
        first = layout.findFirstVisibleItemPosition();
        assertEquals(0, field(activity, "currentTopicId"));
        assertEquals(content, field(rows().get(first), "content").toString());
        assertEquals(top, layout.getDecoratedTop(layout.findViewByPosition(first)) - reader.getPaddingTop());
        assertEquals(before, progress(), 0.000001);
        assertConsistentProgress();
    }

    @Test public void chapterBoundaryChangesTheTitleAndProgressTogether() throws Exception {
        launch(-1);
        insertChapter(1, 9, "下一章");
        settle();
        int next = firstRowOf(9);
        scrollTo(next - 1, -10);
        assertEquals(0, field(activity, "currentTopicId"));
        assertEquals(1.0, progress(), 0.000001);
        assertConsistentProgress();
        scrollTo(next, 0);
        assertEquals(9, field(activity, "currentTopicId"));
        assertEquals("下一章", ((TextView) activity.findViewById(R.id.tvChapterTitle)).getText().toString());
        assertEquals(0.0, progress(), 0.000001);
        assertConsistentProgress();
        reader.scrollBy(0, 180);
        settle();
        assertTrue(progress() > 0 && progress() < 1);
        assertConsistentProgress();
        reader.scrollBy(0, -181);
        settle();
        assertEquals("Scrolling back across the boundary restores the previous chapter", 0,
                field(activity, "currentTopicId"));
        assertEquals(1.0, progress(), 0.000001);
        assertConsistentProgress();
    }

    @Test public void savedProgressRestoresInsideAParagraph() throws Exception {
        launch(0.37);
        assertEquals(0.37, progress(), 0.001);
        assertTrue(layout.getDecoratedTop(layout.findViewByPosition(layout.findFirstVisibleItemPosition()))
                < reader.getPaddingTop());
        assertConsistentProgress();
        double before = progress();
        insertChapter(0, 7, "上一章");
        settle();
        assertEquals(before, progress(), 0.000001);
        assertConsistentProgress();
    }

    @Test public void completedChapterDoesNotRegressWhenAnotherChapterLoads() throws Exception {
        launch(-1);
        scrollTo(rows().size() - 1, 0);
        assertEquals(1.0, progress(), 0.000001);
        assertConsistentProgress();
        insertChapter(1, 9, "下一章");
        settle();
        assertEquals(0, field(activity, "currentTopicId"));
        assertEquals(1.0, progress(), 0.000001);
        assertConsistentProgress();
    }

    @Test public void aFullyVisibleShortFinalChapterIsReportedAsComplete() throws Exception {
        launch(-1);
        insertChapter(1, 9, "短章", "一屏内即可读完的正文。");
        settle();
        invoke("fetchChapterContent", new Class<?>[]{int.class, boolean.class}, 9, false);
        settle();
        assertEquals(9, field(activity, "currentTopicId"));
        assertEquals("短章", ((TextView) activity.findViewById(R.id.tvChapterTitle)).getText().toString());
        assertEquals(1.0, progress(), 0.000001);
        assertConsistentProgress();
    }

    @Test public void switchingModesKeepsTheCurrentChapterAndIgnoresHiddenScrolls() throws Exception {
        launch(-1);
        insertChapter(1, 9, "下一章");
        settle();
        scrollTo(firstRowOf(9) + 2, -100);
        TabLayout modes = activity.findViewById(R.id.tabPageMode);
        modes.getTabAt(0).select();
        settle();
        assertEquals(9, field(activity, "currentTopicId"));
        assertTrue(label().contains("/"));
        String pageCount = label();
        reader.scrollBy(0, 100);
        settle();
        assertEquals(pageCount, label());
        modes.getTabAt(1).select();
        settle();
        assertEquals(9, field(activity, "currentTopicId"));
        assertConsistentProgress();
    }
}
