package tv.clearstream.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelNamesTest {
    @Test public void regionalChineseNamesAndGenericChannelSuffixesStaySeparate() {
        assertEquals("cctv:4EU",ChannelNames.key("央视四套欧洲","源A"));
        assertEquals("cctv:4AM",ChannelNames.key("央视四套美洲","源B"));
        assertNotEquals(ChannelNames.key("新闻综合频道","默认","https://a.example/live"),ChannelNames.key("新闻综合频道","默认","https://b.example/live"));
    }
    @Test public void chinesePlusStaysDistinctAndUnownedLocalNamesNeverMergeByDefaultGroup() {
        assertEquals("cctv:5+",ChannelNames.key("央视五套+","源A"));
        assertNotEquals(ChannelNames.key("新闻综合","默认","https://a.example/live"),ChannelNames.key("新闻综合","默认","https://b.example/live"));
    }
    @Test public void ultraHdSuffixNeverTurnsDedicated4KOr8KChannelsIntoNumberedChannels() {
        assertEquals("cctv:4K",ChannelNames.key("CCTV4K高清","源A"));
        assertEquals("cctv:8K",ChannelNames.key("CCTV8K高清","源A"));
    }
    @Test public void cctvAliasesMergeButPlusUltraHdAndRegionalFeedsStaySeparate() {
        for(String name:new String[]{"CCTV1","CCTV-1 综合HD","央视一套","中央电视台1套高清","cctv１ FHD"})assertEquals(name,"cctv:1",ChannelNames.key(name,"源A"));
        assertEquals(ChannelNames.key("央视新闻频道","源B"),ChannelNames.key("CCTV13HD","源A"));
        assertNotEquals(ChannelNames.key("CCTV5","源A"),ChannelNames.key("CCTV5+","源B"));
        assertNotEquals(ChannelNames.key("CCTV4","源A"),ChannelNames.key("CCTV4K","源B"));
        assertNotEquals(ChannelNames.key("CCTV4欧洲","源A"),ChannelNames.key("CCTV4美洲","源A"));
        assertFalse(ChannelNames.isCctv("某地方CCTV直播站"));
    }
    @Test public void clearlyNamedChannelsMergeAcrossSourceGroupsWithoutMixingGenericLocalNames() {
        assertEquals(ChannelNames.key("湖南卫视高清","订阅甲"),ChannelNames.key("湖南卫视 HD","订阅乙"));
        assertNotEquals(ChannelNames.key("新闻综合","北京"),ChannelNames.key("新闻综合","杭州"));
        assertEquals("湖南卫视",ChannelNames.display("湖南卫视 [高清]"));
    }
    @Test public void searchFindsAliasesNumbersAndChineseTitles() {
        assertTrue(ChannelNames.matches("CCTV-1 HD","直播","央视一套"));
        assertTrue(ChannelNames.matches("CCTV13","直播","新闻"));
        assertTrue(ChannelNames.matches("CCTV5+","直播","央视"));
        assertTrue(ChannelNames.matches("湖南卫视高清","直播","湖南"));
        assertFalse(ChannelNames.matches("湖南卫视","直播","CCTV1"));
    }
}
