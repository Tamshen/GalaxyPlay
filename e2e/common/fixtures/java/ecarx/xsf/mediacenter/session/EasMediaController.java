package ecarx.xsf.mediacenter.session;

import java.util.ArrayList;
import java.util.List;

/** 配置目录服务夹具；只记录合成列表和操作次数。 */
public class EasMediaController {
    public List<AppSourceInfo> configuration = new ArrayList<>();
    public int reads;
    public int writes;
    public boolean rejectWrite;
    public boolean ignoreWrite;
    public List<AppSourceInfo> getAppSourceConfig() {
        reads++;
        return configuration == null ? null : new ArrayList<>(configuration);
    }
    public void setAppSourceConfig(List<AppSourceInfo> value) {
        writes++;
        if (rejectWrite) throw new SecurityException("SYNTHETIC_DENIED");
        if (!ignoreWrite) configuration = new ArrayList<>(value);
    }
}
