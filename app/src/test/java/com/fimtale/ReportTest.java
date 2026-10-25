package com.fimtale;

import android.app.Application;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelProvider;
import com.fimtale.network.ApiDataConverter;
import com.fimtale.network.FimTaleApiService;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.fimtale.report.ReportDialog;
import com.fimtale.report.ReportRequest;
import com.fimtale.report.ReportViewModel;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Local responses only: this suite never submits a real report. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.fimtale.ResourceApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReportTest {
    public static class Host extends AppCompatActivity {
        @Override public void onCreate(Bundle state) { setTheme(R.style.Theme_Fimtale); super.onCreate(state); }
    }
    private ActivityController<Host> controller;
    private Host activity;
    private Field service, factory;
    private Object originalApi;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile boolean loseResponse, emptyResponse;
    private CountDownLatch gate;

    @Before public void setup() throws Exception {
        UserPreferences.saveToken(RuntimeEnvironment.getApplication(), "reporter-fixture");
        factory = ViewModelProvider.AndroidViewModelFactory.class.getDeclaredField("sInstance");
        factory.setAccessible(true); factory.set(null, null);
        service = RetrofitClient.class.getDeclaredField("service"); service.setAccessible(true); originalApi = service.get(null);
        OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain -> {
            Request request = chain.request(); requests.add(request);
            assertEquals("/api/report/create_report", request.url().encodedPath());
            if (gate != null) try { gate.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
            if (loseResponse) throw new java.io.IOException("Response lost");
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(ResponseBody.create(MediaType.get("application/json"), "{\"data\":" + (emptyResponse ? "null" : "{\"id\":42}")
                            + ",\"msg\":\"请勿重复举报\"}")).build();
        }).build();
        service.set(null, new Retrofit.Builder().baseUrl(SiteUrls.API).client(client)
                .callbackExecutor(command -> new Handler(Looper.getMainLooper()).post(command))
                .addConverterFactory(new ApiDataConverter()).addConverterFactory(GsonConverterFactory.create()).build().create(FimTaleApiService.class));
        controller = Robolectric.buildActivity(Host.class).setup(); activity = controller.get();
    }
    @After public void cleanup() throws Exception {
        if (gate != null) gate.countDown();
        controller.pause().stop().destroy(); service.set(null, originalApi); factory.set(null, null);
    }
    private ReportDialog fragment() { return (ReportDialog) activity.getSupportFragmentManager().findFragmentByTag(ReportDialog.TAG); }
    private AlertDialog dialog() { return (AlertDialog) fragment().requireDialog(); }
    private ReportViewModel model() { return new ViewModelProvider(fragment()).get(ReportViewModel.class); }
    private void open(int type, long id) { ReportDialog.show(activity, type, id, type == ReportRequest.WORK ? "文章《测试作品》" : "用户 @测试用户"); }
    private void content(String value) { ((EditText) dialog().findViewById(R.id.reportContent)).setText(value); }
    private void submit() { dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }
    private void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); if (ready.getAsBoolean()) return; Thread.sleep(10);
        }
        fail("Timed out waiting for local report fixture");
    }
    private JsonObject body(int index) throws Exception {
        Buffer buffer = new Buffer(); requests.get(index).body().writeTo(buffer);
        return new JsonParser().parse(buffer.readUtf8()).getAsJsonObject();
    }
    @Test public void workAndUserUseCorrectIdsTypesAndTrimmedContent() throws Exception {
        for (int type : new int[]{ReportRequest.WORK, ReportRequest.USER}) {
            open(type, type == ReportRequest.WORK ? 71323 : 456);
            content(" \n\u3000举报原因：https://example.org/evidence\n补充说明\u00a0 "); submit();
            await(() -> fragment() == null);
        }
        assertEquals(2, requests.size());
        for (int i = 0; i < 2; i++) {
            assertEquals("POST", requests.get(i).method()); assertEquals("reporter-fixture", requests.get(i).header("Token"));
            assertNull(requests.get(i).url().query());
            JsonObject body = body(i); assertEquals(4, body.size());
            assertEquals(i == 0 ? 1 : 7, body.get("target_type").getAsInt());
            assertEquals(i == 0 ? 71323 : 456, body.get("target_id").getAsLong());
            assertEquals("report", body.get("kind").getAsString());
            assertEquals("举报原因：https://example.org/evidence\n补充说明", body.get("content").getAsString());
        }
    }
    @Test public void whitespaceValidationAndCancellationDoNotSendAnything() {
        open(1, 71323); content("\n\u3000\u00a0"); submit();
        assertNotNull(((TextInputLayout) dialog().findViewById(R.id.reportContentLayout)).getError());
        assertTrue(requests.isEmpty()); content("说明");
        assertNull(((TextInputLayout) dialog().findViewById(R.id.reportContentLayout)).getError());
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertNull(fragment()); assertTrue(requests.isEmpty());
        ReportDialog.show(activity, 7, 0, "错误目标"); assertNull(fragment());
    }
    @Test public void commentReportPassesViewModelValidationAndKeepsCommentId() throws Exception {
        open(ReportRequest.COMMENT, 77); content("评论举报原因"); submit();
        await(() -> fragment() == null);
        assertEquals(1, requests.size()); assertEquals(4, body(0).get("target_type").getAsInt());
        assertEquals(77, body(0).get("target_id").getAsLong());
    }
    @Test public void inFlightRotationKeepsDraftAndPreventsDuplicateSubmissions() throws Exception {
        gate = new CountDownLatch(1); open(7, 456); content("举报说明"); ReportViewModel before = model(); submit();
        await(() -> requests.size() == 1);
        assertFalse(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()); assertFalse(fragment().isCancelable());
        submit(); before.submit(7, 456);
        Configuration next = new Configuration(activity.getResources().getConfiguration()); next.orientation = Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(next); activity = controller.get(); shadowOf(Looper.getMainLooper()).idle();
        assertSame(before, model()); assertEquals("举报说明", ((EditText) dialog().findViewById(R.id.reportContent)).getText().toString());
        assertFalse(dialog().getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled()); assertEquals(1, requests.size());
        gate.countDown(); await(() -> fragment() == null); assertEquals(1, requests.size());
    }
    @Test public void serviceFailureKeepsReasonAndAllowsExplicitRetry() throws Exception {
        status = 429; open(1, 71323); content("不合适的内容"); submit(); await(() -> !model().submitting);
        assertEquals("请勿重复举报", model().error); assertEquals("不合适的内容", model().content());
        assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        status = 200; submit(); await(() -> fragment() == null); assertEquals(2, requests.size());
    }
    @Test public void guestLoginAndExpiryPreserveDraftWithoutAutoSubmitting() throws Exception {
        UserPreferences.saveToken(activity, ""); open(1, 71323); content("说明"); submit();
        assertEquals(LoginActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        assertTrue(requests.isEmpty());
        UserPreferences.saveToken(activity, "new-session"); controller.pause().resume();
        assertFalse(model().needsLogin); assertEquals("说明", model().content()); assertTrue(requests.isEmpty());
        status = 401; submit(); await(() -> model().needsLogin);
        assertEquals("说明", model().content()); assertEquals("", UserPreferences.getToken(activity));
        assertEquals("登录", dialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
    }
    @Test public void accountSwitchDiscardsOldCompletion() throws Exception {
        gate = new CountDownLatch(1); open(7, 456); content("说明"); submit(); await(() -> requests.size() == 1);
        UserPreferences.saveToken(activity, "other-session"); model().connect();
        assertFalse(model().submitting); assertFalse(model().error.isEmpty());
        gate.countDown(); shadowOf(Looper.getMainLooper()).idle();
        assertFalse(model().complete); assertEquals("说明", model().content()); assertEquals(1, requests.size());
    }
    @Test public void lostOrMalformedResponsesNeverClaimSuccessOrAutoRetry() throws Exception {
        loseResponse = true; open(1, 71323); content("说明"); submit(); await(() -> !model().submitting);
        assertFalse(model().complete); assertEquals(activity.getString(R.string.report_uncertain), model().error); assertEquals(1, requests.size());
        loseResponse = false; emptyResponse = true; submit(); await(() -> !model().submitting);
        assertFalse(model().complete); assertEquals(activity.getString(R.string.report_uncertain), model().error); assertEquals(2, requests.size());
    }
    @Test public void processRestorationKeepsDraftAndNeverReplaysPendingPost() {
        open(7, 456); content("尚未提交的说明");
        Bundle state = new Bundle(); controller.pause().saveInstanceState(state).stop().destroy();
        controller = Robolectric.buildActivity(Host.class).setup(state); activity = controller.get();
        assertEquals("尚未提交的说明", model().content()); assertEquals(456, fragment().requireArguments().getLong("target_id"));
        SavedStateHandle saved = new SavedStateHandle(); saved.set("pending", true); saved.set("content", "请求中的说明");
        ReportViewModel restored = new ReportViewModel(RuntimeEnvironment.getApplication(), saved);
        assertFalse(restored.submitting); assertFalse(restored.complete); assertEquals("请求中的说明", restored.content());
        assertEquals(activity.getString(R.string.report_uncertain), restored.error); assertTrue(requests.isEmpty());
    }
}
