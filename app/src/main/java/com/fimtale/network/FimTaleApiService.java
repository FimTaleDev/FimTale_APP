package com.fimtale.network;
import com.fimtale.model.*;
import com.fimtale.editor.*;
import com.fimtale.auth.AuthRequests;
import com.fimtale.review.ReviewEntry;
import java.util.List;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

/** Routes from ft-front/schema/openapi.json. No deprecated v1 endpoints. */
public interface FimTaleApiService {
    @POST("work/do_comment_vote") Call<Void> voteComment(@Header("Token") String token, @Body com.fimtale.community.Community.CommentVote request);
    @POST("work/do_comment_star_honor") Call<Void> starComment(@Header("Token") String token, @Body com.fimtale.community.Community.CommentId request);
    @POST("work/favorite_comment") Call<Void> favoriteComment(@Header("Token") String token, @Body com.fimtale.community.Community.CommentId request);
    @POST("work/delete") Call<Void> deleteComment(@Header("Token") String token, @Body com.fimtale.community.Community.DeleteComment request);
    @GET("work/get_comment_edit_history") Call<List<com.fimtale.community.Community.CommentHistory>> getCommentHistory(@Header("Token") String token, @Query("comment_id") int id);
    @GET("work/get_comment") Call<CommentResponse> getCommentViewer(@Header("Token") String token, @Query("comment_id") int id);
    @GET("work/get_comment") Call<CommentResponse> getCommentLocation(@Query("comment_id") int id, @Query("per_page") int perPage,
            @Query("order_by") String order, @Query("order_option") String direction);
    @GET("work/get_favorite_comments") Call<com.fimtale.community.Community.CommentPage> getFavoriteComments(@Header("Token") String token, @Query("page") int page, @Query("per_page") int perPage);
    @POST("user/follow_user") Call<Void> followUser(@Header("Token") String token, @Body com.fimtale.community.Community.UserId request);
    @POST("user/create_conversation") Call<com.fimtale.notifications.Inbox.Conversation> createConversation(@Header("Token") String token, @Body com.fimtale.community.Community.ConversationRequest request);
    @GET("user/get_user_page_tab") Call<com.fimtale.community.Community.UserTab> getUserTab(@Header("Token") String token, @Query("username") String username, @Query("tab") String tab, @Query("page") int page, @Query("per_page") int perPage);
    @GET("user/get_user_page_header") Call<UserDetailResponse> getUserHeader(@Header("Token") String token, @Query("username") String username);
    @POST("user/create_favorite_folder") Call<WorkInteractions.Folder> createFavoriteFolder(@Header("Token") String token, @Body com.fimtale.community.Community.FolderRequest request);
    @POST("user/update_favorite_folder") Call<WorkInteractions.Folder> updateFavoriteFolder(@Header("Token") String token, @Body com.fimtale.community.Community.FolderRequest request);
    @POST("user/delete_favorite_folder") Call<Void> deleteFavoriteFolder(@Header("Token") String token, @Body com.fimtale.community.Community.FolderRequest request);
    @GET("work/get_favorite_works") Call<FavoritesResponse> getFavoriteWorks(@Header("Token") String token, @Query("folder_id") Integer folder, @Query("page") int page, @Query("per_page") int perPage);
    @GET("channel/get_favorite_channels") Call<List<com.fimtale.community.Community.Channel>> getFavoriteChannels(@Header("Token") String token);
    @POST("channel/favorite_channel") Call<Void> favoriteChannel(@Header("Token") String token, @Body com.fimtale.community.Community.ChannelId request);
    @GET("tag/get_favorite_tags") Call<com.fimtale.community.Community.TagPage> getFavoriteTags(@Header("Token") String token, @Query("page") int page, @Query("per_page") int perPage);
    @POST("tag/favorite_tag") Call<com.fimtale.community.Community.TagFavorite> favoriteTag(@Header("Token") String token, @Body com.fimtale.community.Community.TagId request);
    @GET("report/get_reports") Call<com.fimtale.report.MyReport.Page> getMyReports(@Header("Token") String token,
            @Query("source_user_id") long sourceUserId, @Query("status") List<Integer> statuses,
            @Query("report_id") Long reportId, @Query("page") int page, @Query("per_page") int perPage);
    @POST("report/append_report_message") Call<com.fimtale.report.MyReport> appendReportMessage(
            @Header("Token") String token, @Body com.fimtale.report.MyReport.Reply reply);
    @GET("user/get_notification_count") Call<java.util.Map<String, Integer>> getNotificationCount(@Header("Token") String token);
    @GET("user/get_notifications") Call<List<com.fimtale.notifications.Inbox.Notice>> getNotifications(@Header("Token") String token,
            @Query("type") String type, @Query("page") int page, @Query("per_page") int perPage);
    @GET("user/list_conversations") Call<List<com.fimtale.notifications.Inbox.Conversation>> getConversations(@Header("Token") String token,
            @Query("page") int page, @Query("per_page") int perPage);
    @GET("user/get_conversation_messages") Call<List<com.fimtale.notifications.Inbox.Message>> getMessages(@Header("Token") String token,
            @Query("conversation_id") long id, @Query("page") int page, @Query("per_page") int perPage);
    @POST("user/read_notifications") Call<Void> readNotifications(@Header("Token") String token, @Body com.fimtale.notifications.Inbox.Read read);
    @POST("user/send_conversation_message") Call<Void> sendMessage(@Header("Token") String token, @Body com.fimtale.notifications.Inbox.Send message);
    @POST("work/act_on_work_prequel_invite") Call<Void> actOnPrequelInvite(@Header("Token") String token, @Body com.fimtale.notifications.Inbox.Invite invite);
    @GET("user/get_timeline") Call<List<TimelineItem>> getTimeline(@Header("Token") String token,
            @Query("page") int page, @Query("per_page") int perPage);
    @GET("user/get_timeline_update_count") Call<Integer> getTimelineUpdateCount(@Header("Token") String token);
    @POST("user/read_timeline") Call<Void> readTimeline(@Header("Token") String token);
    @POST("user/activate_user_space") Call<Void> activateUserSpace(@Header("Token") String token);
    @POST("work/highlight_timeline_comment") Call<Void> highlightWorkComment(@Header("Token") String token,
            @Body TimelineItem.Highlight request);
    @POST("channel/highlight_timeline_comment") Call<Void> highlightChannelComment(@Header("Token") String token,
            @Body TimelineItem.Highlight request);
    @POST("report/create_report") Call<com.fimtale.report.ReportRequest.Result> createReport(
            @Header("Token") String token, @Body com.fimtale.report.ReportRequest report);
    @POST("report/create_report") Call<com.fimtale.report.ReportRequest.Result> sendCrashFeedback(
            @Header("Token") String token, @Body com.fimtale.crash.CrashFeedbackRequest report);
    @GET("work/get_review_entries") Call<List<ReviewEntry>> getReviewEntries(@Header("Token") String token);
    @POST("work/submit_review") Call<ReviewEntry> submitReview(@Header("Token") String token, @Body ReviewEntry.Submit submission);
    @GET("user/get_username_by_id") Call<String> getUsernameById(@Header("Token") String token, @Query("user_id") int userId);
    // Explicit empty Token keeps guest requests independent of any previously saved session.
    @Headers("Token: ") @POST("user/login") Call<String> login(@Body AuthRequests.PasswordLogin login,
            @Query("captcha_response") String captcha, @Query("captcha_type") String provider);
    @Headers("Token: ") @POST("user/request_email_login") Call<Void> requestEmailLogin(@Body AuthRequests.Email email,
            @Query("captcha_response") String captcha, @Query("captcha_type") String provider);
    @Headers("Token: ") @GET("user/verify_email_login") Call<String> verifyEmailLogin(@Query("token") String code);
    @Headers("Token: ") @POST("user/register") Call<Void> register(@Body AuthRequests.Registration registration,
            @Query("captcha_response") String captcha, @Query("captcha_type") String provider);
    @Headers("Token: ") @POST("user/request_reset_password") Call<Void> requestResetPassword(@Body AuthRequests.Email email,
            @Query("captcha_response") String captcha, @Query("captcha_type") String provider);
    @GET("work/get_drafts") Call<List<OnlineDraft.Summary>> getDrafts(@Header("Token") String token, @Query("prefix") List<String> prefixes);
    @GET("work/get_draft") Call<OnlineDraft> getDraft(@Header("Token") String token, @Query("draft_key") String key);
    @POST("work/save_draft") Call<OnlineDraft> saveDraft(@Header("Token") String token, @Body OnlineDraft.Save draft);
    @POST("work/delete_draft") Call<Void> deleteDraft(@Header("Token") String token, @Body OnlineDraft.Delete draft);
    @GET("user/get_user") Call<CurrentUser> getCurrentUser(@Header("Token") String token);
    @POST("user/logout") Call<ResponseBody> logout(@Header("Token") String token);
    @GET("user/get_active_sessions") Call<List<UserSession>> getActiveSessions(@Header("Token") String token);
    @POST("user/create_api_key") Call<String> createApiKey(@Header("Token") String token);
    @POST("user/logout") Call<Void> logoutSession(@Header("Token") String token, @Body LogoutRequest request);
    @GET("user/get_default_content_filter") Call<ContentFilterDef> getDefaultContentFilter(@Header("Token") String token);
    @POST("user/set_content_filter") Call<UserMaterial> setContentFilter(@Header("Token") String token,
            @Body SetContentFilterRequest request);
    @POST("user/user_blocklist") Call<List<BlockedUser>> updateBlocklist(@Header("Token") String token,
            @Body BlockUserRequest request);
    @GET("search/search_works") Call<TopicListResponse> getTopicList(
            @Query("page") int page, @Query("query") String query, @Query("rank") String rank);
    @GET("search/work_feed") Call<TopicListResponse> getFeed(@Query("page") int page);
    @GET("work/get_curated_works") Call<CuratedResponse> getCuratedWorks(@Query("page") int page,
            @Query("per_page") int perPage);
    @GET("work/get_work") Call<TopicDetailResponse> getWork(@Query("work_id") int workId);
    @GET("work/get_work") Call<TopicDetailResponse> getWorkViewer(@Header("Token") String token, @Query("work_id") int workId);
    @POST("work/do_work_vote") Call<Void> voteWork(@Header("Token") String token, @Body WorkInteractions.Vote vote);
    @POST("work/do_work_high_praise") Call<Void> highPraiseWork(@Header("Token") String token, @Body WorkInteractions.HighPraise highPraise);
    @GET("user/get_favorite_folders") Call<List<WorkInteractions.Folder>> getFavoriteFolders(@Header("Token") String token);
    @POST("work/add_favorite_work") Call<Void> addFavoriteWork(@Header("Token") String token, @Body WorkInteractions.FavoriteRequest favorite);
    @POST("work/remove_favorite_work") Call<Void> removeFavoriteWork(@Header("Token") String token, @Body WorkInteractions.FavoriteRequest favorite);
    @GET("work/get_comments") Call<WorkCommentsResponse> getWorkComments(@Query("work_id") int workId,
            @Query("page") int page, @Query("per_page") int perPage,
            @Query("order_by") String orderBy, @Query("order_option") String orderOption);
    @GET("work/get_comments") Call<WorkCommentsResponse> getWorkComments(@Query("work_id") int workId,
            @Query("chapter_id") Integer chapterId, @Query("page") int page, @Query("per_page") int perPage,
            @Query("order_by") String orderBy, @Query("order_option") String orderOption);
    @POST("work/create_update_comment") Call<Void> createUpdateComment(@Header("Token") String token,
            @Body WorkCommentRequest comment);
    @GET("work/get_chapter") Call<ChapterResponse> getChapter(@Query("chapter_id") int chapterId);
    @GET("work/get_comment") Call<CommentResponse> getComment(@Query("comment_id") int commentId);
    @GET("user/get_user_auth") Call<UserAuth> getUserAuth(@Header("Token") String token);
    @GET("work/get_work") Call<WorkEditResponse> getWorkForEdit(@Query("work_id") int workId, @Query("for_edit") boolean forEdit);
    @GET("work/get_chapter") Call<ChapterResponse> getChapterForEdit(@Query("chapter_id") int chapterId, @Query("for_edit") boolean forEdit);
    @POST("work/create_update_work") Call<SaveResult> saveWork(@Header("Token") String token, @Body WorkInput work,
            @Query("captcha_response") String captcha, @Query("captcha_type") String captchaType);
    @POST("work/create_update_chapter") Call<SaveResult> saveChapter(@Header("Token") String token, @Body ChapterInput chapter);
    @GET("tag/list_tags") Call<List<TagGroup>> getEditorTags(@Query("page") int page,
            @Query("per_page") int perPage, @Query("keyword") String keyword, @Query("type_names") List<String> types);
    @Multipart @POST("misc/upload_image") Call<String> uploadImage(@Header("Token") String token, @Part okhttp3.MultipartBody.Part file);
    @GET("user/list_read_progress") Call<HistoryResponse> getHistory(@Query("page") int page);
    @POST("user/delete_read_progress") Call<Void> deleteReadProgress(@Body DeleteReadProgressRequest request);
    @GET("work/get_favorite_works") Call<FavoritesResponse> getFavorites(@Query("page") int page);
    @GET("user/get_user_page_header") Call<UserDetailResponse> getUserDetail(@Query("username") String username);
    @GET("user/get_user_page_tab") Call<UserWorksResponse> getUserTopics(
            @Query("username") String username, @Query("tab") String tab, @Query("page") int page);
    @GET("tag/get_tag") Call<TagInfo> getTag(@Query("tag_name") String tagName);
    @GET("tag/get_tag") Call<TagInfo> getTagViewer(@Header("Token") String token, @Query("tag_name") String tagName);
    @GET("tag/get_tag_works") Call<TopicListResponse> getTagTopics(
            @Query("tag_name") String tagName, @Query("page") int page, @Query("rank") String rank);
    @GET("tag/list_tags") Call<List<TagGroup>> getTags(@Query("page") int page, @Query("keyword") String keyword);
    @POST("user/update_read_progress") Call<ReadProgress> saveReadingProgress(@Body ReadProgress progress);
    @GET Call<UpdateResponse> checkUpdate(@Url String url);
}
