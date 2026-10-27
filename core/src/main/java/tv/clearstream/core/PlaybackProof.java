package tv.clearstream.core;
/** A decoded frame alone is not evidence of sustained playback. */
public final class PlaybackProof {
 /** Proof follows only the saved website or the page explicitly selected in the native film list. */
 public static boolean canAssociate(String siteRoot,String selectedPage,String currentPage){
  String root=proofHost(siteRoot),selected=proofHost(selectedPage),current=proofHost(currentPage);
  return root!=null&&selected!=null&&current!=null&&(current.equals(root)||current.equals(selected));
 }
 private static String proofHost(String address){
  String safe=WebsitePolicy.normalize(address);if(safe==null)return null;
  String host=java.net.URI.create(safe).getHost().toLowerCase(java.util.Locale.ROOT);
  if(host.endsWith("."))host=host.substring(0,host.length()-1);
  if(host.startsWith("www."))host=host.substring(4);
  return host.isEmpty()?null:host;
 }
 private String page;private long firstAt,lastAt=-1;private double firstTime,lastTime;private boolean recorded;
 public boolean observe(String address,int width,int height,double time,boolean paused,int ready,int error,long now){
  if(WebsitePolicy.normalize(address)==null||width<=0||height<=0||width>16384||height>16384||(double)width/height>3.2||(double)width/height<.35||paused||ready<2||error!=0||Double.isNaN(time)||Double.isInfinite(time)||time<0){reset();return false;}
  long elapsed=now-lastAt;
  boolean restart=!address.equals(page)||lastAt<0||elapsed<0||elapsed>3000||time<lastTime||time-lastTime>Math.max(1,elapsed/1000.0*3+1)||(elapsed>=250&&time-lastTime<.01);
  if(restart){page=address;firstAt=now;firstTime=time;recorded=false;}
  lastAt=now;lastTime=time;
  if(!recorded&&now-firstAt>=5000&&time-firstTime>=3){recorded=true;return true;}return false;
 }
 public void reset(){page=null;lastAt=-1;recorded=false;}
}
