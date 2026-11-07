package com.folio.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PdfImporter {
    public interface Progress { void update(int page,int total,String message); }
    public static File bookFile(Context context,String id) { return new File(new File(context.getFilesDir(),"books"),id+".pdf"); }
    private static void check(AtomicBoolean cancel) throws IOException { if(cancel.get()) throw new IOException("Import cancelled."); }
    static String bookTitle(String name){
        String title=ReadingText.normalize(name).replaceFirst("(?i)\\.pdf$","").trim();
        if(title.isEmpty())return "Untitled book";
        return title.substring(0,title.offsetByCodePoints(0,Math.min(200,title.codePointCount(0,title.length()))));
    }
    static void releaseBitmap(Bitmap bitmap,Task<?> task){
        // A timeout does not cancel ML Kit's task. Keep its input alive until it finishes.
        if(task==null)bitmap.recycle();else task.addOnCompleteListener(Runnable::run,finished->bitmap.recycle());
    }

    public static String run(Context context,LibraryDb db,Uri uri,String name,String password,boolean ocr,AtomicBoolean cancel,Progress progress) throws Exception {
        File temp=File.createTempFile("import-",".pdf",context.getCacheDir());
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            progress.update(0,0,"Copying your PDF…");
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)) {
                if(in==null)throw new IOException("This file could not be opened. Please choose it again.");
                byte[] buffer=new byte[65536];long bytes=0;int n;
                while((n=in.read(buffer))!=-1){check(cancel);bytes+=n;if(bytes>150L*1024*1024)throw new IOException("Please use a PDF smaller than 150 MB.");out.write(buffer,0,n);digest.update(buffer,0,n);}
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b));String id=hex.toString();
            if(db.contains(id))return id;
            List<ReadingText.Unit> units=new ArrayList<>();int scanned=0,skipped=0,total;long chars=0;
            TextRecognizer recognizer=null;PdfRenderer renderer=null;ParcelFileDescriptor descriptor=null;
            try(PDDocument document=PDDocument.load(temp,password,MemoryUsageSetting.setupMixed(24L*1024*1024).setTempDir(context.getCacheDir()))) {
                total=document.getNumberOfPages();
                if(total>3000)throw new IOException("This PDF has more than 3,000 pages. Please split it into smaller books.");
                if(!document.getCurrentAccessPermission().canExtractContent())throw new IOException("This PDF restricts text extraction. Please import an unrestricted copy.");
                PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
                for(int page=1;page<=total;page++) {
                    check(cancel);progress.update(page,total,"Reading page "+page+" of "+total);
                    stripper.setStartPage(page);stripper.setEndPage(page);
                    String text=ReadingText.normalize(stripper.getText(document));
                    // Image-only pages use the bundled, on-device Latin text recognizer.
                    if(text.isEmpty()&&ocr&&!document.isEncrypted()) {
                        progress.update(page,total,"Recognizing text on page "+page+" of "+total);
                        if(recognizer==null)recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                        if(renderer==null){descriptor=ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);}
                        try(PdfRenderer.Page p=renderer.openPage(page-1)) {
                            float scale=1800f/Math.max(p.getWidth(),p.getHeight());
                            Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(p.getWidth()*scale)),Math.max(1,Math.round(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);
                            Task<Text> recognition=null;
                            try {
                                bitmap.eraseColor(Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                recognition=recognizer.process(InputImage.fromBitmap(bitmap,0));
                                text=ReadingText.normalize(Tasks.await(recognition,45,TimeUnit.SECONDS).getText());
                                if(!text.isEmpty())scanned++;
                            } catch(TimeoutException e){throw new IOException("Text recognition timed out on page "+page+". Please retry or import an OCR-processed PDF.",e);}
                            finally { releaseBitmap(bitmap,recognition); }
                        }
                    }
                    if(text.isEmpty())skipped++;
                    chars+=text.length();if(chars>10_000_000)throw new IOException("This PDF contains too much text for one book. Please split it into smaller files.");
                    units.addAll(ReadingText.split(text,page));
                }
            } finally {if(recognizer!=null)recognizer.close();if(renderer!=null)renderer.close();else if(descriptor!=null)descriptor.close();}
            check(cancel);
            if(units.isEmpty())throw new IOException(ocr?"No readable text was found. OCR supports Latin-script text; encrypted scans and some image formats need an OCR-processed PDF.":"No text was found. Enable scanned-page recognition in Settings, then try again.");
            File destination=bookFile(context,id);File dir=destination.getParentFile();if(dir!=null&&!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Could not create the book folder.");
            if(!temp.renameTo(destination))throw new IOException("Could not save the book. Check available storage.");
            try {db.add(id,bookTitle(name),total,units,scanned,skipped);}
            catch(Exception e){destination.delete();throw e;}
            return id;
        } finally {temp.delete();}
    }
}
