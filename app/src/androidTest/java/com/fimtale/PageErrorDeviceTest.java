package com.fimtale;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.fimtale.ui.PageErrorView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fimtale.network.ApiDataConverter;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.ResponseBody;
import org.junit.*;
import org.junit.runner.RunWith;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;

/** Run with adb am instrument; no session changes, writes, or connected-test uninstall step. */
@RunWith(AndroidJUnit4.class)
public class PageErrorDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Field service;
    private Object originalApi;
    private volatile boolean failing = true;
    private final AtomicInteger reads = new AtomicInteger();
    @Before public void setup() throws Exception {
        service = RetrofitClient.class.getDeclaredField("service"); service.setAccessible(true); originalApi = service.get(null);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertEquals("GET", chain.request().method());
            assertTrue(chain.request().url().encodedPath().endsWith("list_read_progress"));
            reads.incrementAndGet();
            return new okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(failing ? 503 : 200).message("Fixture").body(ResponseBody.create(MediaType.get("application/json"),
                            "{\"data\":{\"items\":[],\"total\":0},\"msg\":\"服务器暂时不可用，请稍后重试。\"}")).build();
        }).build();
        service.set(null, new Retrofit.Builder().baseUrl(SiteUrls.API).client(client)
                .callbackExecutor(command -> new Handler(Looper.getMainLooper()).post(command))
                .addConverterFactory(new ApiDataConverter()).addConverterFactory(GsonConverterFactory.create())
                .build().create(FimTaleApiService.class));
    }
    @After public void cleanup() throws Exception { service.set(null, originalApi); }
    private PageErrorView shownError(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof PageErrorView) return ((TextView) view.findViewById(R.id.pageErrorTitle))
                .getText().equals(view.getContext().getText(R.string.page_error_title)) ? (PageErrorView) view : null;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            PageErrorView found = shownError(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private void await(ActivityScenario<HistoryActivity> scenario, boolean error) {
        long until = SystemClock.uptimeMillis() + 6000; AtomicBoolean done = new AtomicBoolean();
        while (!done.get() && SystemClock.uptimeMillis() < until) {
            scenario.onActivity(activity -> done.set((shownError(activity.findViewById(android.R.id.content)) != null) == error
                    && activity.findViewById(R.id.loadingSkeleton).getVisibility() == View.GONE));
            SystemClock.sleep(40);
        }
        assertTrue(done.get()); instrumentation.waitForIdleSync();
    }
    @Test public void errorRetainsHeaderAndRetryRecoversToAnEmptyPageWithWorkingPullRefresh() throws Exception {
        try (ActivityScenario<HistoryActivity> scenario = ActivityScenario.launch(HistoryActivity.class)) {
            await(scenario, true);
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.toolbarContainer).isShown());
                TextView title = shownError(activity.findViewById(android.R.id.content)).findViewById(R.id.pageErrorTitle);
                assertEquals("加载失败", title.getText().toString());
                int[] header = new int[2], text = new int[2];
                activity.findViewById(R.id.toolbarContainer).getLocationOnScreen(header); title.getLocationOnScreen(text);
                assertTrue(text[1] > header[1] + activity.findViewById(R.id.toolbarContainer).getHeight());
            });
            Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
            try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getFilesDir(), "page-error.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
            failing = false;
            scenario.onActivity(activity -> shownError(activity.findViewById(android.R.id.content)).findViewById(R.id.pageErrorRetry).performClick());
            await(scenario, false);
            scenario.onActivity(activity -> assertEquals("暂无历史记录",
                    ((TextView) activity.findViewById(R.id.loadingStatus).findViewById(R.id.pageErrorMessage)).getText().toString()));
            int before = reads.get();
            int[] point = new int[2], size = new int[2];
            scenario.onActivity(activity -> {
                View list = activity.findViewById(R.id.recyclerView); list.getLocationOnScreen(point);
                size[0] = list.getWidth(); size[1] = list.getHeight();
            });
            DeviceGestures.swipe(instrumentation, point[0] + size[0] / 2f, point[1] + size[1] * .25f,
                    point[0] + size[0] / 2f, point[1] + size[1] * .75f);
            await(scenario, false);
            assertTrue(reads.get() > before);
        }
    }
}
