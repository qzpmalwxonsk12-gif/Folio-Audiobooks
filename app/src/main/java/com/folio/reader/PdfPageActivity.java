package com.folio.reader;

import android.app.Activity;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

public final class PdfPageActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private PdfRenderer renderer;private ImageView image;private TextView label;private int current,count;private Bitmap displayed;private boolean busy;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(247,245,239));
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,i)->{Insets inset=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(inset.left,inset.top,inset.right,inset.bottom);return i;});}else root.setFitsSystemWindows(true);
        LinearLayout bar=new LinearLayout(this);Button close=new Button(this);close.setText("Back");close.setOnClickListener(v->finish());bar.addView(close);
        label=new TextView(this);label.setText("Original PDF");label.setGravity(Gravity.CENTER);bar.addView(label,new LinearLayout.LayoutParams(0,-1,1));root.addView(bar);
        image=new ImageView(this);image.setContentDescription("Original PDF page. Use the text reader for accessible text.");image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(this);Button previous=new Button(this);previous.setText("Previous page");previous.setOnClickListener(v->show(current-1));Button next=new Button(this);next.setText("Next page");next.setOnClickListener(v->show(current+1));controls.addView(previous,new LinearLayout.LayoutParams(0,-2,1));controls.addView(next,new LinearLayout.LayoutParams(0,-2,1));root.addView(controls);setContentView(root);
        String id=getIntent().getStringExtra("id");
        if(id==null||!id.matches("[a-f0-9]{64}")){label.setText("This sample has no original PDF");return;}
        File file=PdfImporter.bookFile(this,id);
        final int initialPage=saved==null?getIntent().getIntExtra("page",1)-1:saved.getInt("page",0);
        worker.execute(()->{
            ParcelFileDescriptor descriptor=null;
            try{
                descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
                renderer=new PdfRenderer(descriptor);descriptor=null; // Renderer now owns the descriptor.
                count=renderer.getPageCount();runOnUiThread(()->show(initialPage));
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())label.setText("Preview unavailable for this PDF. Use the text reader.");});}
            finally{if(descriptor!=null)try{descriptor.close();}catch(java.io.IOException ignored){}}
        });
    }
    private void show(int index){
        if(busy||renderer==null||index<0||index>=count||isDestroyed())return;busy=true;current=index;label.setText("Page "+(index+1)+" of "+count);
        worker.execute(()->{
            Bitmap bitmap=null;
            try(PdfRenderer.Page page=renderer.openPage(index)){
                float scale=1800f/Math.max(page.getWidth(),page.getHeight());bitmap=Bitmap.createBitmap(Math.max(1,Math.round(page.getWidth()*scale)),Math.max(1,Math.round(page.getHeight()*scale)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap ready=bitmap;
                runOnUiThread(()->{busy=false;if(isDestroyed()){ready.recycle();return;}image.setImageBitmap(ready);if(displayed!=null)displayed.recycle();displayed=ready;});
                bitmap=null; // The UI callback owns the successful render.
            }catch(Exception e){runOnUiThread(()->{busy=false;if(!isDestroyed())label.setText("Could not render this page");});}
            finally{if(bitmap!=null)bitmap.recycle();}
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("page",current);super.onSaveInstanceState(state);}
    @Override public void onDestroy(){worker.execute(()->{if(renderer!=null)renderer.close();});worker.shutdown();if(displayed!=null){image.setImageDrawable(null);displayed.recycle();}super.onDestroy();}
}
