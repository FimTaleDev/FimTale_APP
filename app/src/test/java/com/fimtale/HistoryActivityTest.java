package com.fimtale;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.fimtale.adapter.HistoryAdapter;
import com.fimtale.network.ApiDataConverter;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = ResourceApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class HistoryActivityTest {
    private final List<JsonObject> entries = new CopyOnWriteArrayList<>();
    private final List<String> deletions = new CopyOnWriteArrayList<>();
    private final List<Integer> pages = new CopyOnWriteArrayList<>();
    private volatile String failingEntry;
    private volatile boolean failRefresh;
    private volatile CountDownLatch deleteGate;
    private Field service;
    private Object originalService;
    private ActivityController<HistoryActivity> controller;
    private HistoryActivity activity;
    private RecyclerView list;
    private HistoryAdapter adapter;

    @Before public void setup() throws Exception {
        service = RetrofitClient.class.getDeclaredField("service");
        service.setAccessible(true);
        originalService = service.get(null);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            int code = 200;
            JsonObject envelope = new JsonObject();
            if (request.url().encodedPath().endsWith("delete_read_progress")) {
                Buffer buffer = new Buffer(); request.body().writeTo(buffer);
                JsonObject body = new JsonParser().parse(buffer.readUtf8()).getAsJsonObject();
                int work = body.get("work_id").getAsInt(), chapter = body.get("chapter_id").getAsInt();
                String key = work + "-" + chapter;
                deletions.add(key);
                CountDownLatch gate = deleteGate;
                if (gate != null) {
                    try {
                        if (!gate.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Delete fixture timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new java.io.IOException(e);
                    }
                }
                if (key.equals(failingEntry)) code = 503;
                else entries.removeIf(item -> item.get("work_id").getAsInt() == work
                        && (chapter == 0 || chapter(item) == chapter));
                envelope.add("data", JsonNull.INSTANCE);
            } else {
                assertTrue(request.url().encodedPath().endsWith("list_read_progress"));
                int page = Integer.parseInt(request.url().queryParameter("page"));
                pages.add(page);
                if (failRefresh && !deletions.isEmpty()) code = 503;
                List<JsonObject> snapshot = new ArrayList<>(entries);
                JsonArray items = new JsonArray();
                for (int i = (page - 1) * 20; i < Math.min(page * 20, snapshot.size()); i++) items.add(snapshot.get(i));
                JsonObject data = new JsonObject(); data.add("items", items); data.addProperty("total", snapshot.size());
                envelope.add("data", data);
            }
            envelope.addProperty("msg", "服务器暂时不可用");
            return new okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("Fixture").body(ResponseBody.create(MediaType.get("application/json"), envelope.toString())).build();
        }).build();
        FimTaleApiService api = new Retrofit.Builder().baseUrl(SiteUrls.API).client(client)
                .callbackExecutor(command -> new Handler(Looper.getMainLooper()).post(command))
                .addConverterFactory(new ApiDataConverter()).addConverterFactory(GsonConverterFactory.create())
                .build().create(FimTaleApiService.class);
        service.set(null, api);
    }

    @After public void cleanup() throws Exception {
        if (deleteGate != null) deleteGate.countDown();
        if (controller != null) controller.pause().stop().destroy();
        service.set(null, originalService);
    }

    private static int chapter(JsonObject item) {
        return item.get("chapter_id").isJsonNull() ? 0 : item.get("chapter_id").getAsInt();
    }

    private void add(int work, int chapter) {
        JsonObject item = new JsonObject();
        item.addProperty("work_id", work);
        if (chapter == 0) item.add("chapter_id", JsonNull.INSTANCE);
        else item.addProperty("chapter_id", chapter);
        item.addProperty("title", "记录 " + work + "-" + chapter);
        item.addProperty("progress", 0.5);
        item.addProperty("updated_at", "2026-10-09T10:00:00+08:00");
        entries.add(item);
    }

    private void launch() throws Exception {
        controller = Robolectric.buildActivity(HistoryActivity.class).setup().visible();
        activity = controller.get();
        list = activity.findViewById(R.id.recyclerView);
        adapter = (HistoryAdapter) ((ConcatAdapter) list.getAdapter()).getAdapters().get(0);
        await(() -> adapter.getItemCount() == Math.min(20, entries.size()) && ready());
        layout();
    }

    private boolean ready() {
        return activity.findViewById(R.id.loadingSkeleton).getVisibility() == View.GONE;
    }

    private void await(BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        } while (System.currentTimeMillis() < end);
        fail("Expected history page state");
    }

    private void layout() {
        shadowOf(Looper.getMainLooper()).idle();
        View root = activity.getWindow().getDecorView();
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(400 * density), height = Math.round(850 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private View row(int index) {
        list.scrollToPosition(index); layout();
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(index);
        assertNotNull("History row " + index, holder);
        return holder.itemView;
    }

    private void toggleBatch() {
        clickAction(R.id.action_history_batch);
        layout();
    }

    private MenuItem action(int id) {
        return ((MaterialToolbar) activity.findViewById(R.id.toolbar)).getMenu().findItem(id);
    }

    private void clickAction(int id) {
        ((MaterialToolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(id, 0);
    }

    private AlertDialog confirmSelected() {
        clickAction(R.id.action_history_delete);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private void awaitDeletion(AlertDialog dialog) throws Exception {
        await(() -> !dialog.isShowing() && ready());
        layout();
    }

    @Test public void batchSelectionStaysInToolbarAndSelectAllExitAndBackUpdateSelection() throws Exception {
        add(42, 101); add(42, 102); add(43, 0); launch();
        int toolbarHeight = activity.findViewById(R.id.historyAppBar).getHeight();
        int listTopPadding = list.getPaddingTop();
        assertNotNull(action(R.id.action_history_batch).getIcon());
        assertFalse(action(R.id.action_history_delete).isVisible());
        assertFalse(action(R.id.action_history_select_all).isVisible());
        assertFalse(row(0).isSelected());
        row(0).performClick();
        Intent reader = shadowOf(activity).getNextStartedActivity();
        assertEquals(ReaderActivity.class.getName(), reader.getComponent().getClassName());
        assertEquals(101, reader.getIntExtra(ReaderActivity.EXTRA_CHAPTER_ID, 0));
        row(2).performClick();
        Intent work = shadowOf(activity).getNextStartedActivity();
        assertEquals(TopicDetailActivity.class.getName(), work.getComponent().getClassName());
        assertEquals(43, work.getIntExtra(TopicDetailActivity.EXTRA_TOPIC_ID, 0));

        toggleBatch();
        assertEquals(toolbarHeight, activity.findViewById(R.id.historyAppBar).getHeight());
        assertEquals(listTopPadding, list.getPaddingTop());
        assertNotNull(action(R.id.action_history_delete).getIcon());
        assertNotNull(action(R.id.action_history_select_all).getIcon());
        assertTrue(action(R.id.action_history_delete).isVisible());
        assertTrue(action(R.id.action_history_select_all).isVisible());
        assertFalse(action(R.id.action_history_delete).isEnabled());
        row(0).performClick(); row(1).performClick();
        assertEquals(2, adapter.getSelectedCount());
        assertTrue(row(0).isSelected());
        assertTrue(((MaterialCardView) row(0)).getStrokeWidth() > 0);
        assertEquals(activity.getString(R.string.history_selected_count, 2),
                ((MaterialToolbar) activity.findViewById(R.id.toolbar)).getTitle().toString());
        assertNull(shadowOf(activity).getNextStartedActivity());
        clickAction(R.id.action_history_select_all);
        assertEquals(3, adapter.getSelectedCount());
        assertEquals(activity.getString(R.string.history_clear_selection), action(R.id.action_history_select_all).getTitle().toString());
        clickAction(R.id.action_history_select_all);
        assertEquals(0, adapter.getSelectedCount()); assertTrue(adapter.isBatchMode());
        assertFalse(row(0).isSelected());
        assertEquals(0, ((MaterialCardView) row(0)).getStrokeWidth());
        assertEquals(activity.getString(R.string.history_select_all), action(R.id.action_history_select_all).getTitle().toString());
        row(0).performClick(); toggleBatch();
        assertFalse(adapter.isBatchMode()); assertEquals(0, adapter.getSelectedCount());
        toggleBatch(); assertFalse(row(0).isSelected());
        row(0).performClick(); activity.getOnBackPressedDispatcher().onBackPressed();
        assertFalse(activity.isFinishing()); assertFalse(adapter.isBatchMode()); assertEquals(0, adapter.getSelectedCount());
    }

    @Test public void cancellingConfirmationDoesNotDeleteOrClearSelection() throws Exception {
        add(42, 101); add(42, 102); launch(); toggleBatch();
        row(0).performClick(); row(1).performClick();
        AlertDialog dialog = confirmSelected();
        assertEquals(activity.getString(R.string.history_delete_batch_message, 2),
                ((TextView) dialog.findViewById(android.R.id.message)).getText().toString());
        assertTrue(deletions.isEmpty());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); layout();
        assertTrue(deletions.isEmpty()); assertEquals(2, adapter.getSelectedCount());
        assertTrue(action(R.id.action_history_delete).isEnabled());
    }

    @Test public void deletionIsSequentialLocksActionsAndRetriesOnlyRemainingEntries() throws Exception {
        add(42, 101); add(42, 102); add(42, 103); add(43, 201); launch(); toggleBatch();
        row(0).performClick(); row(1).performClick(); row(2).performClick();
        failingEntry = "42-102"; deleteGate = new CountDownLatch(1);
        AlertDialog dialog = confirmSelected(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> deletions.size() == 1); layout();
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertFalse(row(0).isEnabled());
        assertFalse(action(R.id.action_history_delete).isEnabled());
        assertFalse(action(R.id.action_history_select_all).isEnabled());
        clickAction(R.id.action_history_delete);
        clickAction(R.id.action_history_select_all);
        assertEquals(3, adapter.getSelectedCount());
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertFalse(activity.isFinishing()); assertTrue(adapter.isBatchMode());
        assertEquals(Arrays.asList(1), pages);
        deleteGate.countDown(); awaitDeletion(dialog);
        assertEquals(Arrays.asList("42-101", "42-102"), deletions);
        assertEquals(2, adapter.getSelectedCount());
        assertEquals("42-102", adapter.getSelectedTopics().get(0).getKey());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("服务器暂时不可用"));

        failingEntry = null;
        AlertDialog retry = confirmSelected(); retry.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); awaitDeletion(retry);
        assertEquals(Arrays.asList("42-101", "42-102", "42-102", "42-103"), deletions);
        assertEquals(0, adapter.getSelectedCount()); assertEquals(1, adapter.getItemCount());
    }

    @Test public void workDeletionWarnsAboutChaptersAndDoesNotResendCoveredEntries() throws Exception {
        add(42, 0); add(42, 101); add(43, 101); launch(); toggleBatch();
        row(0).performClick(); row(1).performClick();
        AlertDialog dialog = confirmSelected();
        assertTrue(((TextView) dialog.findViewById(android.R.id.message)).getText().toString()
                .contains(activity.getString(R.string.history_delete_batch_work_warning)));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); awaitDeletion(dialog);
        assertEquals(Arrays.asList("42-0"), deletions);
        assertEquals(1, adapter.getItemCount()); assertEquals(0, adapter.getSelectedCount());
        row(0).performClick();
        AlertDialog single = confirmSelected();
        assertEquals(activity.getString(R.string.history_delete_chapter_message, "记录 43-101"),
                ((TextView) single.findViewById(android.R.id.message)).getText().toString());
        single.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); awaitDeletion(single);
        assertEquals(0, adapter.getItemCount());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.loadingStatus).getVisibility());
        assertFalse(action(R.id.action_history_select_all).isEnabled());
        assertFalse(action(R.id.action_history_delete).isEnabled());
        toggleBatch();
        assertFalse(action(R.id.action_history_batch).isEnabled());
    }

    @Test public void laterPageSelectionsSurviveFailureAndShiftedPagesReloadWithoutGaps() throws Exception {
        for (int chapter = 101; chapter <= 145; chapter++) add(42, chapter);
        launch(); list.scrollBy(0, 10000);
        await(() -> adapter.getItemCount() == 40); toggleBatch();
        row(0).performClick(); row(25).performClick();
        failingEntry = "42-126";
        AlertDialog dialog = confirmSelected(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); awaitDeletion(dialog);
        assertEquals(Arrays.asList(1, 2, 1, 2), pages);
        assertEquals(40, adapter.getItemCount()); assertEquals(1, adapter.getSelectedCount());
        assertEquals("42-126", adapter.getSelectedTopics().get(0).getKey());
        assertTrue(row(24).isSelected());
        assertTrue(((MaterialCardView) row(24)).getStrokeWidth() > 0);
        list.scrollBy(0, 10000); await(() -> adapter.getItemCount() == 44);
        assertEquals(Arrays.asList(1, 2, 1, 2, 3), pages);
        // Select the loaded list to verify that shifted pages contain every remaining key once.
        clickAction(R.id.action_history_select_all);
        assertEquals(44, adapter.getSelectedCount());
        assertEquals("42-102", adapter.getSelectedTopics().get(0).getKey());
        assertEquals("42-145", adapter.getSelectedTopics().get(43).getKey());
    }

    @Test public void failedRefreshPreservesUndeletedSelectionUntilRetry() throws Exception {
        add(42, 101); add(42, 102); launch(); toggleBatch();
        row(0).performClick(); row(1).performClick();
        failingEntry = "42-102"; failRefresh = true;
        AlertDialog dialog = confirmSelected(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); awaitDeletion(dialog);
        assertEquals(1, adapter.getItemCount()); assertEquals(1, adapter.getSelectedCount());
        View retry = shownError(activity.findViewById(android.R.id.content)).findViewById(R.id.pageErrorRetry); assertTrue(retry.isShown());
        failRefresh = false; retry.performClick();
        await(() -> pages.size() == 3 && ready());
        assertEquals(1, adapter.getSelectedCount());
        assertEquals("42-102", adapter.getSelectedTopics().get(0).getKey());
        assertTrue(action(R.id.action_history_delete).isEnabled());
    }
    private com.fimtale.ui.PageErrorView shownError(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof com.fimtale.ui.PageErrorView) return ((TextView) view.findViewById(R.id.pageErrorTitle))
                .getText().equals(activity.getText(R.string.page_error_title)) ? (com.fimtale.ui.PageErrorView) view : null;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                com.fimtale.ui.PageErrorView found = shownError(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
