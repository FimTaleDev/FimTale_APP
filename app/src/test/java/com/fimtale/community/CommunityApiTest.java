package com.fimtale.community;

import com.fimtale.model.*;
import com.fimtale.network.*;
import com.fimtale.report.ReportRequest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.Buffer;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;

/** Local wire fixtures only. No requests reach a live service. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34, application = com.fimtale.ResourceApplication.class)
public class CommunityApiTest {
    private final AtomicReference<Request> request = new AtomicReference<>();
    private FimTaleApiService api(String payload) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            request.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(ResponseBody.create(MediaType.get("application/json"), "{\"data\":" + payload + "}")).build();
        }).build();
        return new Retrofit.Builder().baseUrl(SiteUrls.API).client(client).addConverterFactory(new ApiDataConverter())
                .addConverterFactory(GsonConverterFactory.create()).build().create(FimTaleApiService.class);
    }
    private JsonObject body() throws Exception {
        Buffer buffer = new Buffer(); request.get().body().writeTo(buffer); return new JsonParser().parse(buffer.readUtf8()).getAsJsonObject();
    }
    @Test public void editsKeepWorkChapterAndCommentIdentityWhileRepliesOmitEditId() throws Exception {
        api("null").createUpdateComment("viewer", new WorkCommentRequest(42, "[b]正文[/b]", 9, null, 77)).execute();
        assertEquals(42, body().get("work_id").getAsInt()); assertEquals(9, body().get("chapter_id").getAsInt());
        assertEquals(77, body().get("id").getAsInt()); assertFalse(body().has("reply_comment_id"));
        api("null").createUpdateComment("viewer", new WorkCommentRequest(42, "回复", 9, 77)).execute();
        assertEquals(77, body().get("reply_comment_id").getAsInt()); assertFalse(body().has("id"));
        api("null").deleteComment("viewer", new Community.DeleteComment(77)).execute();
        assertEquals("/api/work/delete", request.get().url().encodedPath()); assertEquals(4, body().get("type").getAsInt());
        api("null").voteComment("viewer", new Community.CommentVote(77, 2)).execute();
        assertEquals("/api/work/do_comment_vote", request.get().url().encodedPath());
        assertEquals(2, body().get("operation").getAsInt()); assertEquals("viewer", request.get().header("Token"));
        ReportRequest report = new ReportRequest(ReportRequest.COMMENT, 77, "举报原因"); assertEquals(4, report.targetType);
    }
    @Test public void viewerOperationsAreScopedToEachCommentAndCanClearAnOldSelection() throws Exception {
        WorkCommentsResponse page = api("{\"items\":[{\"id\":77,\"work_id\":42,\"user_id\":8},{\"id\":78,\"user_id\":9}],"
                + "\"total\":2,\"viewer\":{\"operations\":[{\"comment_id\":77,\"operation\":1},{\"comment_id\":78,\"operation\":2}],"
                + "\"favs\":[{\"comment_id\":78}]}}")
                .getWorkComments(42, 1, 20, "created_at", "asc").execute().body();
        Comment first = page.getItems().get(0), second = page.getItems().get(1);
        page.viewer.apply(first); page.viewer.apply(second);
        assertEquals(8, first.authorId()); assertTrue(first.viewerLoaded); assertTrue(first.liked); assertFalse(first.disliked); assertFalse(first.favorited);
        assertFalse(second.liked); assertTrue(second.disliked); assertTrue(second.favorited);
        new Community.CommentViewer().apply(second); assertFalse(second.liked); assertFalse(second.disliked); assertFalse(second.favorited);
        CommentResponse located = api("{\"comment\":{\"id\":77,\"work_id\":42},\"page_number\":3}")
                .getCommentLocation(77, 16, "created_at", "desc").execute().body();
        assertEquals(Integer.valueOf(3), located.pageNumber); assertEquals("16", request.get().url().queryParameter("per_page"));
        assertEquals("desc", request.get().url().queryParameter("order_option"));
    }
    @Test public void followingAndConversationsUseDistinctBodiesAndHeaderState() throws Exception {
        api("null").followUser("viewer", new Community.UserId(8)).execute();
        assertEquals("/api/user/follow_user", request.get().url().encodedPath()); assertEquals(8, body().get("user_id").getAsInt());
        api("{\"id\":99,\"participants\":[{\"user_id\":8,\"username\":\"作者\"}]}")
                .createConversation("viewer", new Community.ConversationRequest(8)).execute();
        assertEquals("/api/user/create_conversation", request.get().url().encodedPath());
        assertEquals(8, body().getAsJsonArray("user_ids").get(0).getAsInt()); assertFalse(body().has("user_id"));
        UserDetailResponse header = api("{\"user_id\":8,\"username\":\"作者\",\"stats\":{\"follower_count\":6},"
                + "\"user_operations\":[{\"follower_id\":7,\"followee_id\":8}]}").getUserHeader("viewer", "作者").execute().body();
        assertTrue(header.followedBy(7)); assertFalse(header.followedBy(6)); assertEquals(6, header.getFollowers());
        Community.UserTab tab = api("{\"current_tab\":\"following\",\"count\":21,\"content\":[{\"user_id\":8,\"username\":\"作者\"}]}")
                .getUserTab("viewer", "读者", "following", 2, 20).execute().body();
        assertEquals(21, tab.count); assertEquals("following", request.get().url().queryParameter("tab"));
        assertEquals(8, new Gson().fromJson(tab.content, AuthorInfo[].class)[0].getId());
    }
    @Test public void folderManagementPreservesNullAllScopeAndNamedFolderIdentity() throws Exception {
        WorkInteractions.Folder folder = api("{\"id\":12,\"name\":\"待读\",\"count_works\":3}")
                .createFavoriteFolder("viewer", new Community.FolderRequest(null, "待读")).execute().body();
        assertEquals(3, folder.count); assertFalse(body().has("folder_id"));
        api("null").updateFavoriteFolder("viewer", new Community.FolderRequest(12, "已读")).execute();
        assertEquals(12, body().get("folder_id").getAsInt()); assertEquals("已读", body().get("name").getAsString());
        api("null").deleteFavoriteFolder("viewer", new Community.FolderRequest(12, null)).execute();
        assertEquals(12, body().get("folder_id").getAsInt()); assertFalse(body().has("name"));
        api("{\"items\":[],\"total\":0}").getFavoriteWorks("viewer", null, 1, 20).execute();
        assertNull(request.get().url().queryParameter("folder_id"));
        api("{\"items\":[],\"total\":21}").getFavoriteWorks("viewer", 12, 2, 20).execute();
        assertEquals("12", request.get().url().queryParameter("folder_id")); assertEquals("2", request.get().url().queryParameter("page"));
    }
    @Test public void tagsUseAuthoritativeMembershipAndAStructuredScope() throws Exception {
        Community.TagFavorite result = api("{\"faved\":false,\"count_fav\":0}").favoriteTag("viewer", new Community.TagId(7)).execute().body();
        assertFalse(result.faved); assertEquals(0, result.count_fav); assertEquals(7, body().get("tag_id").getAsInt());
        JsonObject scope = new JsonParser().parse(SearchQuery.tags(Arrays.asList("中文 & \"引号\"", "另一标签"), true)).getAsJsonObject().getAsJsonObject("tags_expr");
        assertEquals("and", scope.get("op").getAsString());
        assertEquals("中文 & \"引号\"", scope.getAsJsonArray("children").get(0).getAsJsonObject().get("tag").getAsString());
        assertEquals("or", new JsonParser().parse(SearchQuery.tags(Collections.singleton("标签"), false)).getAsJsonObject()
                .getAsJsonObject("tags_expr").get("op").getAsString());
        try { SearchQuery.tags(Collections.emptyList(), false); fail("Empty scopes must never become unfiltered feeds"); }
        catch (IllegalArgumentException expected) { }
    }
}
