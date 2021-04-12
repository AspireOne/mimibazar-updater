package com.gmail.matejpesl1.mimi;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.gmail.matejpesl1.mimi.utils.RootUtils;
import com.gmail.matejpesl1.mimi.utils.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class AppUpdateManager {
    private static final String TAG = "AppUpdateManager";
    private static final String PREF_LAST_DOWNLOADED_APK_VERSION = "Last Downloaded APK version";
    private static final String PREF_LAST_VERSION_CHECK_TIME = "Last Version Check Time";
    private static final String LATEST_RELEASE_JSON_LINK = "https://api.github.com/repos/AspireOne/mimibazar-updater/releases/latest";
    private static final String APK_DOWNLOAD_LINK = "https://github.com/AspireOne/mimibazar-updater/releases/latest/download/updater.apk";
    private static final String APK_NAME = "updater.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final long VERSION_CACHE_TIME_MS = TimeUnit.HOURS.toMillis(1);

    public enum DownloadState {ALREADY_DOWNLOADED, ALREADY_DOWNLOADING, SUCCESS, FAILURE}

    private static @Nullable Thread downloadThread = null;
    private static int cachedVersion = 0;

    public static boolean isUpdateAvailable(Context context) {
        return getNewestVerNum(context) > BuildConfig.VERSION_CODE;
    }

    public static boolean requestInstall(Context context) {
        if (!tryAssertLatestApkDownloaded(context))
            return false;

        final Uri apkUri = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                getApk(context));

        final Intent installIntent = new Intent(Intent.ACTION_VIEW, apkUri)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .setDataAndType(apkUri, APK_MIME_TYPE)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        context.startActivity(installIntent);
        return true;
    }

    public static boolean installDirectlyWithRoot(Context context) {
        if (!tryAssertLatestApkDownloaded(context))
            return false;

        final File apk = getApk(context);

        String command = String.format("cat %s | pm install -S %s", apk.getAbsolutePath(), apk.length());
        Log.i(TAG, "Command to directly install apk: " + command);

        Pair<Boolean, Process> result = RootUtils.runCommandAsSu(command);
        Log.i(TAG, "direct installation success: " + result.first.booleanValue());
        return true;
    }

    private static boolean tryAssertLatestApkDownloaded(Context context) {
        final String tag = TAG + "::prepareForInstall";

        // If already downloading, wait for it to end and return the apk downloaded (bool) status.
        if (downloadThread != null) {
            Log.i(tag, "Download is already running, waiting for it to end...");
            waitForDownloadThreadIfExists(tag);

            boolean downloadedApkLatest = isDownloadedApkLatest(context);
            Log.i(tag, "Download finished. Downloaded apk latest: " + downloadedApkLatest);
            return downloadedApkLatest;
        }

        // If not downloading and the app is not downloaded.
        if (!isDownloadedApkLatest(context)) {
            AtomicReference<DownloadState> downloadState = new AtomicReference<>();

            // Start the download and wait for it to end (if it runs), getting it's result.
            downloadApkAsync(context, (dState) -> downloadState.set(dState));
            waitForDownloadThreadIfExists(tag);

            if (downloadState.get() == DownloadState.ALREADY_DOWNLOADED || downloadState.get() == DownloadState.ALREADY_DOWNLOADING)
                Log.e(TAG, "Unexpected download state (" + downloadState.get() + ").");

            return isDownloadedApkLatest(context);
        }

        return true;
    }

    private static void waitForDownloadThreadIfExists(String tag) {
        if (downloadThread == null)
            return;
        try { downloadThread.join(20000); }
        catch (InterruptedException e) { Log.e(tag, Utils.getExceptionAsString(e)); }
    }

    private static int getNewestVerNum(Context context) {
        final long lastCheckTimeMs = Long.parseLong(Utils.getPref(context, PREF_LAST_VERSION_CHECK_TIME, "0"));

        if ((System.currentTimeMillis() - lastCheckTimeMs) < VERSION_CACHE_TIME_MS)
            return cachedVersion;

        Log.i(TAG, "Checking for an updated app version");
        Utils.writePref(context, PREF_LAST_VERSION_CHECK_TIME, System.currentTimeMillis()+"");

        try {
            URL url = new URL(LATEST_RELEASE_JSON_LINK);
            Scanner sc = new Scanner(url.openStream(), "UTF-8");
            String releaseInfo = sc.useDelimiter("\\A").next();
            sc.close();
            int numBeginChar = releaseInfo.indexOf("\"v") + 2;
            int numEndChar = releaseInfo.indexOf("\"", numBeginChar);
            String tag = releaseInfo.substring(numBeginChar, numEndChar).replace(".", "");
            return (cachedVersion = Integer.parseInt(tag));
        } catch (Exception e) {
            Log.e(TAG, Utils.getExceptionAsString(e));
            return -1;
        }
    }

    private static File getApk(Context context) {
        File externalDir = context.getExternalFilesDir(null);
        return new File(externalDir, APK_NAME);
    }

    public static boolean isDownloadedApkLatest(Context context) {
        final String downloadedApkVer = Utils.getPref(context, PREF_LAST_DOWNLOADED_APK_VERSION, "0");
        final String latestApkVer = getNewestVerNum(context) + "";

        return downloadedApkVer.equals(latestApkVer) && getApk(context).exists();
    }

    public static void downloadApkAsync(Context context, Consumer<DownloadState> onFinish) {
        if (downloadThread != null) {
            onFinish.accept(DownloadState.ALREADY_DOWNLOADING);
            return;
        }
        if (isDownloadedApkLatest(context)) {
            onFinish.accept(DownloadState.ALREADY_DOWNLOADED);
            return;
        }

        downloadThread = new Thread(() -> {
            FileOutputStream fos = null;
            FileChannel fch = null;
            try {
                final ReadableByteChannel readableByteChannel
                        = Channels.newChannel(new URL(APK_DOWNLOAD_LINK).openStream());

                fos = new FileOutputStream(getApk(context));
                fch = fos.getChannel();

                fch.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                Utils.writePref(context, PREF_LAST_DOWNLOADED_APK_VERSION, getNewestVerNum(context)+"");

                onFinish.accept(DownloadState.SUCCESS);
                return;
            } catch (Exception e) {
                Log.e(TAG, "Exception while downloading apk. E:\n" + Utils.getExceptionAsString(e));
            } finally {
                downloadThread = null;
                try {
                    if (fos != null) fos.close();
                    if (fch != null) fch.close();
                } catch (Exception e) {
                    Log.e(TAG, "E closing streams. E:\n" + Utils.getExceptionAsString(e));
                }
            }

            onFinish.accept(DownloadState.FAILURE);
        });

        downloadThread.start();
    }
}
