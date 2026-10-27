package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PlaybackProofTest {
 @Test public void extremelyWideBannerVideoCannotCertifyAFilm(){PlaybackProof p=new PlaybackProof();for(int i=0;i<9;i++)assertFalse("The observed 4360x630 banner is not film evidence",p.observe("https://example.org/movie/one",4360,630,i,false,4,0,i*1000));}
 @Test public void playbackBelongsOnlyToSiteOrExplicitlySelectedPageHost(){
  assertTrue(PlaybackProof.canAssociate("https://site-a.example/","https://www.site-b.example/vod/detail/123","https://site-b.example/vod/play/123"));
  assertTrue(PlaybackProof.canAssociate("https://site-a.example/","https://site-b.example/detail/123","https://WWW.SITE-A.EXAMPLE/another/path"));
  assertTrue(PlaybackProof.canAssociate("https://www.site-a.example/","https://site-b.example/detail/123","https://SITE-A.example./video"));
  assertFalse(PlaybackProof.canAssociate("https://site-a.example/","https://site-b.example/detail/123","https://search.example/search"));
  assertFalse(PlaybackProof.canAssociate("https://site-a.example/","https://site-b.example/detail/123","https://adsite.example/banner"));
 }
 @Test public void playbackAssociationRejectsInvalidAddressesAndHostSuffixTricks(){
  for(String address:new String[]{"javascript:alert(1)","file:///clip.mp4","ftp://site-a.example/film","https://site-a.example.evil.example/video","https://site-b.example.evil.example/video","https://site-a.example@evil.example/video","https://ad.site-a.example/video"})assertFalse(address,PlaybackProof.canAssociate("https://site-a.example/","https://site-b.example/detail/123",address));
  assertFalse(PlaybackProof.canAssociate(null,"https://site-b.example/film","https://site-b.example/play"));
  assertFalse(PlaybackProof.canAssociate("https://site-a.example/","javascript:bad","https://site-a.example/play"));
  assertFalse(PlaybackProof.canAssociate("https://site-a.example/","https://site-b.example/film",null));
 }
 private boolean tick(PlaybackProof p,String page,double t,long now){return p.observe(page,1280,720,t,false,4,0,now);}
 @Test public void decodedVideoMustAdvanceContinuouslyForFiveSecondsAndRecordsOnce(){PlaybackProof p=new PlaybackProof();for(int i=0;i<5;i++)assertFalse(tick(p,"https://example.org/film",i,1000+i*1000));assertTrue(tick(p,"https://example.org/film",5.2,6000));assertFalse(tick(p,"https://example.org/film",6.2,7000));}
 @Test public void pausedFramesErrorsAndStillPostersCannotPass(){PlaybackProof p=new PlaybackProof();for(int i=0;i<10;i++){assertFalse(p.observe("https://example.org/film",0,0,i,false,0,0,1000*i));assertFalse(p.observe("https://example.org/film",1280,720,i,true,4,0,1000*i));assertFalse(p.observe("https://example.org/film",1280,720,i,false,4,3,1000*i));}p.reset();for(int i=0;i<10;i++)assertFalse(tick(p,"https://example.org/film",0,1000+i*1000));}
 @Test public void pageChangesSeeksAndLongSamplingGapsRestartObservation(){PlaybackProof p=new PlaybackProof();for(int i=0;i<4;i++)assertFalse(tick(p,"https://example.org/one",i,1000+i*1000));assertFalse(tick(p,"https://example.org/two",0,5000));assertFalse(tick(p,"https://example.org/two",90,6000));assertFalse(tick(p,"https://example.org/two",91,17000));for(int i=0;i<4;i++)assertFalse(tick(p,"https://example.org/two",92+i,18000+i*1000));assertTrue(tick(p,"https://example.org/two",96,22000));}
}
