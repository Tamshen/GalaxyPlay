package com.ecarx.eas.sdk.mediacenter;

/** 公开 getter 签名替身；不包含厂商代码。 */
public class MusicPlaybackInfo {
    public static final int SOURCE_TYPE_ONLINE = 6;
    public String getTitle() { return null; }
    public String getArtist() { return null; }
    public String getAlbum() { return null; }
    public long getDuration() { return 0L; }
    public android.net.Uri getArtwork() { return null; }
    public int getSourceType() { return 0; }
    public int getPlaybackStatus() { return 0; }
    public String getPackageName() { return null; }
    public String getAppName() { return null; }
    public String getAppIcon() { return null; }
    public android.app.PendingIntent getLaunchIntent() { return null; }
    public android.app.PendingIntent getPlayerIntent() { return null; }
    public String getUuid() { return null; }
    public boolean isSupportCollect() { return false; }
    public boolean isSupportDownload() { return false; }
    public boolean isSupportLoopModeSwitch() { return true; }
    public boolean isSupportVrCtrlPlayStatus() { return true; }
    public boolean isCollected() { return false; }
    public boolean isDownloaded() { return false; }
    public int getVip() { return -1; }
    public int getPlayingMediaListType() { return 0; }
    public int getPlayingItemPositionInQueue() { return 0; }
    public int getRadioMode() { return 0; }
    public int getDisplayId() { return 0; }
}
