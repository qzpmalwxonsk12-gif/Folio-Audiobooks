package com.folio.reader;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Android service behavior with simulated TTS callbacks; does not test a physical speech engine. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class PlaybackServiceTest {
    private Context context;
    private LibraryDb db;
    private ServiceController<PlaybackService> controller;
    private PlaybackService service;

    @Before public void setUp() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("folio",Context.MODE_PRIVATE).edit().clear().commit();
        db=new LibraryDb(context);
        db.add("test-book","A test book",2,Arrays.asList(
            new ReadingText.Unit(1,"First sentence.",true),
            new ReadingText.Unit(1,"Second sentence.",true),
            new ReadingText.Unit(2,"Last sentence.",true)),0,0);
        db.add("other-book","Another book",1,Collections.singletonList(new ReadingText.Unit(1,"Another sentence.",true)),0,0);
        controller=Robolectric.buildService(PlaybackService.class).create();
        service=controller.get();service.open("test-book");
    }
    @After public void tearDown(){if(controller!=null)controller.destroy();db.close();}

    @Test public void nextAtEndDoesNotRequestReplay() {
        service.seek(2);
        ReflectionHelpers.setField(service,"playing",true);
        service.skip(1);
        assertEquals(3,service.snapshot().optInt("index"));
        assertFalse(service.snapshot().optBoolean("playing"));
        assertNull("Next at the end must not enqueue a new PLAY",shadowOf((Application)context).getNextStartedService());
    }
    @Test public void seekingPastEndDoesNotMarkUnreadBookFinished() {
        service.seek(999);
        service.pause();
        assertFalse(db.getBook("test-book").optBoolean("completed"));
    }
    @Test public void pageTimerDoesNotFollowAnotherBook() {
        service.sleepPage();service.open("other-book");
        assertFalse(service.snapshot().optBoolean("sleepPage"));
    }
    @Test public void pageTimerTracksPageChosenBySeek() {
        service.sleepPage();service.seek(2);
        assertEquals(2,(int)ReflectionHelpers.getField(service,"sleepPage"));
    }
    @Test public void bookmarkAtEndSavesLastSentence() {
        service.seek(3);service.bookmark("End passage");
        assertEquals(1,service.snapshot().optJSONArray("bookmarks").length());
        assertEquals(2,service.snapshot().optJSONArray("bookmarks").optJSONObject(0).optInt("index"));
    }
    @Test public void delayedEngineCallbackCannotAccessDestroyedService() {
        TextToSpeech engine=ShadowTextToSpeech.getLastTextToSpeechInstance();
        TextToSpeech.OnInitListener callback=shadowOf(engine).getOnInitListener();
        controller.destroy();controller=null;
        callback.onInit(TextToSpeech.SUCCESS);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse((boolean)ReflectionHelpers.getField(service,"ready"));
    }
    @Test public void waitingForEngineTimesOutAndStopsForeground() {
        service.onStartCommand(new Intent(context,PlaybackService.class).setAction("PLAY"),0,1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        assertFalse((boolean)ReflectionHelpers.getField(service,"pendingPlay"));
        assertFalse((boolean)ReflectionHelpers.getField(service,"foreground"));
        assertTrue(service.snapshot().optString("error").contains("timed out"));
    }
    @Test public void completedSpeechCreditsWordsOnlyOnce() {
        ReflectionHelpers.setField(service,"playing",true);
        ReflectionHelpers.setField(service,"utterance","unit-1");
        ReflectionHelpers.callInstanceMethod(service,"completed",ReflectionHelpers.ClassParameter.from(String.class,"unit-1"));
        ReflectionHelpers.callInstanceMethod(service,"completed",ReflectionHelpers.ClassParameter.from(String.class,"unit-1"));
        assertEquals(1,service.snapshot().optInt("index"));
        assertEquals(2,db.stats().optInt("words"));
    }
    @Test public void callbackAfterPauseDoesNotAdvanceOrCredit() {
        ReflectionHelpers.setField(service,"playing",true);
        ReflectionHelpers.setField(service,"utterance","unit-1");
        service.pause();
        ReflectionHelpers.callInstanceMethod(service,"completed",ReflectionHelpers.ClassParameter.from(String.class,"unit-1"));
        assertEquals(0,service.snapshot().optInt("index"));
        assertEquals(0,db.stats().optInt("words"));
    }
    @Test public void removingBookPreservesListeningTotals() {
        db.credit(1200,2);service.bookmark("A passage");service.remove("test-book");
        assertFalse(db.contains("test-book"));
        assertTrue(db.units("test-book").isEmpty());
        assertEquals(0,db.bookmarks("test-book").length());
        assertEquals(1200,db.stats().optLong("millis"));
        assertEquals(2,db.stats().optInt("words"));
    }
    @Test public void previewDoesNotChangeBookPositionOrListeningStats() {
        ShadowTextToSpeech.addVoice(new Voice("offline-us",Locale.US,Voice.QUALITY_HIGH,Voice.LATENCY_NORMAL,false,Collections.emptySet()));
        context.getSharedPreferences("folio",Context.MODE_PRIVATE).edit().putString("language","en-US").commit();
        TextToSpeech engine=ShadowTextToSpeech.getLastTextToSpeechInstance();
        shadowOf(engine).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        shadowOf(Looper.getMainLooper()).idle();
        service.seek(1);db.credit(1234,9);
        service.previewVoice();
        assertTrue(service.snapshot().optBoolean("previewing"));
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(service.snapshot().optBoolean("previewing"));
        assertEquals(1,service.snapshot().optInt("index"));
        assertEquals(9,db.stats().optInt("words"));
        assertEquals(1234,db.stats().optLong("millis"));
    }
    @Test public void onlineOnlyEngineNeverSpeaks() {
        ShadowTextToSpeech.addVoice(new Voice("online-us",Locale.US,Voice.QUALITY_VERY_HIGH,Voice.LATENCY_NORMAL,true,Collections.emptySet()));
        context.getSharedPreferences("folio",Context.MODE_PRIVATE).edit().putString("language","en-US").commit();
        TextToSpeech engine=ShadowTextToSpeech.getLastTextToSpeechInstance();
        shadowOf(engine).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        shadowOf(Looper.getMainLooper()).idle();
        service.previewVoice();service.play();
        assertFalse(service.snapshot().optBoolean("playing"));
        assertFalse(service.snapshot().optBoolean("previewing"));
        assertTrue(shadowOf(engine).getSpokenTextList().isEmpty());
        assertTrue(service.snapshot().optString("error").contains("offline"));
    }
}
