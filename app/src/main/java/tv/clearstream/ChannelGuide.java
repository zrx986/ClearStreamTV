package tv.clearstream;

import java.util.*;
import tv.clearstream.core.ChannelNames;

/** Daily viewing treats same-name, same-group URLs as alternate routes. */
public final class ChannelGuide {
    public static List<Channel> programs(List<Channel> channels,String preferredUrl) {
        Map<String,Channel> selected=new LinkedHashMap<>();
        for(Channel channel:channels) {
            String key=ChannelNames.key(channel.name,channel.group,channel.url);
            if(!selected.containsKey(key)||channel.url.equals(preferredUrl))selected.put(key,channel);
        }
        return new ArrayList<>(selected.values());
    }
    public static Channel recent(List<Channel> channels,String lastUrl) {
        for(Channel channel:channels)if(channel.url.equals(lastUrl))return channel;
        return channels.isEmpty()?null:channels.get(0);
    }
    public static int indexOf(List<Channel> channels,Channel current) {
        if(current!=null)for(int i=0;i<channels.size();i++)if(ChannelNames.key(channels.get(i).name,channels.get(i).group,channels.get(i).url).equals(ChannelNames.key(current.name,current.group,current.url)))return i;
        return 0;
    }
    public static List<Channel> lines(List<Channel> channels,Channel selected) {
        List<Channel> result=new ArrayList<>();if(selected==null)return result;
        String key=ChannelNames.key(selected.name,selected.group,selected.url);
        for(Channel channel:channels)if(ChannelNames.key(channel.name,channel.group,channel.url).equals(key))result.add(channel);
        return result;
    }
    public static List<Channel> cctv(List<Channel> channels,String preferredUrl) {
        List<Channel> result=new ArrayList<>();for(Channel channel:programs(channels,preferredUrl))if(ChannelNames.isCctv(channel.name))result.add(channel);
        Collections.sort(result,(a,b)->Integer.compare(ChannelNames.cctvOrder(a.name),ChannelNames.cctvOrder(b.name)));return result;
    }
    public static final class SearchItem {
        public final Channel channel;public final String title,key,text;public final int routes;
        SearchItem(Channel c,String key,int routes){channel=c;this.key=key;this.routes=routes;title=ChannelNames.display(c.name);text=ChannelNames.searchText(c.name,c.group);}
    }
    public static List<SearchItem> searchIndex(List<Channel> channels,String preferredUrl) {
        Map<String,Integer> counts=new HashMap<>();Map<String,Channel> selected=new LinkedHashMap<>();
        for(Channel channel:channels){String key=ChannelNames.key(channel.name,channel.group,channel.url);Integer n=counts.get(key);counts.put(key,n==null?1:n+1);if(!selected.containsKey(key)||channel.url.equals(preferredUrl))selected.put(key,channel);}
        List<SearchItem> result=new ArrayList<>();for(Map.Entry<String,Channel> entry:selected.entrySet())result.add(new SearchItem(entry.getValue(),entry.getKey(),counts.get(entry.getKey())));return result;
    }

}
