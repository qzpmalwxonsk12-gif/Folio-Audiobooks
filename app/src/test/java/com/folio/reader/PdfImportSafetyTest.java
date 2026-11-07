package com.folio.reader;

import android.app.Application;
import android.graphics.Bitmap;
import com.google.android.gms.tasks.TaskCompletionSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class PdfImportSafetyTest {
    @Test public void missingOrEmptyDisplayNameHasFallback(){
        assertEquals("Untitled book",PdfImporter.bookTitle(null));
        assertEquals("Untitled book",PdfImporter.bookTitle(" .PDF "));
        assertEquals("My book",PdfImporter.bookTitle(" My  book.PdF "));
    }
    @Test public void longTitleDoesNotSplitSurrogatePair(){
        String title=PdfImporter.bookTitle("x".repeat(199)+"\uD83D\uDC31"+"extra.pdf");
        assertEquals(200,title.codePointCount(0,title.length()));
        assertTrue(title.endsWith("\uD83D\uDC31"));
    }
    @Test public void pendingRecognitionRetainsBitmapUntilSuccess(){
        TaskCompletionSource<String> source=new TaskCompletionSource<>();
        Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        PdfImporter.releaseBitmap(bitmap,source.getTask());
        assertFalse(bitmap.isRecycled());
        source.setResult("done");assertTrue(bitmap.isRecycled());
    }
    @Test public void pendingRecognitionRetainsBitmapUntilFailure(){
        TaskCompletionSource<String> source=new TaskCompletionSource<>();
        Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        PdfImporter.releaseBitmap(bitmap,source.getTask());
        assertFalse(bitmap.isRecycled());
        source.setException(new Exception("recognition failed"));assertTrue(bitmap.isRecycled());
    }
    @Test public void renderFailureBeforeRecognitionRecyclesImmediately(){
        Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        PdfImporter.releaseBitmap(bitmap,null);assertTrue(bitmap.isRecycled());
    }
}
