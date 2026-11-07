package com.folio.reader;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.*;

/** Never substitute a network voice when offline speech is requested. */
final class OfflineVoices {
    static boolean installed(Voice voice){
        return voice.getFeatures()==null||!voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }
    static Voice choose(Set<Voice> voices,String requested,Locale language){
        if(voices==null)return null;
        if(!requested.isEmpty()){
            for(Voice voice:voices)if(voice.getName().equals(requested))
                return !voice.isNetworkConnectionRequired()&&installed(voice)?voice:null;
            return null;
        }
        return voices.stream().filter(v->!v.isNetworkConnectionRequired()&&installed(v)&&v.getLocale().getLanguage().equals(language.getLanguage()))
            .sorted(Comparator.comparingInt((Voice v)->v.getLocale().equals(language)?0:v.getLocale().getCountry().equals(language.getCountry())?1:2)
                .thenComparing(Comparator.comparingInt(Voice::getQuality).reversed())
                .thenComparingInt(Voice::getLatency).thenComparing(Voice::getName))
            .findFirst().orElse(null);
    }
    static String quality(Voice voice){
        return voice.getQuality()>=Voice.QUALITY_HIGH?"Enhanced quality":voice.getQuality()>=Voice.QUALITY_NORMAL?"Standard quality":"Basic quality";
    }
    private OfflineVoices(){}
}
