package com.fimtale.community;

import android.content.ContextWrapper;
import android.content.Intent;
import com.fimtale.network.SiteUrls;
import org.junit.Test;
import org.robolectric.RuntimeEnvironment;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34, application = com.fimtale.ResourceApplication.class)
public class CommunityLinksTest {
    @Test public void workAndChapterCommentAnchorsOpenTheSameNativeDestination() {
        AtomicReference<Intent> launched = new AtomicReference<>();
        ContextWrapper context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public void startActivity(Intent intent) { launched.set(intent); }
        };
        for (String path : new String[]{"/work/42#comment-77", SiteUrls.SITE + "/work/42/chapter/101#comment-77"}) {
            assertTrue(CommunityLinks.openComment(context, path));
            assertEquals(CommentDetailActivity.class.getName(), launched.get().getComponent().getClassName());
            assertEquals(77, launched.get().getIntExtra("comment_id", 0));
        }
        launched.set(null);
        for (String path : new String[]{"/channel/42#comment-77", "https://elsewhere.invalid/work/42#comment-77",
                "//elsewhere.invalid/work/42#comment-77", "/work/42#comment-0", "/work/42#comment-999999999999999999"})
            assertFalse(CommunityLinks.openComment(context, path));
        assertNull(launched.get());
    }
}
