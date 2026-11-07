package com.folio.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class LibraryDb extends SQLiteOpenHelper {
    public LibraryDb(Context context) { super(context, "folio.db", null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY, title TEXT NOT NULL, pages INTEGER NOT NULL, total INTEGER NOT NULL, words INTEGER NOT NULL, position INTEGER NOT NULL DEFAULT 0, completed INTEGER NOT NULL DEFAULT 0, added INTEGER NOT NULL, opened INTEGER NOT NULL, ocr INTEGER NOT NULL DEFAULT 0, skipped INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE units (book TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE, idx INTEGER NOT NULL, page INTEGER NOT NULL, text TEXT NOT NULL, sentence_end INTEGER NOT NULL, PRIMARY KEY(book,idx))");
        db.execSQL("CREATE TABLE bookmarks (book TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE, idx INTEGER NOT NULL, label TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(book,idx))");
        db.execSQL("CREATE TABLE daily (day TEXT PRIMARY KEY, millis INTEGER NOT NULL DEFAULT 0, words INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    static JSONObject json(Object... pairs) {
        JSONObject j = new JSONObject();
        try { for (int i=0;i<pairs.length;i+=2) j.put(String.valueOf(pairs[i]), pairs[i+1]); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return j;
    }
    public synchronized boolean contains(String id) {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM books WHERE id=?",new String[]{id})) { return c.moveToFirst(); }
    }
    public void add(String id,String title,int pages,List<ReadingText.Unit> units,int ocr,int skipped) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            int words=0; for (ReadingText.Unit u:units) words+=u.words;
            ContentValues v=new ContentValues(); v.put("id",id);v.put("title",title);v.put("pages",pages);v.put("total",units.size());v.put("words",words);v.put("added",System.currentTimeMillis());v.put("opened",System.currentTimeMillis());v.put("ocr",ocr);v.put("skipped",skipped);
            db.insertOrThrow("books",null,v);
            for(int i=0;i<units.size();i++) { ReadingText.Unit u=units.get(i); v=new ContentValues();v.put("book",id);v.put("idx",i);v.put("page",u.page);v.put("text",u.text);v.put("sentence_end",u.sentenceEnd?1:0);db.insertOrThrow("units",null,v); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private JSONObject book(Cursor c) {
        return json("id",c.getString(0),"title",c.getString(1),"pages",c.getInt(2),"total",c.getInt(3),"words",c.getInt(4),"position",c.getInt(5),"completed",c.getInt(6)==1,"added",c.getLong(7),"opened",c.getLong(8),"ocr",c.getInt(9),"skipped",c.getInt(10));
    }
    public synchronized JSONArray library() {
        JSONArray a=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM books ORDER BY opened DESC",null)) { while(c.moveToNext()) a.put(book(c)); }
        return a;
    }
    public synchronized JSONObject getBook(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM books WHERE id=?",new String[]{id})) {return c.moveToFirst()?book(c):null;}
    }
    public synchronized List<ReadingText.Unit> units(String id) {
        ArrayList<ReadingText.Unit> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT page,text,sentence_end FROM units WHERE book=? ORDER BY idx",new String[]{id})) { while(c.moveToNext()) out.add(new ReadingText.Unit(c.getInt(0),c.getString(1),c.getInt(2)==1)); }
        return out;
    }
    public synchronized void position(String id,int position,boolean completed) {
        getWritableDatabase().execSQL("UPDATE books SET position=?,completed=MAX(completed,?),opened=? WHERE id=?",new Object[]{position,completed?1:0,System.currentTimeMillis(),id});
    }
    public synchronized void rename(String id,String title) {
        String trimmed=title.trim();if(trimmed.isEmpty())return;
        getWritableDatabase().execSQL("UPDATE books SET title=? WHERE id=?",new Object[]{trimmed.substring(0,Math.min(200,trimmed.length())),id});
    }
    public synchronized void remove(String id) { getWritableDatabase().delete("books","id=?",new String[]{id}); }
    public synchronized void mark(String id,int index,String label) {
        ContentValues v=new ContentValues();v.put("book",id);v.put("idx",index);v.put("label",label);v.put("created",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("bookmarks",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void unmark(String id,int index) { getWritableDatabase().delete("bookmarks","book=? AND idx=?",new String[]{id,String.valueOf(index)}); }
    public synchronized JSONArray bookmarks(String id) {
        JSONArray a=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT b.idx,b.label,u.page FROM bookmarks b JOIN units u ON u.book=b.book AND u.idx=b.idx WHERE b.book=? ORDER BY b.idx",new String[]{id})) { while(c.moveToNext()) a.put(json("index",c.getInt(0),"label",c.getString(1),"page",c.getInt(2))); }
        return a;
    }
    public synchronized void credit(long millis,int words) {
        if(millis<=0&&words<=0)return;
        String day=LocalDate.now().toString();SQLiteDatabase db=getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO daily(day) VALUES(?)",new Object[]{day});
        db.execSQL("UPDATE daily SET millis=millis+?,words=words+? WHERE day=?",new Object[]{Math.max(0,millis),Math.max(0,words),day});
    }
    public synchronized JSONObject stats() {
        long millis=0,words=0,today=0; int books=0,streak=0;
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(millis),0),COALESCE(SUM(words),0) FROM daily",null)) { if(c.moveToFirst()){millis=c.getLong(0);words=c.getLong(1);} }
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM books WHERE completed=1",null)) { if(c.moveToFirst())books=c.getInt(0); }
        JSONArray days=new JSONArray();LocalDate now=LocalDate.now();
        for(int i=6;i>=0;i--) { String date=now.minusDays(i).toString();long ms=0;
            try(Cursor c=db.rawQuery("SELECT millis FROM daily WHERE day=?",new String[]{date})) {if(c.moveToFirst()) ms=c.getLong(0);}
            if(i==0)today=ms;days.put(json("day",date,"millis",ms));
        }
        LocalDate day=today>0?now:now.minusDays(1);
        while(streak<100000) {
            try(Cursor c=db.rawQuery("SELECT millis FROM daily WHERE day=?",new String[]{day.toString()})) {if(!c.moveToFirst()||c.getLong(0)<=0)break;}
            streak++;day=day.minusDays(1);
        }
        return json("millis",millis,"words",words,"today",today,"finished",books,"streak",streak,"days",days);
    }
}
