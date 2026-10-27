package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SourceManagementTest {
 @Test public void onlyMissingGoneOrNonMediaResponsesAreClearable(){
  assertTrue(SourceManagement.clearable("暂时失败 · HTTP 404"));
  assertTrue(SourceManagement.clearable("暂时失败 · HTTP 410"));
  assertTrue(SourceManagement.clearable("响应为网页 · 不是媒体"));
  assertEquals(SourceManagement.State.INVALID,SourceManagement.classify("暂时失败 · HTTP 404"));
 }
 @Test public void localIpv6ProblemsTimeoutsAuthenticationAndServersNeedRechecking(){
  for(String status:new String[]{"暂时失败 · DNS / 地址族不可用","暂时失败 · 请求超时或已取消","暂时失败 · 目标连接失败","需认证 · HTTP 401","需认证 · HTTP 403","暂时失败 · HTTP 429","暂时失败 · HTTP 503","空响应","暂时失败 · TLS 证书或握手失败"}){
   assertFalse(status,SourceManagement.clearable(status));assertEquals(status,SourceManagement.State.RECHECK,SourceManagement.classify(status));
  }
 }
 @Test public void connectionAndPlaybackHistoryAreDistinctFromUnchecked(){
  for(String status:new String[]{"可解析 · 17 ms","可连接 · 媒体待播放验证","已播放 · IPv6"})assertEquals(SourceManagement.State.AVAILABLE,SourceManagement.classify(status));
  assertEquals(SourceManagement.State.UNTESTED,SourceManagement.classify("未检测"));assertEquals(SourceManagement.State.UNTESTED,SourceManagement.classify(null));assertEquals(SourceManagement.State.UNTESTED,SourceManagement.classify(" "));
 }
 @Test public void statusTextCannotMatchPartialHttpCodesOrAnOldErrorInPlaybackHistory(){
  assertFalse(SourceManagement.clearable("暂时失败 · HTTP 4040"));assertFalse(SourceManagement.clearable("已播放 · 上次 HTTP 404"));assertFalse(SourceManagement.clearable("未检测"));
 }
}
