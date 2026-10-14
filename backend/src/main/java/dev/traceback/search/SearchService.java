package dev.traceback.search;

import dev.traceback.indexing.*;
import dev.traceback.knowledge.DocumentRepository;
import dev.traceback.organization.OrganizationAuthorizationService;
import jakarta.validation.constraints.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@Service
@Validated
public class SearchService {
    private final SearchRepository repository;private final OrganizationAuthorizationService authorization;
    private final DocumentRepository documents;private final EmbeddingProvider embeddings;private final IndexSettings settings;
    private final TransactionTemplate read;
    public SearchService(SearchRepository repository,OrganizationAuthorizationService authorization,DocumentRepository documents,
                         EmbeddingProvider embeddings,IndexSettings settings,PlatformTransactionManager manager) {
        this.repository=repository;this.authorization=authorization;this.documents=documents;this.embeddings=embeddings;this.settings=settings;
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);read.setTimeout(5);
    }
    public Response search(Jwt jwt,UUID org,@NotBlank String q,@Min(1) @Max(50) int limit,Mode mode) {
        if(StructureChunker.points(q)>256 || q.codePoints().anyMatch(Character::isISOControl)
                || !q.codePoints().anyMatch(Character::isLetterOrDigit) || q.strip().split("[^\\p{L}\\p{N}_]+").length>16)
            throw new IllegalArgumentException("Invalid search query");
        read.execute(t->authorization.requireAccess(jwt,org,false));
        Mode requested=mode==null?Mode.hybrid:mode;float[] queryVector=null;String degraded=null;
        if(requested!=Mode.keyword) {
            try {queryVector=embeddings.query(q);EmbeddingProvider.validate(queryVector,settings.dimensions());}
            catch(EmbeddingFailure ex) {if(requested==Mode.semantic)throw ex;degraded=ex.code();}
        }
        float[] vector=queryVector;String reason=degraded;
        return read.execute(t->{
            var identity=authorization.requireAccess(jwt,org,false);
            var keyword=requested==Mode.semantic?List.<SearchRepository.Candidate>of():repository.keyword(org,identity,q.strip(),Math.min(200,limit*4));
            var semantic=vector==null?List.<SearchRepository.Candidate>of():repository.semantic(org,identity,vector,settings.configuration(),Math.min(200,limit*4));
            List<ReciprocalRankFusion.Ranked> ranked;
            if(requested==Mode.hybrid && vector!=null)ranked=ReciprocalRankFusion.merge(keyword,semantic,limit);
            else {var list=requested==Mode.semantic?semantic:keyword;var values=new ArrayList<ReciprocalRankFusion.Ranked>();
                for(int i=0;i<Math.min(list.size(),limit);i++)values.add(new ReciprocalRankFusion.Ranked(list.get(i),requested==Mode.semantic?null:i+1,requested==Mode.semantic?i+1:null,list.get(i).score()));ranked=List.copyOf(values);}
            return new Response(requested,reason!=null?Mode.keyword:requested,reason,ranked.size(),ranked);
        });
    }
    public SearchRepository.IndexStatus status(Jwt jwt,UUID org,UUID document,UUID revision) {
        return read.execute(t->{var identity=authorization.requireAccess(jwt,org,false);documents.revision(org,document,revision,identity);return repository.status(org,document,revision,identity);});
    }
    public enum Mode {keyword,semantic,hybrid}
    public record Response(Mode requestedMode,Mode effectiveMode,String degradationCode,int returnedCount,List<ReciprocalRankFusion.Ranked> results) {}
}
