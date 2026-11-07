package com.folio.reader;

import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.speech.tts.*;
import org.json.*;
import java.util.*;

public final class PlaybackService extends Service {
    public interface Listener {void changed();}
    public final class LocalBinder extends Binder {public PlaybackService service(){return PlaybackService.this;}}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LocalBinder binder=new LocalBinder();
    private LibraryDb db;private SharedPreferences prefs;private TextToSpeech tts;private AudioManager audio;private AudioFocusRequest focus;
    private MediaSession session;private PowerManager.WakeLock wake;private Listener listener;
    private List<ReadingText.Unit> units=new ArrayList<>();private String bookId="",engine="",engineLabel="",error="",utterance="";
    private int index=0,sequence=0,engineGeneration=0,rangeStart=-1,rangeEnd=-1;private boolean ready=false,playing=false,focused=false,pendingPlay=false,foreground=false,destroyed=false;
    private Runnable engineTimeout;
    private boolean previewing=false,engineInitializing=false;private String previewId="",activeVoice="";
    private final Runnable previewTimeout=()->{if(previewing){error="The voice sample timed out. Try another installed offline voice.";pause();}};
    private long spokenAt=0,sleepAt=0;private boolean sleepAfterPage=false;private int sleepPage=0;
    private float rate=1f,pitch=1f;private int pauseMs=400;private String voiceName="";
    private final Runnable next=this::speakCurrent;
    private final Runnable ticker=new Runnable(){public void run(){
        if(playing){creditTime();if(sleepAt>0&&SystemClock.elapsedRealtime()>=sleepAt){sleepAt=0;pause();}else{notifyListener();updateSession();}}
        handler.postDelayed(this,1000);
    }};
    private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){pause();}};

    @Override public void onCreate(){
        super.onCreate();db=new LibraryDb(this);prefs=getSharedPreferences("folio",MODE_PRIVATE);
        rate=prefs.getFloat("rate",1f);pitch=prefs.getFloat("pitch",1f);pauseMs=prefs.getInt("pauseMs",400);voiceName=prefs.getString("voice","");
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes()).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{if(change<0)pause();},handler).build();
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Folio:Playback");wake.setReferenceCounted(false);
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("reading","Audiobook playback",NotificationManager.IMPORTANCE_LOW));
        session=new MediaSession(this,"Folio");session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){requestPlay();}@Override public void onPause(){pause();}@Override public void onStop(){pause();stopSelf();}
            @Override public void onSkipToNext(){skip(1);}@Override public void onSkipToPrevious(){skip(-1);}
            @Override public void onFastForward(){skip(1);}@Override public void onRewind(){skip(-1);}
        });session.setActive(true);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),RECEIVER_NOT_EXPORTED);else registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        String last=prefs.getString("book","");if(db.contains(last))open(last);
        initializeEngine(prefs.getString("engine","com.google.android.tts"));
        handler.post(ticker);
    }
    private AudioAttributes attributes(){return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();
        if("PLAY".equals(action)){promote();play();}
        else if("PAUSE".equals(action))pause();else if("NEXT".equals(action))skip(1);else if("PREV".equals(action))skip(-1);else if("STOP".equals(action)){pause();stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf();}
        return START_NOT_STICKY;
    }
    public void setListener(Listener l){listener=l;}
    private void notifyListener(){if(!destroyed&&listener!=null)listener.changed();}
    private void initializeEngine(String requested){
        if(destroyed)return;
        pause();ready=false;engineInitializing=true;activeVoice="";error="";
        if(engineTimeout!=null)handler.removeCallbacks(engineTimeout);
        if(tts!=null){tts.shutdown();tts=null;}
        List<ResolveInfo> services=getPackageManager().queryIntentServices(new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),0);
        String chosen="";engineLabel="System speech engine";
        for(ResolveInfo r:services)if(r.serviceInfo.packageName.equals(requested)){chosen=requested;engineLabel=r.loadLabel(getPackageManager()).toString();break;}
        engine=chosen;final int generation=++engineGeneration;
        engineTimeout=()->{
            if(destroyed||generation!=engineGeneration||ready)return;
            ++engineGeneration;engineInitializing=false;
            error="The speech engine timed out. Open Android speech settings, then reload voices.";
            pause();if(tts!=null){tts.shutdown();tts=null;}
        };
        handler.postDelayed(engineTimeout,30_000);
        TextToSpeech.OnInitListener init=status->handler.post(()->{
            if(destroyed||generation!=engineGeneration||tts==null)return;
            handler.removeCallbacks(engineTimeout);
            engineInitializing=false;
            ready=status==TextToSpeech.SUCCESS;
            if(!ready){error="The speech engine could not start. Open Android speech settings to install a voice, then reload the engine.";pendingPlay=false;pause();notifyListener();return;}
            if(engine.isEmpty()){engine=tts.getDefaultEngine();for(TextToSpeech.EngineInfo info:tts.getEngines())if(info.name.equals(engine))engineLabel=info.label;}
            tts.setAudioAttributes(attributes());tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                @Override public void onStart(String id){handler.post(()->{if(valid(id)){spokenAt=SystemClock.elapsedRealtime();notifyListener();}else if(validPreview(id))notifyListener();});}
                @Override public void onDone(String id){handler.post(()->{if(validPreview(id))pause();else completed(id);});}
                @Override public void onStop(String id,boolean interrupted){handler.post(()->{if(valid(id)||validPreview(id)){error="The speech engine stopped. Try another installed offline voice.";pause();}});}
                @Override public void onError(String id){onError(id,TextToSpeech.ERROR);}
                @Override public void onError(String id,int code){handler.post(()->{if(valid(id)||validPreview(id)){error="This offline voice could not speak (code "+code+"). Download its voice data or choose another installed offline voice.";pause();}});}
                @Override public void onRangeStart(String id,int start,int end,int frame){handler.post(()->{if(valid(id)){rangeStart=start;rangeEnd=end;notifyListener();}});}
            });
            applyVoice();notifyListener();if(pendingPlay){pendingPlay=false;play();}
        });
        tts=chosen.isEmpty()?new TextToSpeech(this,init):new TextToSpeech(this,init,chosen);
        notifyListener();
    }
    private boolean valid(String id){return !destroyed&&playing&&utterance.equals(id);}
    private boolean validPreview(String id){return !destroyed&&previewing&&previewId.equals(id);}
    private void applyVoice(){
        if(!ready)return;
        tts.setSpeechRate(rate);tts.setPitch(pitch);
        Locale locale=Locale.forLanguageTag(prefs.getString("language",Locale.getDefault().toLanguageTag()));
        Voice selected=OfflineVoices.choose(tts.getVoices(),voiceName,locale);activeVoice="";
        if(selected==null||tts.setVoice(selected)!=TextToSpeech.SUCCESS){error="No usable offline voice is selected. Choose Automatic or download offline voice data for this language, then reload voices.";return;}
        // Check the engine's actual selection as well as the requested voice.
        Voice actual=tts.getVoice();
        if(actual==null||actual.isNetworkConnectionRequired()||!OfflineVoices.installed(actual)){error="The engine did not select an installed offline voice. Playback was stopped.";return;}
        activeVoice=actual.getName();
    }
    public void previewVoice(){
        pause();error="";
        if(!ready){error="Wait for the speech engine, or reload voices in Settings.";notifyListener();return;}
        applyVoice();if(!error.isEmpty()){notifyListener();return;}
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){error="Audio is in use by another app. Try the voice sample again in a moment.";notifyListener();return;}
        focused=true;previewing=true;previewId="preview-"+(++sequence);
        String sample="Welcome to Folio. A good voice makes room for the story. Take a moment, listen to the rhythm, and choose the voice that feels right for your next chapter.";
        if(tts.speak(sample,TextToSpeech.QUEUE_FLUSH,new Bundle(),previewId)==TextToSpeech.ERROR){error="This offline voice could not play a sample. Try another downloaded voice.";pause();return;}
        handler.postDelayed(previewTimeout,45_000);notifyListener();
    }
    public void stopPreview(){if(previewing)pause();}
    public void open(String id){
        if(id.equals(bookId)&&!units.isEmpty()){notifyListener();return;}
        JSONObject book=db.getBook(id);if(book==null)return;
        pause();sleepAfterPage=false;bookId=id;units=db.units(id);index=Math.max(0,Math.min(book.optInt("position"),units.size()));
        prefs.edit().putString("book",id).apply();db.position(id,index,false);error="";updateSession();notifyListener();
    }
    public void requestPlay(){
        if(destroyed)return;
        if(units.isEmpty()){error="Import and open a PDF first.";notifyListener();return;}
        startForegroundService(new Intent(this,PlaybackService.class).setAction("PLAY"));
    }
    public void play(){
        if(destroyed||playing)return;
        if(previewing)pause();
        if(units.isEmpty()){pause();return;}
        if(!ready){
            if(!engineInitializing){error="The speech engine is unavailable. Reload voices in Settings.";pause();return;}
            pendingPlay=true;error="Waiting for the speech engine. If this persists, reload it in Settings.";notifyListener();return;
        }
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){error="Audio is in use by another app. Try again when it finishes.";pause();return;}
        focused=true;error="";applyVoice();
        if(!error.isEmpty()){pause();return;}
        if(index>=units.size())index=0;
        playing=true;promote();wake.acquire(12L*60*60*1000);speakCurrent();updateSession();notifyListener();
    }
    public void pause(){
        creditTime();spokenAt=0;playing=false;pendingPlay=false;previewing=false;previewId="";utterance="";rangeStart=-1;rangeEnd=-1;handler.removeCallbacks(next);handler.removeCallbacks(previewTimeout);
        if(tts!=null)tts.stop();if(focused){audio.abandonAudioFocusRequest(focus);focused=false;}if(wake!=null&&wake.isHeld())wake.release();
        // Completion is credited only by a successful final utterance, never by seeking.
        if(db!=null&&!bookId.isEmpty())db.position(bookId,index,false);
        if(session!=null)updateSession();if(foreground){stopForeground(STOP_FOREGROUND_DETACH);foreground=false;updateNotification();}
        stopSelf();notifyListener();
    }
    private void creditTime(){if(spokenAt>0){long now=SystemClock.elapsedRealtime();db.credit(Math.max(0,now-spokenAt),0);spokenAt=now;}}
    private void speakCurrent(){
        if(!playing||!ready)return;
        if(sleepAt>0&&SystemClock.elapsedRealtime()>=sleepAt){sleepAt=0;pause();return;}
        if(index>=units.size()){pause();return;}
        rangeStart=-1;rangeEnd=-1;utterance="read-"+(++sequence)+"-"+index;
        Bundle params=new Bundle();params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f);
        if(tts.speak(units.get(index).text,TextToSpeech.QUEUE_FLUSH,params,utterance)==TextToSpeech.ERROR){error="The speech engine rejected this text. Try another voice.";pause();}
        updateSession();updateNotification();notifyListener();
    }
    private void completed(String id){
        if(!valid(id))return;
        creditTime();spokenAt=0;ReadingText.Unit done=units.get(index);db.credit(0,done.words);
        index++;utterance="";db.position(bookId,index,index>=units.size());rangeStart=-1;rangeEnd=-1;
        if(index>=units.size()||(sleepAfterPage&&units.get(index).page!=sleepPage)){sleepAfterPage=false;pause();return;}
        notifyListener();handler.postDelayed(next,done.sentenceEnd?pauseMs:0);
    }
    public void seek(int target){
        boolean resume=playing;pause();index=Math.max(0,Math.min(target,units.size()));
        if(sleepAfterPage){if(index<units.size())sleepPage=units.get(index).page;else sleepAfterPage=false;}
        db.position(bookId,index,false);updateSession();updateNotification();notifyListener();
        if(resume&&index<units.size())requestPlay();
    }
    public void skip(int delta){seek(index+delta);}
    public void page(int page){for(int i=0;i<units.size();i++)if(units.get(i).page>=page){seek(i);return;}error="That page has no readable text.";notifyListener();}
    public void sleep(int minutes){sleepAfterPage=false;sleepAt=minutes>0?SystemClock.elapsedRealtime()+Math.min(180,minutes)*60_000L:0;notifyListener();}
    public void sleepPage(){sleepAt=0;sleepAfterPage=!units.isEmpty();sleepPage=units.isEmpty()?0:units.get(Math.min(index,units.size()-1)).page;notifyListener();}
    public boolean bookmark(String label){
        if(units.isEmpty())return false;
        int position=Math.min(index,units.size()-1);String text=units.get(position).text;
        db.mark(bookId,position,label.trim().isEmpty()?text.substring(0,Math.min(90,text.length())):label);
        notifyListener();return true;
    }
    public void unmark(int position){db.unmark(bookId,position);notifyListener();}
    public void remove(String id){if(id.equals(bookId)){pause();bookId="";units.clear();index=0;sleepAfterPage=false;prefs.edit().remove("book").apply();updateSession();getSystemService(NotificationManager.class).cancel(1);}db.remove(id);if(id.matches("[a-f0-9]{64}"))PdfImporter.bookFile(this,id).delete();notifyListener();}
    public void configure(JSONObject value){
        String nextEngine=value.optString("engine",prefs.getString("engine","com.google.android.tts"));
        rate=(float)Math.max(.5,Math.min(3,value.optDouble("rate",rate)));pitch=(float)Math.max(.5,Math.min(2,value.optDouble("pitch",pitch)));pauseMs=Math.max(0,Math.min(5000,value.optInt("pauseMs",pauseMs)));
        voiceName=value.optString("voice",voiceName);
        SharedPreferences.Editor edit=prefs.edit().putFloat("rate",rate).putFloat("pitch",pitch).putInt("pauseMs",pauseMs).putString("voice",voiceName).putString("engine",nextEngine);
        for(String key:new String[]{"theme","language"})if(value.has(key))edit.putString(key,value.optString(key));
        if(value.has("ocr"))edit.putBoolean("ocr",value.optBoolean("ocr"));if(value.has("goal"))edit.putInt("goal",Math.max(5,Math.min(180,value.optInt("goal",30))));
        boolean changed=!nextEngine.equals(prefs.getString("engine","com.google.android.tts"));edit.apply();
        if(changed){initializeEngine(nextEngine);return;}
        boolean speechChange=value.has("rate")||value.has("pitch")||value.has("pauseMs")||value.has("voice")||value.has("language");
        if(speechChange){boolean resume=playing;pause();error="";applyVoice();if(resume)requestPlay();}notifyListener();
    }
    public void reloadEngine(){initializeEngine(prefs.getString("engine","com.google.android.tts"));}
    public JSONObject snapshot(){
        JSONArray context=new JSONArray();
        int current=Math.min(index,Math.max(0,units.size()-1));int currentPage=units.isEmpty()?0:units.get(current).page;
        for(int i=Math.max(0,current-6);i<Math.min(units.size(),current+10);i++)context.put(LibraryDb.json("index",i,"text",units.get(i).text,"page",units.get(i).page));
        return LibraryDb.json("book",db.getBook(bookId),"playing",playing,"previewing",previewing,"ready",ready,"index",index,"page",currentPage,"total",units.size(),"context",context,"rangeStart",rangeStart,"rangeEnd",rangeEnd,"error",error,"engineLabel",engineLabel,"engine",engine,"sleepSeconds",sleepAt==0?0:Math.max(0,(sleepAt-SystemClock.elapsedRealtime())/1000),"sleepPage",sleepAfterPage,"rate",rate,"pauseMs",pauseMs,"pitch",pitch,"bookmarks",db.bookmarks(bookId));
    }
    public JSONObject settings(){
        JSONArray voices=new JSONArray(),engines=new JSONArray();
        if(ready&&tts!=null){
            Set<Voice> all=tts.getVoices();
            if(all!=null){
                ArrayList<Voice> sorted=new ArrayList<>(all);sorted.removeIf(Voice::isNetworkConnectionRequired);
                sorted.sort(Comparator.comparing((Voice v)->v.getLocale().getDisplayName()).thenComparing(Comparator.comparingInt(Voice::getQuality).reversed()).thenComparing(Voice::getName));
                Map<String,Integer> numbers=new HashMap<>();
                for(Voice v:sorted){String language=v.getLocale().toLanguageTag();int number=numbers.merge(language,1,Integer::sum);
                    voices.put(LibraryDb.json("name",v.getName(),"label",v.getLocale().getDisplayName()+" · Voice "+number,"language",language,"network",false,"installed",OfflineVoices.installed(v),"quality",OfflineVoices.quality(v)));
                }
            }
            for(TextToSpeech.EngineInfo e:tts.getEngines())engines.put(LibraryDb.json("name",e.name,"label",e.label));
        }
        return LibraryDb.json("rate",rate,"pitch",pitch,"pauseMs",pauseMs,"voice",voiceName,"activeVoice",activeVoice,"offlineOnly",true,"engine",prefs.getString("engine","com.google.android.tts"),"activeEngine",engine,"voices",voices,"engines",engines,"language",prefs.getString("language",Locale.getDefault().toLanguageTag()),"theme",prefs.getString("theme","light"),"ocr",prefs.getBoolean("ocr",true),"goal",prefs.getInt("goal",30));
    }
    public JSONArray search(String query){JSONArray result=new JSONArray();if(query.trim().isEmpty())return result;String q=query.toLowerCase(Locale.ROOT);for(int i=0;i<units.size()&&result.length()<80;i++)if(units.get(i).text.toLowerCase(Locale.ROOT).contains(q))result.put(LibraryDb.json("index",i,"page",units.get(i).page,"text",units.get(i).text));return result;}
    public String bookId(){return bookId;}
    private PendingIntent action(String action){Intent intent=new Intent(this,PlaybackService.class).setAction(action);return "PLAY".equals(action)?PendingIntent.getForegroundService(this,action.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE):PendingIntent.getService(this,action.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){
        JSONObject book=db.getBook(bookId);String title=book==null?"Folio":book.optString("title");
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"reading").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText((playing?"Listening":"Paused")+" · "+rate+"×").setContentIntent(open).setDeleteIntent(action("STOP")).setOnlyAlertOnce(true).setOngoing(playing).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"Previous sentence",action("PREV")).build())
            .addAction(new Notification.Action.Builder(playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,playing?"Pause":"Play",action(playing?"PAUSE":"PLAY")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"Next sentence",action("NEXT")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop",action("STOP")).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2)).build();
    }
    private void promote(){startForeground(1,notification());foreground=true;}
    private void updateNotification(){if(!bookId.isEmpty())getSystemService(NotificationManager.class).notify(1,notification());}
    private void updateSession(){
        if(session==null)return;JSONObject book=db.getBook(bookId);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,book==null?"Folio":book.optString("title")).putString(MediaMetadata.METADATA_KEY_ARTIST,"Folio · PDF audiobooks").build());
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_FAST_FORWARD|PlaybackState.ACTION_REWIND).setState(playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,PlaybackState.PLAYBACK_POSITION_UNKNOWN,rate).build());
    }
    @Override public void onDestroy(){destroyed=true;++engineGeneration;pause();ready=false;handler.removeCallbacksAndMessages(null);listener=null;if(tts!=null){tts.shutdown();tts=null;}if(session!=null)session.release();unregisterReceiver(noisy);db.close();super.onDestroy();}
}
