/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.os.StrictMode;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.app.NotificationCompat;
import android.util.Log;

import com.etesync.syncadapter.log.LogcatHandler;
import com.etesync.syncadapter.log.PlainTextFormatter;
import com.etesync.syncadapter.model.CollectionInfo;
import com.etesync.syncadapter.model.JournalEntity;
import com.etesync.syncadapter.model.Models;
import com.etesync.syncadapter.model.ServiceDB;
import com.etesync.syncadapter.model.Settings;

import org.apache.commons.lang3.time.DateFormatUtils;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;

import at.bitfire.cert4android.CustomCertManager;
import io.requery.Persistable;
import io.requery.android.sqlite.DatabaseSource;
import io.requery.sql.Configuration;
import io.requery.sql.EntityDataStore;
import io.requery.sql.TableCreationMode;
import lombok.Cleanup;
import lombok.Getter;
import okhttp3.internal.tls.OkHostnameVerifier;

public class App extends Application {
    public static final String FLAVOR_GOOGLE_PLAY = "gplay";

    public static final String
            DISTRUST_SYSTEM_CERTIFICATES = "distrustSystemCerts",
            LOG_TO_EXTERNAL_STORAGE = "logToExternalStorage",
            OVERRIDE_PROXY = "overrideProxy",
            OVERRIDE_PROXY_HOST = "overrideProxyHost",
            OVERRIDE_PROXY_PORT = "overrideProxyPort";

    public static final String OVERRIDE_PROXY_HOST_DEFAULT = "localhost";
    public static final int OVERRIDE_PROXY_PORT_DEFAULT = 8118;

    @Getter
    private CustomCertManager certManager;

    @Getter
    private static SSLSocketFactoryCompat sslSocketFactoryCompat;

    @Getter
    private static HostnameVerifier hostnameVerifier;

    public final static Logger log = Logger.getLogger("syncadapter");
    static {
        at.bitfire.cert4android.Constants.log = Logger.getLogger("syncadapter.cert4android");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        reinitCertManager();
        reinitLogger();
        StrictMode.enableDefaults();
    }

    public void reinitCertManager() {
        if (BuildConfig.customCerts) {
            if (certManager != null)
                certManager.close();

            @Cleanup ServiceDB.OpenHelper dbHelper = new ServiceDB.OpenHelper(this);
            Settings settings = new Settings(dbHelper.getReadableDatabase());

            certManager = new CustomCertManager(this, !settings.getBoolean(DISTRUST_SYSTEM_CERTIFICATES, false));
            sslSocketFactoryCompat = new SSLSocketFactoryCompat(certManager);
            hostnameVerifier = certManager.hostnameVerifier(OkHostnameVerifier.INSTANCE);
        }
    }

