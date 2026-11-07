package com.folio.reader;

import android.app.Application;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class OfflineVoicesTest {
    private Voice voice(String name,Locale locale,int quality,boolean online,boolean installed){return new Voice(name,locale,quality,Voice.LATENCY_NORMAL,online,installed?Collections.emptySet():Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));}
    @Test public void automaticPrefersQualityButNeverNetworkOrMissingVoices(){
        Voice basic=voice("basic",Locale.US,Voice.QUALITY_NORMAL,false,true),better=voice("better",Locale.US,Voice.QUALITY_HIGH,false,true);
        Set<Voice> all=new HashSet<>(Arrays.asList(basic,better,voice("cloud",Locale.US,Voice.QUALITY_VERY_HIGH,true,true),voice("missing",Locale.US,Voice.QUALITY_VERY_HIGH,false,false)));
        assertSame(better,OfflineVoices.choose(all,"",Locale.US));
    }
    @Test public void selectingNetworkVoiceIsRejectedWithoutFallback(){
        Set<Voice> all=new HashSet<>(Arrays.asList(voice("offline",Locale.US,300,false,true),voice("online",Locale.US,500,true,true)));
        assertNull(OfflineVoices.choose(all,"online",Locale.US));
    }
    @Test public void selectedAccentPrecedesQualityOfOtherAccents(){
        Voice british=voice("british",Locale.UK,300,false,true),american=voice("american",Locale.US,500,false,true);
        assertSame(british,OfflineVoices.choose(new HashSet<>(Arrays.asList(british,american)),"",Locale.UK));
    }
    @Test public void explicitInstalledVoiceIsRespected(){
        Voice chosen=voice("chosen",Locale.US,300,false,true),other=voice("other",Locale.US,500,false,true);
        assertSame(chosen,OfflineVoices.choose(new HashSet<>(Arrays.asList(chosen,other)),"chosen",Locale.US));
    }
    @Test public void unavailableLanguageDoesNotSilentlyUseAnotherLanguage(){
        assertNull(OfflineVoices.choose(Collections.singleton(voice("english",Locale.US,300,false,true)),"",Locale.FRENCH));
    }
}
