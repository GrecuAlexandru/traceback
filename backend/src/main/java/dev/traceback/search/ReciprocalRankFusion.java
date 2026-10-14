package dev.traceback.search;
import java.util.*;
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {}
    public static List<Ranked> merge(List<SearchRepository.Candidate> keyword,List<SearchRepository.Candidate> semantic,int limit) {
        var candidates=new HashMap<String,SearchRepository.Candidate>();var k=new HashMap<String,Integer>();var s=new HashMap<String,Integer>();
        for(int i=0;i<keyword.size();i++){var c=keyword.get(i);candidates.putIfAbsent(c.key(),c);k.putIfAbsent(c.key(),i+1);}
        for(int i=0;i<semantic.size();i++){var c=semantic.get(i);candidates.putIfAbsent(c.key(),c);s.putIfAbsent(c.key(),i+1);}
        return candidates.values().stream().map(c->new Ranked(c,k.get(c.key()),s.get(c.key()),
                (k.containsKey(c.key())?1.0/(60+k.get(c.key())):0)+(s.containsKey(c.key())?1.0/(60+s.get(c.key())):0)))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r->r.source().key())).limit(limit).toList();
    }
    public record Ranked(SearchRepository.Candidate source,Integer keywordRank,Integer semanticRank,double score) {}
}
