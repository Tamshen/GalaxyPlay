package com.ecarx.eas.sdk.mediacenter;

/** 公开控制与拉取接口替身；默认没有可执行的手机命令。 */
public class MusicClient {
    public boolean onPlay() { return false; }
    public boolean onPause() { return false; }
    public boolean onNext() { return false; }
    public boolean onPrevious() { return false; }
    public boolean onSourceSelected(int source) { return false; }
    public boolean onSourceChanged(int source, String previous) { return false; }
    public void onMediaCenterFocusChanged(String packageName) {}
    public int getCurrentSourceType() { return 0; }
    public int[] getMediaSourceTypeList() { return new int[0]; }
    public long getCurrentProgress() { return 0; }
    public MusicPlaybackInfo getMusicPlaybackInfo() { return null; }
}
