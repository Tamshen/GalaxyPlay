package com.ecarx.eas.sdk;

/** 仅为原厂公开回调签名的测试替身。 */
public class ECarXApiClient {
    public interface Callback { void onAPIReady(boolean ready); }
}
