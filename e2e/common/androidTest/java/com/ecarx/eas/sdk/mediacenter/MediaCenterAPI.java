package com.ecarx.eas.sdk.mediacenter;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import com.ecarx.eas.sdk.ECarXApiClient;

/** 合成 SDK 端口用于真实 VM 契约回归，绝不随正式 APK 构建。 */
public class MediaCenterAPI {
    private static MediaCenterAPI instance;
    public Context sdkContext;
    public MusicClient client;
    public String packageName;
    public String mediaSessionPackage;
    public MusicPlaybackInfo published;
    public long progress;
    public int source;
    public int[] sources;
    public int unregistrations;
    public int releases;
    public boolean invalidToken;
    private Object token;
    public static MediaCenterAPI get(Context context) { return instance; }
    public static MediaCenterAPI reset() { instance = new MediaCenterAPI(); return instance; }
    public void init(Context context, ECarXApiClient.Callback callback) { sdkContext = context; callback.onAPIReady(true); }
    public Object registerMusic(Runnable client) { throw new AssertionError("PLACEHOLDER_OVERLOAD"); }
    public Object registerMusic(String name, MusicClient client) { throw new AssertionError("SESSION_LINK_REQUIRED"); }
    public Object registerMusic(String name, MusicClient client, String session) {
        this.client = client; packageName = name; mediaSessionPackage = session;
        token = invalidToken ? new Object() : new Token(); return token;
    }
    private void verify(Object value) { if (value != token) throw new AssertionError("TOKEN_CHANGED"); }
    public boolean updateMediaSourceTypeList(Object value, int[] sources) { verify(value); this.sources = sources; return true; }
    public void updateCurrentSourceType(Object value, int source) { verify(value); this.source = source; }
    public boolean requestPlay(Object value) { verify(value); return true; }
    public String queryCurrentFocusClient(Object value) { verify(value); return packageName; }
    public void updateCurrentProgress(Object value, long progress) { verify(value); this.progress = progress; }
    public boolean updateMusicPlaybackState(Object value, MusicPlaybackInfo info) { verify(value); published = info; return true; }
    public boolean unregister(Object value) { verify(value); unregistrations++; return true; }
    public void release() { releases++; }
    public static class Token extends Binder implements IInterface { public IBinder asBinder() { return this; } }
}
