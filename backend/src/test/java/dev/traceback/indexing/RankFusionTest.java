package dev.traceback.indexing;
import dev.traceback.search.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RankFusionTest {
    SearchRepository.Candidate candidate(UUID generation,UUID id){return new SearchRepository.Candidate(UUID.randomUUID(),UUID.randomUUID(),id,generation,"Title","Excerpt",null,null,null,0,"cfg","model",768,999);}
    @Test void fusionUsesRanksAndDeduplicatesInsteadOfAddingProviderScores(){UUID gen=UUID.randomUUID();var a=candidate(gen,UUID.randomUUID());var b=candidate(gen,UUID.randomUUID());var c=candidate(gen,UUID.randomUUID());
        var ranked=ReciprocalRankFusion.merge(List.of(a,b,a),List.of(c,b),10);assertEquals(3,ranked.size());assertEquals(b,ranked.getFirst().source());assertEquals(2.0/62,ranked.getFirst().score(),1e-12);}
    @Test void tiedRanksHaveStableOrderingAndLimit(){UUID gen=UUID.randomUUID();var a=candidate(gen,new UUID(0,1));var b=candidate(gen,new UUID(0,2));
        assertEquals(a,ReciprocalRankFusion.merge(List.of(a),List.of(b),1).getFirst().source());assertEquals(ReciprocalRankFusion.merge(List.of(a),List.of(b),10).stream().map(ReciprocalRankFusion.Ranked::source).toList(),ReciprocalRankFusion.merge(List.of(b),List.of(a),10).stream().map(ReciprocalRankFusion.Ranked::source).toList());}
}
