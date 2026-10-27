package tv.clearstream.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class FilmSearchScopeTest {
    @Test public void defaultIsFirstThreeDistinctFilmWebsitesInSavedOrder(){
        assertEquals(Arrays.asList("https://a.example/","https://b.example/","https://c.example/"),FilmSearchScope.defaults(Arrays.asList("https://a.example/","https://www.a.example/films","https://b.example/","https://c.example/","https://d.example/")));
    }
    @Test public void navigationAndLiveEntriesAreNotFilmSearchTargets(){
        assertEquals(Collections.singletonList("https://a.example/"),FilmSearchScope.defaults(Arrays.asList("https://www.bmmyy.com/","https://i8k.tv/","https://tv.cctv.com/live/cctv1/","https://a.example/")));
    }
    @Test public void customSelectionUsesChosenOrderAndMayContainMoreThanThreeSites(){
        List<String> sites=Arrays.asList("https://a.example/","https://b.example/","https://c.example/","https://d.example/");
        assertEquals(Arrays.asList(sites.get(3),sites.get(0),sites.get(2),sites.get(1)),FilmSearchScope.selected(sites,Arrays.asList(sites.get(3),sites.get(0),sites.get(2),sites.get(1))));
    }
    @Test public void selectionRejectsDeletedUnchosenInvalidAndNavigationSites(){
        assertEquals(Collections.singletonList("https://b.example/"),FilmSearchScope.selected(Arrays.asList("https://b.example/","https://www.bmmyy.com/"),Arrays.asList("https://deleted.example/","https://b.example/","https://b.example/","javascript:bad","https://www.bmmyy.com/")));
        assertTrue(FilmSearchScope.selected(Collections.singletonList("https://a.example/"),Collections.emptyList()).isEmpty());
    }
    @Test public void emptyDefaultNeverAddsWebsites(){
        assertTrue("Source-free release must not invent search targets",FilmSearchScope.defaults(Collections.emptyList()).isEmpty());
    }
}
