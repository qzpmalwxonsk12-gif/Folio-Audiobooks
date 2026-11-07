package com.folio.reader;

import android.app.Application;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public final class FolioApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PDFBoxResourceLoader.init(this);
    }
}
