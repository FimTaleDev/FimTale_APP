package com.fimtale.editor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = com.fimtale.ResourceApplication.class)
public class EditorTextStatsTest {
    @Test public void countsProseWithoutFormattingWhitespaceOrImageAddresses() {
        assertEquals(4, EditorTextStats.count("[b]你好[/b] \n[i]世界[/i][img]https://example.com/image.png[/img]"));
    }

    @Test public void selectionInsideAFormattedBlockCountsItsVisibleTextOnly() {
        assertEquals(1, EditorTextStats.count("[b]你好[/b]", 4, 5));
        assertEquals(2, EditorTextStats.count("[b]你好[/b]", 0, 9));
    }

    @Test public void entitiesAndSupplementaryCharactersCountOnce() {
        assertEquals(3, EditorTextStats.count("甲&amp;😀"));
    }

    @Test public void codeBlocksKeepTheirLiteralMarkupInTheCount() {
        assertEquals(8, EditorTextStats.count("[code][b]字[/b][/code]"));
    }
}
