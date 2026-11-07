package com.folio.reader;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private WebView web;private PlaybackService service;private LibraryDb db;private boolean bound=false,binding=false,loaded=false,visible=false,lastReady=false;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean importing=new AtomicBoolean(false),cancel=new AtomicBoolean(false);
    private JSONObject importState=LibraryDb.json("active",false);private String exportContent="",pendingBook="";private boolean closing=false;
    private final Runnable refresh=new Runnable(){public void run(){if(visible){push(false);main.postDelayed(this,1500);}}};
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){if(closing)return;service=((PlaybackService.LocalBinder)binder).service();bound=true;service.setListener(()->{if(visible)push(false);});if(!pendingBook.isEmpty()){service.open(pendingBook);pendingBook="";}push(true);}
        public void onServiceDisconnected(ComponentName name){bound=false;service=null;}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);db=new LibraryDb(this);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(247,245,239));
        web=new WebView(this);root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});}
        else root.setFitsSystemWindows(true);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setSupportZoom(false);settings.setDefaultTextEncodingName("UTF-8");
        web.setBackgroundColor(Color.rgb(247,245,239));web.addJavascriptInterface(new Bridge(),"FolioNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){Uri u=request.getUrl();return !("https".equals(u.getScheme())&&"app.folio.local".equals(u.getHost())&&"/index.html".equals(u.getPath()));}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri u=request.getUrl();
                if("https".equals(u.getScheme())&&"app.folio.local".equals(u.getHost())){
                    String path=u.getPath();if(path==null||path.equals("/"))path="/index.html";
                    if(!Arrays.asList("/index.html","/app.css","/app.js","/core.js").contains(path))return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
                    try {String mime=path.endsWith(".css")?"text/css":path.endsWith(".js")?"application/javascript":"text/html";return new WebResourceResponse(mime,"UTF-8",200,"OK",Collections.singletonMap("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'"),getAssets().open(path.substring(1)));}catch(IOException e){return null;}
                }
                return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView view,String url){loaded=true;push(true);}
        });
        web.setWebChromeClient(new WebChromeClient());web.loadUrl("https://app.folio.local/index.html");
        binding=bindService(new Intent(this,PlaybackService.class),connection,BIND_AUTO_CREATE);
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);}
    private void handleIntent(Intent intent){if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null&&"content".equals(intent.getData().getScheme()))main.postDelayed(()->startImport(intent.getData(),""),500);}
    @Override protected void onResume(){super.onResume();visible=true;push(true);main.removeCallbacks(refresh);main.post(refresh);}
    @Override protected void onPause(){visible=false;if(service!=null)service.stopPreview();main.removeCallbacks(refresh);super.onPause();}
    private void push(boolean full){
        if(!loaded||closing||isDestroyed()||service==null)return;
        JSONObject player=service.snapshot();boolean ready=player.optBoolean("ready");
        JSONObject payload=LibraryDb.json("player",player,"library",db.library(),"stats",db.stats(),"import",importState);
        try{if(full||lastReady!=ready)payload.put("settings",service.settings());}catch(JSONException ignored){}lastReady=ready;
        dispatch("folio-state",payload);
    }
    private void dispatch(String name,JSONObject data){if(loaded&&!closing&&!isDestroyed())web.evaluateJavascript("window.dispatchEvent(new CustomEvent("+JSONObject.quote(name)+",{detail:"+data+"}));",null);}
    private void message(String text){dispatch("folio-message",LibraryDb.json("text",text));}
    private void theme(boolean dark){
        int color=Color.parseColor(dark?"#20241E":"#F7F5EF");web.setBackgroundColor(color);getWindow().getDecorView().setBackgroundColor(color);((View)web.getParent()).setBackgroundColor(color);
        getWindow().setStatusBarColor(color);getWindow().setNavigationBarColor(color);
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null)c.setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
        else getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private final class Bridge {
        @JavascriptInterface public void command(String action,String raw){main.post(()->{
            if(closing)return;
            if(service==null){message("The reader is starting. Please try again in a moment.");return;}
            try{
                JSONObject data=new JSONObject(raw);
                switch(action){
                    case "refresh":push(true);break;
                    case "import":if(!importing.get())startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE),10);break;
                    case "cancelImport":cancel.set(true);break;
                    case "open":service.open(data.getString("id"));break;
                    case "play":if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(MODE_PRIVATE).getBoolean("askedNotification",false)){getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotification",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);}service.requestPlay();break;
                    case "pause":service.pause();break;
                    case "skip":service.skip(data.optInt("delta",1));break;
                    case "seek":service.seek(data.optInt("index"));break;
                    case "page":service.page(data.optInt("page",1));break;
                    case "sleep":if(data.optBoolean("page"))service.sleepPage();else service.sleep(data.optInt("minutes"));break;
                    case "bookmark":message(service.bookmark(data.optString("label",""))?"Bookmark saved":"Open a book before adding a bookmark.");break;
                    case "unmark":service.unmark(data.optInt("index"));break;
                    case "settings":service.configure(data);push(true);break;
                    case "theme":theme(data.optBoolean("dark"));break;
                    case "reloadEngine":service.reloadEngine();main.postDelayed(()->push(true),1500);break;
                    case "previewVoice":service.previewVoice();push(true);break;
                    case "stopPreview":service.stopPreview();break;
                    case "speechSettings":try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(ActivityNotFoundException e){startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}break;
                    case "voiceData":try{Intent i=new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);String engine=service.snapshot().optString("engine");if(!engine.isEmpty())i.setPackage(engine);startActivity(i);}catch(ActivityNotFoundException e){message("Open Android speech settings to install voice data.");}break;
                    case "search":dispatch("folio-search",LibraryDb.json("query",data.optString("query"),"results",service.search(data.optString("query"))));break;
                    case "rename":db.rename(data.getString("id"),data.optString("title"));push(false);break;
                    case "remove":service.remove(data.getString("id"));push(false);break;
                    case "pdf":if(!service.bookId().isEmpty())startActivity(new Intent(MainActivity.this,PdfPageActivity.class).putExtra("id",service.bookId()).putExtra("page",service.snapshot().optInt("page",1)));break;
                    case "exportStats":exportContent=LibraryDb.json("exportedAt",java.time.OffsetDateTime.now().toString(),"stats",db.stats(),"library",db.library()).toString(2);startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"folio-reading-stats.json"),11);break;
                    case "sample":addSample();break;
                }
            }catch(Exception e){message("That action could not finish: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        });}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==10)startImport(data.getData(),"");
        if(request==11){Uri uri=data.getData();String contents=exportContent;worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("File unavailable");out.write(contents.getBytes(StandardCharsets.UTF_8));main.post(()->message("Statistics exported"));}catch(Exception e){main.post(()->message("Could not export statistics. Please choose another location."));}});}
    }
    private void startImport(Uri uri,String password){
        if(closing)return;
        if(!importing.compareAndSet(false,true)){message("A PDF is already being imported.");return;}
        cancel.set(false);importState=LibraryDb.json("active",true,"page",0,"total",0,"message","Opening your PDF…");push(false);
        worker.execute(()->{
            String title="Untitled book";
            try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())title=cursor.getString(0);}catch(Exception ignored){}
            try {
                boolean useOcr=getSharedPreferences("folio",MODE_PRIVATE).getBoolean("ocr",true);
                String id=PdfImporter.run(this,db,uri,title,password,useOcr,cancel,(page,total,text)->main.post(()->{importState=LibraryDb.json("active",true,"page",page,"total",total,"message",text);push(false);}));
                main.post(()->{if(closing)return;importing.set(false);importState=LibraryDb.json("active",false);if(service!=null)service.open(id);else pendingBook=id;push(true);dispatch("folio-imported",LibraryDb.json("id",id));message("Your book is ready");});
            } catch(InvalidPasswordException e){main.post(()->{importing.set(false);importState=LibraryDb.json("active",false);push(false);if(isDestroyed())return;EditText input=new EditText(this);input.setInputType(129);input.setHint("PDF password");new AlertDialog.Builder(this).setTitle("This PDF is password protected").setMessage("The password is used to read this file and is never saved.").setView(input).setPositiveButton("Unlock",(d,w)->startImport(uri,input.getText().toString())).setNegativeButton("Cancel",null).show();});}
            catch(Exception e){main.post(()->{importing.set(false);importState=LibraryDb.json("active",false);push(false);String detail=e.getMessage();message(detail==null?"This PDF could not be read. It may be damaged or unsupported.":detail);});}
        });
    }
    private void addSample(){
        String id="sample-welcome";
        if(!db.contains(id)){
            List<ReadingText.Unit> units=new ArrayList<>();
            units.addAll(ReadingText.split("Welcome to a little more reading time. Folio turns the words in your PDFs into a voice you can take with you. Settle into your favorite chair, or let a good book keep you company on a walk. There is no hurry here.",1));
            units.addAll(ReadingText.split("Make the voice your own. Try a slower pace for a difficult idea, or a quicker one for a familiar story. A small pause between sentences gives each thought room to breathe. Your phone's installed speech engine does the reading.",2));
            units.addAll(ReadingText.split("A few pages, every day. Your place is saved when you pause. Add a bookmark to a passage you love, and set a sleep timer when the day is done. Every listening session is a little time returned to your books. Enjoy the next chapter.",3));
            db.add(id,"A little more reading time",3,units,0,0);
        }
        service.open(id);push(true);dispatch("folio-imported",LibraryDb.json("id",id));
    }
    @Override public void onBackPressed(){web.evaluateJavascript("window.folioBack ? window.folioBack() : false",value->{if(!"true".equals(value))super.onBackPressed();});}
    @Override protected void onDestroy(){closing=true;visible=false;main.removeCallbacksAndMessages(null);cancel.set(true);worker.execute(db::close);worker.shutdown();if(service!=null)service.setListener(null);if(binding)unbindService(connection);service=null;web.removeJavascriptInterface("FolioNative");web.destroy();super.onDestroy();}
}
