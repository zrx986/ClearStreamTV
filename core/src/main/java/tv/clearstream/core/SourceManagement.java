package tv.clearstream.core;

/** A failed local connection is not proof that an IPTV address is obsolete. */
public final class SourceManagement {
 private static final java.util.regex.Pattern MISSING=java.util.regex.Pattern.compile("\\bHTTP\\s+(404|410)(?:\\D|$)");
 public enum State { UNTESTED, AVAILABLE, INVALID, RECHECK }
 public static boolean clearable(String status){
  String text=status==null?"":status.trim();
  return text.equals("响应为网页 · 不是媒体")||text.startsWith("暂时失败")&&MISSING.matcher(text).find();
 }
 public static State classify(String status){
  String text=status==null?"":status.trim();
  if(text.isEmpty()||text.startsWith("未检测"))return State.UNTESTED;
  if(clearable(text))return State.INVALID;
  if(text.startsWith("可解析")||text.startsWith("可连接")||text.startsWith("已播放"))return State.AVAILABLE;
  return State.RECHECK;
 }
 private SourceManagement(){}
}
