package com.fimtale.ui;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.R;
import com.fimtale.model.WorkCommentRequest;
import com.fimtale.model.WorkCommentsResponse;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.utils.UserPreferences;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.fimtale.ResourceApplication.class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReaderCommentsPanelTest {
    private final List<Pending<?>> pending = new ArrayList<>();
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private ReaderCommentsPanel panel;
    private View root;
    private RecyclerView list;
    private PullRefreshLayout refresh;
    private Field service;
    private Object originalService;
    private long downTime;

    @Before public void setup() throws Exception {
        service = RetrofitClient.class.getDeclaredField("service");
        service.setAccessible(true);
        originalService = service.get(null);
        service.set(null, Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FimTaleApiService.class},
                (proxy, method, args) -> {
                    Pending<Object> call = new Pending<>(method.getName(), args);
                    pending.add(call);
                    return call;
                }));
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_Fimtale);
        activity = controller.setup().visible().get();
        UserPreferences.saveToken(activity, "reader-comment-fixture");
        root = activity.getLayoutInflater().inflate(R.layout.item_reader_comment_page, null);
        activity.setContentView(root);
        list = root.findViewById(R.id.readerCommentsList);
        refresh = root.findViewById(R.id.readerCommentsRefreshLayout);
        panel = new ReaderCommentsPanel(activity, root, 42);
        layout();
    }

    @After public void cleanup() throws Exception {
        panel.close();
        controller.pause().stop().destroy();
        service.set(null, originalService);
    }

    @Test public void chapterChangesDiscardOldResultsAndKeepPaginationAndSortScoped() {
        panel.bind(101);
        Pending<WorkCommentsResponse> old = comments(101, 1, "asc");
        panel.bind(102);
        Pending<WorkCommentsResponse> current = comments(102, 1, "asc");
        assertTrue(old.canceled);
        old.reply(data(101, 99));
        assertEquals(0, list.getAdapter().getItemCount());
        current.reply(data(102, 33));
        assertEquals("评论（33）", text(R.id.readerCommentsTitle));

        root.findViewById(R.id.readerCommentsNext).performClick();
        comments(102, 2, "asc").reply(data(102, 33));
        assertEquals("2 / 3", text(R.id.readerCommentsPage));
        root.findViewById(R.id.readerCommentsSort).performClick();
        comments(102, 1, "desc").reply(data(102, 33));
        assertEquals("1 / 3", text(R.id.readerCommentsPage));
        assertEquals("从晚到早", text(R.id.readerCommentsSort));
    }

    @Test public void pullRefreshRetainsCommentsAndStopsAfterSuccessOrFailureIncludingEmptyLists() {
        panel.bind(101);
        comments(101, 1, "asc").reply(data(101, 17));
        root.findViewById(R.id.readerCommentsNext).performClick();
        comments(101, 2, "asc").reply(data(101, 17));
        layout();
        int before = pending.size();
        pull();
        assertEquals(before + 1, pending.size());
        Pending<WorkCommentsResponse> firstRefresh = comments(101, 1, "asc");
        assertTrue(refresh.isRefreshing());
        assertEquals(View.VISIBLE, list.getVisibility());
        assertEquals(1, list.getAdapter().getItemCount());
        assertEquals(View.GONE, root.findViewById(R.id.readerCommentsSkeleton).getVisibility());
        pull();
        assertEquals(before + 1, pending.size());
        firstRefresh.reply(data(101, 1));
        assertFalse(refresh.isRefreshing());
        assertEquals("1 / 1", text(R.id.readerCommentsPage));

        for (boolean serverError : new boolean[]{true, false}) {
            pull();
            Pending<WorkCommentsResponse> failing = comments(101, 1, "asc");
            assertTrue(refresh.isRefreshing());
            if (serverError) failing.error(); else failing.fail();
            assertFalse(refresh.isRefreshing());
            assertEquals(1, list.getAdapter().getItemCount());
            assertEquals(View.VISIBLE, errorView(root).getVisibility());
            root.findViewById(R.id.pageErrorRetry).performClick();
            comments(101, 1, "asc").reply(data(101, 1));
        }

        pull();
        comments(101, 1, "asc").reply(data(101, 0));
        assertFalse(refresh.isRefreshing());
        assertEquals(0, list.getAdapter().getItemCount());
        assertEquals("暂无评论，下拉刷新", text(R.id.readerCommentsStatus));
        pull();
        assertTrue(refresh.isRefreshing());
        comments(101, 1, "asc").reply(data(101, 1));
        assertFalse(refresh.isRefreshing());
        assertEquals(1, list.getAdapter().getItemCount());
    }

    @Test public void recycledCommentPagesCancelRefreshAndReuseTheErrorContainer() {
        panel.bind(101);
        comments(101, 1, "asc").reply(data(101, 1));
        root.findViewById(R.id.readerCommentsSort).performClick();
        comments(101, 1, "desc").reply(data(101, 1));
        PageErrorView error = errorView(root);
        Object listParent = list.getParent();
        pull();
        Pending<WorkCommentsResponse> old = comments(101, 1, "desc");
        panel.close();
        assertTrue(old.canceled);
        assertFalse(refresh.isRefreshing());

        panel = new ReaderCommentsPanel(activity, root, 42);
        panel.bind(102);
        Pending<WorkCommentsResponse> current = comments(102, 1, "asc");
        assertEquals("从早到晚", text(R.id.readerCommentsSort));
        assertSame(listParent, list.getParent());
        assertSame(error, errorView(root));
        old.reply(data(101, 99));
        assertEquals(0, list.getAdapter().getItemCount());
        current.reply(data(102, 2));
        assertEquals("评论（2）", text(R.id.readerCommentsTitle));
        pull();
        assertTrue(refresh.isRefreshing());
        comments(102, 1, "asc").reply(data(102, 2));
        assertFalse(refresh.isRefreshing());
    }

    @Test public void reusingTheViewKeepsTheDraftOnlyForItsOriginalChapter() {
        panel.bind(101);
        comments(101, 1, "asc").reply(data(101, 1));
        ((TextView) root.findViewById(R.id.readerCommentComposerInput)).setText("尚未发送的草稿");
        panel.close();
        panel = new ReaderCommentsPanel(activity, root, 42);
        panel.bind(101);
        assertEquals("尚未发送的草稿", text(R.id.readerCommentComposerInput));
        comments(101, 1, "asc").reply(data(101, 1));
        panel.bind(102);
        assertEquals("", text(R.id.readerCommentComposerInput));
        comments(102, 1, "asc").reply(data(102, 1));
    }

    @Test public void rebindingTheSameChapterKeepsTheFocusedDraftAndCurrentCommentPage() {
        panel.bind(101);
        comments(101, 1, "asc").reply(data(101, 32));
        root.findViewById(R.id.readerCommentsNext).performClick();
        comments(101, 2, "asc").reply(data(101, 32));
        TextView input = root.findViewById(R.id.readerCommentComposerInput);
        input.setText("正在输入的评论"); input.requestFocus();
        RecyclerView.Adapter<?> original = list.getAdapter();
        int requests = pending.size();
        String currentPage = text(R.id.readerCommentsPage);

        panel.bind(101);

        assertEquals("A layout rebind must not fetch comments again", requests, pending.size());
        assertSame(original, list.getAdapter()); assertTrue(input.hasFocus());
        assertEquals("正在输入的评论", input.getText().toString());
        assertEquals(currentPage, text(R.id.readerCommentsPage));
        assertEquals(View.GONE, root.findViewById(R.id.readerCommentsSkeleton).getVisibility());
    }

    @Test public void postingACommentReloadsOnlyItsChapterAndCanceledPostsCannotReloadAnotherChapter() {
        panel.bind(101);
        comments(101, 1, "asc").reply(data(101, 0));
        ((TextView) root.findViewById(R.id.readerCommentComposerInput)).setText("当前章节的评论");
        root.findViewById(R.id.readerCommentComposerSend).performClick();
        Pending<Void> post = last("createUpdateComment");
        WorkCommentRequest body = (WorkCommentRequest) post.args[1];
        assertEquals(42, body.workId);
        assertEquals(Integer.valueOf(101), body.chapterId);
        post.reply(null);
        comments(101, 1, "desc").reply(data(101, 1));
        assertEquals("", text(R.id.readerCommentComposerInput));

        ((TextView) root.findViewById(R.id.readerCommentComposerInput)).setText("仍属于第一章");
        root.findViewById(R.id.readerCommentComposerSend).performClick();
        Pending<Void> oldPost = last("createUpdateComment");
        panel.bind(102);
        assertTrue(oldPost.canceled);
        int before = pending.size();
        oldPost.reply(null);
        assertEquals(before, pending.size());
        comments(102, 1, "desc").reply(data(102, 0));
        assertTrue(root.findViewById(R.id.readerCommentComposerSend).isEnabled());
    }

    private Pending<WorkCommentsResponse> comments(int chapter, int page, String order) {
        Pending<WorkCommentsResponse> call = last("getWorkComments");
        assertArrayEquals(new Object[]{42, chapter, page, 16, "created_at", order}, call.args);
        return call;
    }

    @SuppressWarnings("unchecked") private <T> Pending<T> last(String method) {
        for (int i = pending.size() - 1; i >= 0; i--)
            if (pending.get(i).method.equals(method)) return (Pending<T>) pending.get(i);
        throw new AssertionError(method);
    }

    private WorkCommentsResponse data(int chapter, int total) {
        String items = total == 0 ? "[]" : "[{\"id\":" + chapter + ",\"chapter_id\":" + chapter
                + ",\"title\":\"当前章节\",\"content\":\"章节评论\",\"user\":{\"username\":\"读者\"}}]";
        return new Gson().fromJson("{\"items\":" + items + ",\"total\":" + total + "}", WorkCommentsResponse.class);
    }

    private String text(int id) { return ((TextView) root.findViewById(id)).getText().toString(); }

    private PageErrorView errorView(View view) {
        if (view instanceof PageErrorView) return (PageErrorView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            PageErrorView found = errorView(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
    }

    private void pull() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        layout();
        touch(MotionEvent.ACTION_DOWN, 40);
        for (int y = 60; y <= 400; y += 20) touch(MotionEvent.ACTION_MOVE, y);
        touch(MotionEvent.ACTION_UP, 400);
    }

    private void touch(int action, float y) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, refresh.getWidth() / 2f, y, 0);
        refresh.dispatchTouchEvent(event);
        event.recycle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
    }

    private static final class Pending<T> implements Call<T> {
        final String method;
        final Object[] args;
        Callback<T> callback;
        boolean canceled;
        Pending(String method, Object[] args) { this.method = method; this.args = args; }
        void reply(T data) { callback.onResponse(this, Response.success(data)); }
        void error() { callback.onResponse(this, Response.error(503,
                ResponseBody.create(MediaType.get("application/json"), "{\"msg\":\"暂时无法加载\"}"))); }
        void fail() { callback.onFailure(this, new IOException("offline")); }
        @Override public void enqueue(Callback<T> callback) { this.callback = callback; }
        @Override public Response<T> execute() { throw new UnsupportedOperationException(); }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Call<T> clone() { return new Pending<>(method, args); }
        @Override public Request request() { return new Request.Builder().url("https://example.test/api/").build(); }
        @Override public Timeout timeout() { return new Timeout(); }
    }
}
