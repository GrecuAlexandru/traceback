package dev.traceback.rag;

import dev.traceback.organization.OrganizationAuthorizationService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ConversationService {
    private final ConversationRepository repository;
    private final OrganizationAuthorizationService authorization;
    private final TransactionTemplate write,read;
    public ConversationService(ConversationRepository repository,OrganizationAuthorizationService authorization,PlatformTransactionManager manager) {
        this.repository=repository;this.authorization=authorization;
        write=new TransactionTemplate(manager);write.setTimeout(5);write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read=new TransactionTemplate(manager);read.setTimeout(5);read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public ConversationRepository.Conversation create(Jwt jwt,UUID org,String title) {
        validTitle(title);return write.execute(t->repository.create(org,authorization.requireAccess(jwt,org,false),title.strip()));
    }
    public Page list(Jwt jwt,UUID org,int page,int size) {
        pagination(page,size);return read.execute(t->{var who=authorization.requireAccess(jwt,org,false);
            return new Page(repository.list(org,who,page,size),repository.count(org,who),page,size);});
    }
    public ConversationRepository.Conversation find(Jwt jwt,UUID org,UUID id) {
        return read.execute(t->repository.find(org,id,authorization.requireAccess(jwt,org,false),false));
    }
    public ConversationRepository.Conversation rename(Jwt jwt,UUID org,UUID id,String title) {
        validTitle(title);return write.execute(t->repository.rename(org,id,authorization.requireAccess(jwt,org,false),title.strip()));
    }
    public void delete(Jwt jwt,UUID org,UUID id) {
        write.executeWithoutResult(t->repository.delete(org,id,authorization.requireAccess(jwt,org,false)));
    }
    static void validTitle(String title) {
        if(title==null || title.isBlank() || title.length()>120 || title.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid title");
    }
    static void pagination(int page,int size) {if(page<0 || page>10000 || size<1 || size>100)throw new IllegalArgumentException("Invalid pagination");}
    public record Page(List<ConversationRepository.Conversation> items,long totalElements,int page,int size) {}
}
