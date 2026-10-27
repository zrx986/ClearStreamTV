package tv.clearstream;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;
import tv.clearstream.core.UrlPolicy;

/** Atomic local import checkpoint, including favorites. */
public final class ImportBackup {
    private final AtomicFile file;
    public ImportBackup(Context context) {file=new AtomicFile(new File(context.getFilesDir(),"before-import.json"));}
    public boolean exists() {return file.getBaseFile().exists();}
    public synchronized void save(List<Channel> channels) throws Exception {
        JSONArray array=new JSONArray();
        for(Channel c:channels)array.put(new JSONObject().put("id",c.id).put("name",c.name).put("url",c.url).put("group",c.group).put("favorite",c.favorite).put("status",c.status).put("checked",c.checked).put("family",c.observedFamily));
        byte[] data=array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);FileOutputStream out=file.startWrite();
        try {out.write(data);file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}
    }
    public synchronized List<Channel> load() throws Exception {
        JSONArray array=new JSONArray(new String(file.readFully(),java.nio.charset.StandardCharsets.UTF_8));List<Channel> result=new ArrayList<>();
        if(array.length()>10000)throw new IOException("备份过大");
        for(int i=0;i<array.length();i++) {
            JSONObject v=array.getJSONObject(i);Channel c=new Channel();c.id=v.getLong("id");c.name=v.getString("name");c.url=v.getString("url");c.group=v.getString("group");
            if(!UrlPolicy.isSupported(c.url))throw new IOException("备份包含无效地址");
            c.favorite=v.optBoolean("favorite");c.status=v.optString("status","未检测");c.checked=v.optLong("checked");c.observedFamily=v.optString("family","未知");result.add(c);
        }
        return result;
    }
}
