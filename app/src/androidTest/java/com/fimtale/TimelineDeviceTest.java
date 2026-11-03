package com.fimtale;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fimtale.network.ApiDataConverter;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.*;
import org.junit.runner.RunWith;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;

/** Every API request, including mark-read/activation, terminates in local fixtures. */
@RunWith(AndroidJUnit4.class)
public class TimelineDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private SharedPreferences session;
    private Map<String, ?> originalSession;
    private Field service, updateService;
    private Object originalApi, originalUpdate;
    private volatile boolean failNextPage, spaceOpened;
    private volatile CountDownLatch holdFeed;
    private final AtomicInteger firstPageReads = new AtomicInteger(), readMarks = new AtomicInteger();

    @Before public void setup() throws Exception {
        session = instrumentation.getTargetContext().getSharedPreferences("fimtale_session", 0);
        originalSession = session.getAll();
        session.edit().clear().putString("token", "timeline-fixture").putString("user_id", "9").commit();
        service = RetrofitClient.class.getDeclaredField("service"); service.setAccessible(true); originalApi = service.get(null);
        updateService = RetrofitClient.class.getDeclaredField("updateService"); updateService.setAccessible(true); originalUpdate = updateService.get(null);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath(), data = "null"; int status = 200;
            if (path.endsWith("get_timeline")) {
                assertEquals("timeline-fixture", chain.request().header("Token"));
                assertEquals("12", chain.request().url().queryParameter("per_page"));
                int page = Integer.parseInt(chain.request().url().queryParameter("page"));
                if (page == 1) {
                    firstPageReads.incrementAndGet();
                    CountDownLatch gate = holdFeed;
                    if (gate != null) try { gate.await(8, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { throw new java.io.IOException(error); }
                    StringBuilder rows = new StringBuilder("[");
                    for (int i = 0; i < 12; i++) { if (i > 0) rows.append(','); rows.append(entry(i)); }
                    data = rows.append(']').toString();
                } else if (failNextPage) { failNextPage = false; status = 503; }
                else data = "[" + entry(11) + "," + entry(12) + "," + entry(13) + "]";
            } else if (path.endsWith("get_user_auth")) {
                data = "{\"user_id\":9,\"qualify_status\":2,\"space_status\":" + (spaceOpened ? 1 : 0) + "}";
            } else if (path.endsWith("get_timeline_update_count")) data = "3";
            else if (path.endsWith("read_timeline")) readMarks.incrementAndGet();
            else if (path.endsWith("activate_user_space")) spaceOpened = true;
            else if (path.endsWith("work_feed") || path.endsWith("search_works") || path.endsWith("get_curated_works"))
                data = "{\"items\":[],\"total\":0}";
            else if (path.equals("/update/")) data = "{}";
            else throw new AssertionError("Unexpected endpoint " + path);
            return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                    .message("Fixture").body(ResponseBody.create(MediaType.get("application/json"),
                            "{\"data\":" + data + ",\"msg\":\"加载失败，请重试\"}")).build();
        }).build();
        FimTaleApiService api = new Retrofit.Builder().baseUrl(SiteUrls.API).client(client)
                .callbackExecutor(command -> new Handler(Looper.getMainLooper()).post(command))
                .addConverterFactory(new ApiDataConverter()).addConverterFactory(GsonConverterFactory.create())
                .build().create(FimTaleApiService.class);
        service.set(null, api); updateService.set(null, api);
    }

    private String entry(int index) {
        int[] types = {6, 7, 8, 14, 1}; int type = types[index % types.length]; int id = 100 + index;
        return "{\"type\":" + type + ",\"entity_id\":" + id + ",\"created_at\":\"2026-10-05T14:00:00Z\","
                + "\"from_user\":{\"username\":\"暮光闪闪\"},\"context\":{\"id\":7,\"name\":\"小马同人频道\"},"
                + "\"entity\":{\"id\":" + id + ",\"work_id\":42,\"chapter_id\":12,\"channel_id\":7,"
                + "\"title\":\"友谊的故事 · " + (index + 1) + "\",\"context_title\":\"友谊的故事\","
                + "\"content\":\"[b]新的冒险开始了[/b]\\n一起探索小马利亚的故事。\","
                + "\"intro\":\"一段关于友谊与勇气的新故事。\",\"count_character\":2500,\"count_view\":128,"
                + "\"count_comment\":6,\"count_fav\":12,\"user\":{\"username\":\"暮光闪闪\"}}}";
    }
    @After public void cleanup() throws Exception {
        if (holdFeed != null) holdFeed.countDown();
        service.set(null, originalApi); updateService.set(null, originalUpdate);
        SharedPreferences.Editor editor = session.edit().clear();
        originalSession.forEach((key, value) -> { if (value instanceof String) editor.putString(key, (String) value); });
        editor.commit();
    }
    private void selectTimeline(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> ((BottomNavigationView) activity.findViewById(R.id.bottom_navigation)).setSelectedItemId(R.id.nav_timeline));
    }
    private void await(ActivityScenario<MainActivity> scenario, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 8000; AtomicBoolean done = new AtomicBoolean();
        while (!done.get() && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> done.set(condition.test(activity))); SystemClock.sleep(40);
        }
        assertTrue("Timeline state did not settle", done.get()); instrumentation.waitForIdleSync();
    }
    private boolean loaded(MainActivity activity, int count) {
        RecyclerView list = activity.findViewById(R.id.timelineList);
        return list != null && list.isShown() && list.getAdapter().getItemCount() == count;
    }

    @Test public void nativeFeedRetriesPaginationRefreshesAndRestoresSelectedTab() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            selectTimeline(scenario);
            await(scenario, activity -> loaded(activity, 14) && readMarks.get() > 0);
            scenario.onActivity(activity -> {
                BottomNavigationView nav = activity.findViewById(R.id.bottom_navigation);
                assertEquals(4, nav.getMenu().size());
                assertEquals(R.id.nav_timeline, nav.getMenu().getItem(1).getItemId());
                assertEquals(R.id.nav_article, nav.getMenu().getItem(2).getItemId());
                for (int i = 0; i < nav.getMenu().size(); i++) {
                    assertNull(nav.getMenu().getItem(i).getIcon());
                    View item = nav.findViewById(nav.getMenu().getItem(i).getItemId());
                    View labels = item.findViewById(com.google.android.material.R.id.navigation_bar_item_labels_group);
                    assertEquals(item.getHeight() / 2f, (labels.getTop() + labels.getBottom()) / 2f, 2f);
                }
                assertEquals("开通空间", ((TextView) activity.findViewById(R.id.timelineComposeAction)).getText().toString());
                activity.findViewById(R.id.timelineComposeAction).performClick();
            });
            await(scenario, activity -> "发帖".contentEquals(((TextView) activity.findViewById(R.id.timelineComposeAction)).getText()));
            capture("timeline-native");
            failNextPage = true;
            scenario.onActivity(activity -> ((RecyclerView) activity.findViewById(R.id.timelineList)).scrollToPosition(13));
            await(scenario, activity -> activity.findViewById(R.id.timelineLoadMore) != null);
            scenario.onActivity(activity -> activity.findViewById(R.id.timelineLoadMore).performClick());
            await(scenario, activity -> "重试".contentEquals(((TextView) activity.findViewById(R.id.timelineLoadMore)).getText()));
            scenario.onActivity(activity -> activity.findViewById(R.id.timelineLoadMore).performClick());
            await(scenario, activity -> loaded(activity, 16));
            int before = firstPageReads.get();
            scenario.onActivity(activity -> ((RecyclerView) activity.findViewById(R.id.timelineList)).scrollToPosition(0));
            instrumentation.waitForIdleSync(); SystemClock.sleep(250);
            int[] point = new int[2]; int[] extent = new int[2];
            scenario.onActivity(activity -> {
                View list = activity.findViewById(R.id.timelineList); list.getLocationOnScreen(point);
                extent[0] = list.getWidth(); extent[1] = list.getHeight();
            });
            DeviceGestures.swipe(instrumentation, point[0] + extent[0] / 2f, point[1] + extent[1] * .2f,
                    point[0] + extent[0] / 2f, point[1] + extent[1] * .75f);
            await(scenario, activity -> firstPageReads.get() > before && loaded(activity, 14));
            scenario.recreate();
            await(scenario, activity -> loaded(activity, 14));
            scenario.onActivity(activity -> assertEquals(R.id.nav_timeline,
                    ((BottomNavigationView) activity.findViewById(R.id.bottom_navigation)).getSelectedItemId()));
        }
    }

    @Test public void guestOpensNativeLoginWithoutFetchingPrivateFeed() {
        session.edit().clear().commit();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(LoginActivity.class.getName(),
                new Instrumentation.ActivityResult(0, null), true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            selectTimeline(scenario);
            await(scenario, activity -> activity.findViewById(R.id.timelineState) != null && activity.findViewById(R.id.timelineState).isShown());
            scenario.onActivity(activity -> {
                assertEquals("登录后查看关注动态", ((TextView) activity.findViewById(R.id.timelineState).findViewById(R.id.pageErrorMessage)).getText().toString());
                activity.findViewById(R.id.timelineState).findViewById(R.id.pageErrorRetry).performClick();
            });
            assertEquals(1, monitor.getHits()); assertEquals(0, firstPageReads.get());
        } finally { instrumentation.removeMonitor(monitor); }
    }

    @Test public void logoutDiscardsLatePrivateResponse() {
        holdFeed = new CountDownLatch(1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            selectTimeline(scenario);
            await(scenario, activity -> firstPageReads.get() == 1);
            scenario.moveToState(Lifecycle.State.CREATED);
            session.edit().clear().commit();
            scenario.moveToState(Lifecycle.State.RESUMED);
            holdFeed.countDown();
            await(scenario, activity -> activity.findViewById(R.id.timelineState).isShown());
            scenario.onActivity(activity -> {
                assertEquals("登录后查看关注动态", ((TextView) activity.findViewById(R.id.timelineState).findViewById(R.id.pageErrorMessage)).getText().toString());
                assertFalse(activity.findViewById(R.id.timelineList).isShown());
                assertEquals(2, ((RecyclerView) activity.findViewById(R.id.timelineList)).getAdapter().getItemCount());
            });
            assertEquals(0, readMarks.get());
        }
    }
    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        try (FileOutputStream output = new FileOutputStream(new File(instrumentation.getTargetContext().getFilesDir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
