package com.folio.reader;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ReadingTextTest {
    @Test public void sentencesKeepPunctuationAndPages(){List<ReadingText.Unit> u=ReadingText.split("Hello there. How are you? Very well!",7);assertEquals(3,u.size());assertEquals("Hello there.",u.get(0).text);assertEquals(7,u.get(2).page);assertTrue(u.get(2).sentenceEnd);}
    @Test public void abbreviationsDoNotCreateFalsePauses(){List<ReadingText.Unit> u=ReadingText.split("Dr. Smith met Mr. Jones. They talked.",1);assertEquals(2,u.size());assertEquals("Dr. Smith met Mr. Jones.",u.get(0).text);}
    @Test public void decimalNumbersStayTogether(){List<ReadingText.Unit> u=ReadingText.split("It costs 3.50 dollars. That is fair.",1);assertEquals(2,u.size());assertTrue(u.get(0).text.contains("3.50"));}
    @Test public void blankTextIsNotRead(){assertTrue(ReadingText.split("\n\t \u00a0",1).isEmpty());assertTrue(ReadingText.split(null,1).isEmpty());}
    @Test public void joinsBrokenPdfWords(){assertEquals("Understanding a paragraph.",ReadingText.normalize("Under-\nstanding  a\nparagraph."));}
    @Test public void preservesIntentionalHyphenation(){assertEquals("well-known author",ReadingText.normalize("well-known author"));}
    @Test public void unicodeAndContractionsAreCounted(){assertEquals(5,ReadingText.countWords("Don’t stop. Café déjà vu!"));assertEquals(3,ReadingText.countWords("नमस्ते दुनिया hello"));}
    @Test public void punctuationHasNoWords(){assertEquals(0,ReadingText.countWords("… — ! ?"));}
    @Test public void longSentencesFitEngineLimitAndPauseOnlyAtEnd(){String text=("longword ").repeat(1300)+"finished.";List<ReadingText.Unit> u=ReadingText.split(text,2);assertTrue(u.size()>2);for(int i=0;i<u.size();i++){assertTrue(u.get(i).text.length()<=ReadingText.MAX_CHUNK);assertEquals(i==u.size()-1,u.get(i).sentenceEnd);}assertEquals(ReadingText.normalize(text),String.join(" ",u.stream().map(x->x.text).toList()));}
    @Test public void longUnbrokenInputDoesNotExceedLimit(){List<ReadingText.Unit> u=ReadingText.split("a".repeat(12000),1);assertEquals(4,u.size());assertEquals(12000,u.stream().mapToInt(x->x.text.length()).sum());}
    @Test public void surrogatePairsAreNeverSplit(){List<ReadingText.Unit> u=ReadingText.split("a".repeat(3199)+"😀"+"b".repeat(5000),1);for(ReadingText.Unit x:u){assertFalse(Character.isHighSurrogate(x.text.charAt(x.text.length()-1)));assertFalse(Character.isLowSurrogate(x.text.charAt(0)));}}
    @Test public void stripsSoftHyphensAndNulls(){assertEquals("cooperate now",ReadingText.normalize("co\u00adoperate\u0000 now"));}
}
