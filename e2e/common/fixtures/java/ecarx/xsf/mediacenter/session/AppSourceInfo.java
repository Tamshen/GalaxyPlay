package ecarx.xsf.mediacenter.session;

import android.graphics.Bitmap;

/** 仅供 JVM 与 Android VM 的公开契约夹具，不进入产品源集。 */
public final class AppSourceInfo {
    private final Builder fields;
    private AppSourceInfo(Builder fields) { this.fields = fields; }
    public String getPackageName() { return fields.packageName; }
    public String getAppName() { return fields.appName; }
    public String getIconPath() { return fields.iconPath; }
    public int[] getSourceTypeList() { return fields.sources; }
    public int getPriorityLevel() { return fields.priority; }
    public boolean isRunning() { return fields.running; }
    public boolean isSupportMultipleOpen() { return fields.multiple; }
    public Bitmap getIconRes() { return fields.bitmap; }

    public static final class Builder {
        private String packageName;
        private String appName;
        private String iconPath;
        private int[] sources;
        private int priority;
        private boolean running;
        private boolean multiple;
        private Bitmap bitmap;
        public Builder packageName(String value) { packageName = value; return this; }
        public Builder appName(String value) { appName = value; return this; }
        public Builder iconPath(String value) { iconPath = value; return this; }
        public Builder sourceTypeList(int[] value) { sources = value; return this; }
        public Builder priorityLevel(int value) { priority = value; return this; }
        public Builder isRunning(boolean value) { running = value; return this; }
        public Builder supportMultipleOpen(boolean value) { multiple = value; return this; }
        public Builder iconRes(Bitmap value) { bitmap = value; return this; }
        public AppSourceInfo build() { return new AppSourceInfo(this); }
    }
}