    public void reinitLogger() {
        @Cleanup ServiceDB.OpenHelper dbHelper = new ServiceDB.OpenHelper(this);
        Settings settings = new Settings(dbHelper.getReadableDatabase());

        boolean logToFile = settings.getBoolean(LOG_TO_EXTERNAL_STORAGE, false),
                logVerbose = logToFile || Log.isLoggable(log.getName(), Log.DEBUG);

        App.log.info("Verbose logging: " + logVerbose);

        // set logging level according to preferences
        final Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(logVerbose ? Level.ALL : Level.INFO);

        // remove all handlers and add our own logcat handler
        rootLogger.setUseParentHandlers(false);
        for (Handler handler : rootLogger.getHandlers())
            rootLogger.removeHandler(handler);
        rootLogger.addHandler(LogcatHandler.INSTANCE);

        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        // log to external file according to preferences
        if (logToFile) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
            builder .setSmallIcon(R.drawable.ic_sd_storage_light)
                    .setLargeIcon(getLauncherBitmap(this))
                    .setContentTitle(getString(R.string.logging_davdroid_file_logging))
                    .setLocalOnly(true);

            File dir = getExternalFilesDir(null);
            if (dir != null)
                try {
                    String fileName = new File(dir, "etesync-" + Process.myPid() + "-" +
                            DateFormatUtils.format(System.currentTimeMillis(), "yyyyMMdd-HHmmss") + ".txt").toString();
                    log.info("Logging to " + fileName);

                    FileHandler fileHandler = new FileHandler(fileName);
                    fileHandler.setFormatter(PlainTextFormatter.DEFAULT);
                    log.addHandler(fileHandler);
                    builder .setContentText(dir.getPath())
                            .setSubText(getString(R.string.logging_to_external_storage_warning))
                            .setCategory(NotificationCompat.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(getString(R.string.logging_to_external_storage, dir.getPath())))
                            .setOngoing(true);

                } catch (IOException e) {
                    log.log(Level.SEVERE, "Couldn't create external log file", e);

                    builder .setContentText(getString(R.string.logging_couldnt_create_file, e.getLocalizedMessage()))
                            .setCategory(NotificationCompat.CATEGORY_ERROR);
                }
            else
                builder.setContentText(getString(R.string.logging_no_external_storage));

            nm.notify(Constants.NOTIFICATION_EXTERNAL_FILE_LOGGING, builder.build());
        } else
            nm.cancel(Constants.NOTIFICATION_EXTERNAL_FILE_LOGGING);
    }

    @Nullable
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static Bitmap getLauncherBitmap(@NonNull Context context) {
        Bitmap bitmapLogo = null;
        Drawable drawableLogo = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP ?
                context.getDrawable(R.mipmap.ic_launcher) :
                context.getResources().getDrawable(R.mipmap.ic_launcher);
        if (drawableLogo instanceof BitmapDrawable)
            bitmapLogo = ((BitmapDrawable)drawableLogo).getBitmap();
        return bitmapLogo;
    }


    public static class ReinitLoggingReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            log.info("Received broadcast: re-initializing logger");

            App app = (App)context.getApplicationContext();
            app.reinitLogger();
        }

    }

    private EntityDataStore<Persistable> dataStore;

    /**
     * @return {@link EntityDataStore} single instance for the application.
     * <p/>
     * Note if you're using Dagger you can make this part of your application level module returning
     * {@code @Provides @Singleton}.
     */
    public EntityDataStore<Persistable> getData() {
        if (dataStore == null) {
            // override onUpgrade to handle migrating to a new version
            DatabaseSource source = new DatabaseSource(this, Models.DEFAULT, 1);
            if (BuildConfig.DEBUG) {
                // use this in development mode to drop and recreate the tables on every upgrade
                source.setTableCreationMode(TableCreationMode.DROP_CREATE);
            }
            Configuration configuration = source.getConfiguration();
            dataStore = new EntityDataStore<>(configuration);
        }
        return dataStore;
    }

    // update from previous account settings

    private final static String PREF_VERSION = "version";

    private void update(int fromVersion) {
        App.log.info("Updating from version " + fromVersion + " to " + BuildConfig.VERSION_CODE);

        if (fromVersion < 6) {
            EntityDataStore<Persistable> data = this.getData();

            ServiceDB.OpenHelper dbHelper = new ServiceDB.OpenHelper(this);

            List<CollectionInfo> collections = readCollections(dbHelper);
            for (CollectionInfo info : collections) {
                JournalEntity journalEntity = new JournalEntity(info);
                data.insert(journalEntity);
            }

            @Cleanup SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.delete(ServiceDB.Collections._TABLE, null, null);
        }
    }

    public static class AppUpdatedReceiver extends BroadcastReceiver {

        @Override
        @SuppressLint("UnsafeProtectedBroadcastReceiver,MissingPermission")
        public void onReceive(Context context, Intent intent) {
            App.log.info("EteSync was updated, checking for app version");

            App app = (App) context.getApplicationContext();
            SharedPreferences prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE);
            int fromVersion = prefs.getInt(PREF_VERSION, 1);
            app.update(fromVersion);
            prefs.edit().putInt(PREF_VERSION, BuildConfig.VERSION_CODE).apply();
        }

    }

    @NonNull
    private List<CollectionInfo> readCollections(ServiceDB.OpenHelper dbHelper) {
        @Cleanup SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<CollectionInfo> collections = new LinkedList<>();
        @Cleanup Cursor cursor = db.query(ServiceDB.Collections._TABLE, null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            ContentValues values = new ContentValues();
            DatabaseUtils.cursorRowToContentValues(cursor, values);
            collections.add(CollectionInfo.fromDB(values));
        }
        return collections;
    }
}