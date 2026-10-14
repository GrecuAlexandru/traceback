package dev.traceback.rag;

import dev.traceback.knowledge.DocumentUploadValidator;
import dev.traceback.organization.OrganizationAuthorizationService;
import dev.traceback.search.*;
import dev.traceback.security.HumanIdentityPolicy.Identity;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class ChatService {
    private final ConversationRepository conversations;
    private final ChatTurnRepository turns;
    private final OrganizationAuthorizationService authorization;
    private final SearchService search;
    private final SearchRepository sources;
    private final ChatProvider provider;
    private final ChatSettings settings;
    private final double minSimilarity;
    private final RagContext context;
    private final MeterRegistry metrics;
    private final TransactionTemplate write,read;
    private final Semaphore capacity;
    private final ExecutorService inference;
    private final JsonMapper json=JsonMapper.builder().build();
    public ChatService(ConversationRepository conversations,ChatTurnRepository turns,OrganizationAuthorizationService authorization,
                       SearchService search,SearchRepository sources,ChatProvider provider,ChatSettings settings,RagContext context,
                       MeterRegistry metrics,PlatformTransactionManager manager,
                       @org.springframework.beans.factory.annotation.Value("${traceback.chat.min-semantic-similarity:0.5}") double minSimilarity) {
        this.conversations=conversations;this.turns=turns;this.authorization=authorization;this.search=search;this.sources=sources;
        this.provider=provider;this.settings=settings;this.context=context;this.metrics=metrics;
        if(!Double.isFinite(minSimilarity) || minSimilarity<0 || minSimilarity>1)throw new IllegalArgumentException("Invalid relevance threshold");
        this.minSimilarity=minSimilarity;
        write=new TransactionTemplate(manager);write.setTimeout(5);write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read=new TransactionTemplate(manager);read.setTimeout(5);read.setReadOnly(true);read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        capacity=new Semaphore(settings.concurrent());inference=Executors.newFixedThreadPool(settings.concurrent(),Thread.ofPlatform().daemon(true).name("chat-inference-",0).factory());
        metrics.gauge("traceback.chat.active",capacity,s->settings.concurrent()-s.availablePermits());
    }
    @PreDestroy void close() {inference.shutdownNow();}
    public HistoryPage history(Jwt jwt,UUID org,UUID conversation,int page,int size) {
        ConversationService.pagination(page,size);
        return read.execute(t->{var who=authorization.requireAccess(jwt,org,false);var c=conversations.find(org,conversation,who,false);
            var rows=turns.messages(org,conversation,who,page,size,false);
            return new HistoryPage(c,rows.stream().map(m->visible(org,conversation,who,m)).toList(),turns.messageCount(org,conversation,who),page,size);});
    }
    public MessageView message(Jwt jwt,UUID org,UUID conversation,UUID id) {
        return read.execute(t->{var who=authorization.requireAccess(jwt,org,false);conversations.find(org,conversation,who,false);
            return visible(org,conversation,who,turns.message(org,conversation,who,id));});
    }
    public Citation resolveCitation(Jwt jwt,UUID org,UUID conversation,UUID message,String reference) {
        return message(jwt,org,conversation,message).citations().stream().filter(c->c.reference().equals(reference))
            .findFirst().orElseThrow(ChatFailure::missing);
    }
    private MessageView visible(UUID org,UUID conversation,Identity who,ChatTurnRepository.Message message) {
        var citations=new ArrayList<Citation>();var dependencies=new ArrayList<RagContext.Evidence>();
        String status=message.status(),content=message.content();
        if(message.role().equals("ASSISTANT") && Set.of("ANSWERED","INSUFFICIENT_EVIDENCE").contains(status)) {
            var refs=turns.sources(org,conversation,who,message.id());
            boolean valid=!status.equals("ANSWERED") || !refs.isEmpty();
            for(var ref:refs) {
                var source=sources.resolve(org,who,ref.document(),ref.revision(),ref.generation(),ref.chunk(),ref.configuration());
                if(source.isEmpty()) {valid=false;break;}
                var evidence=new RagContext.Evidence(ref.reference(),source.get(),ref.cited());dependencies.add(evidence);
                if(ref.cited())citations.add(citation(evidence));
            }
            if(!valid) {status="REDACTED";content="This answer is unavailable because its source evidence is no longer current or accessible.";citations.clear();dependencies.clear();}
        }
        if(message.role().equals("ASSISTANT") && Set.of("GENERATING","FAILED").contains(status)) content=null;
        citations.sort(Comparator.comparingInt(c->Integer.parseInt(c.reference().substring(1))));
        return new MessageView(message.id(),message.turnId(),message.sequence(),message.role(),content,status,message.errorCode(),
            List.copyOf(citations),message.createdAt(),message.completedAt(),List.copyOf(dependencies));
    }
    private record Reservation(ChatTurnRepository.Turn turn,boolean fresh) {}
    public TurnResponse submit(Jwt jwt,UUID org,UUID conversation,UUID key,String question) {
        validateQuestion(question);if(key==null)throw new IllegalArgumentException("Idempotency key required");
        String normalized=question.strip(),hash=DocumentUploadValidator.checksum(normalized.getBytes(StandardCharsets.UTF_8));
        // Authorization precedes admission; completed idempotent replays do not consume inference capacity.
        var acquired=new AtomicBoolean();
        Reservation reservation;
        try {reservation=write.execute(t->{var who=authorization.requireAccess(jwt,org,false);conversations.find(org,conversation,who,true);
            var existing=turns.existing(org,conversation,who,key);
            if(existing.isPresent()) {
                if(!existing.get().hash().equals(hash))throw ChatFailure.conflict("IDEMPOTENCY_CONFLICT");
                return new Reservation(existing.get(),false);
            }
            if(!capacity.tryAcquire()) {metrics.counter("traceback.chat.admission.rejected").increment();throw new ChatFailure("GENERATION_CAPACITY",HttpStatus.TOO_MANY_REQUESTS);}
            acquired.set(true);
            try {return new Reservation(turns.reserve(org,conversation,who,key,hash,normalized,settings.timeoutSeconds()+45),true);}
            catch(RuntimeException ex) {acquired.set(false);capacity.release();throw ex;}
        });} catch(RuntimeException ex) {if(acquired.get())capacity.release();throw ex;}
        if(!reservation.fresh())return response(jwt,org,conversation,reservation.turn());
        var turn=reservation.turn();boolean transferred=false;
        try {
            var prior=read.execute(t->{var who=authorization.requireAccess(jwt,org,false);conversations.find(org,conversation,who,false);
                return turns.messages(org,conversation,who,0,settings.historyMessages()+2,true).stream()
                    .filter(m->!m.turnId().equals(turn.id())).map(m->visible(org,conversation,who,m)).toList();});
            var excluded=new HashSet<UUID>();for(var item:prior)if(item.role().equals("ASSISTANT") && !Set.of("ANSWERED","INSUFFICIENT_EVIDENCE").contains(item.status()))excluded.add(item.turnId());
            var admitted=new ArrayList<MessageView>();var inherited=new LinkedHashMap<String,RagContext.Evidence>();
            for(int i=prior.size()-1;i>=0;i--) {
                var message=prior.get(i);if(excluded.contains(message.turnId()) || message.content()==null || !Set.of("ANSWERED","INSUFFICIENT_EVIDENCE").contains(message.status()))continue;
                if(inherited.size()+message.dependencies().size()>64)continue;
                admitted.addFirst(message);for(var e:message.dependencies())inherited.putIfAbsent(e.source().key(),e);
            }
            var history=admitted.stream().map(m->new RagContext.History(m.role(),m.content())).toList();
            long retrievalStart=System.nanoTime();var retrieved=search.search(jwt,org,context.query(normalized,history),settings.retrievalChunks(),SearchService.Mode.hybrid);
            metrics.timer("traceback.chat.retrieval").record(System.nanoTime()-retrievalStart,TimeUnit.NANOSECONDS);
            // Pure semantic candidates require a minimum cosine similarity; lexical hits remain eligible.
            var candidates=retrieved.results().stream().filter(r->r.keywordRank()!=null || r.source().score()>=minSimilarity).map(ReciprocalRankFusion.Ranked::source).toList();
            var prepared=context.prepare(normalized,history,candidates);
            metrics.summary("traceback.chat.context.bytes").record(prepared.bytes());metrics.summary("traceback.chat.evidence.count").record(prepared.evidence().size());
            ChatProvider.Result generated=null;RagContext.Answer answer;
            if(prepared.evidence().isEmpty())answer=new RagContext.Answer("INSUFFICIENT_EVIDENCE",RagContext.ABSTENTION,Set.of());
            else {
                // Recheck sources and inherited context immediately before transmitting to local inference.
                var dependencies=new ArrayList<>(prepared.evidence());dependencies.addAll(inherited.values());
                write.executeWithoutResult(t->{var who=authorization.requireAccess(jwt,org,false);conversations.find(org,conversation,who,false);checkSources(org,who,dependencies);});
                var taskState=new AtomicInteger(); // 0 queued, 1 running, 2 cancelled before execution
                Future<ChatProvider.Result> future=inference.submit(()->{
                    if(!taskState.compareAndSet(0,1))throw ChatFailure.provider("GENERATION_INTERRUPTED");
                    long start=System.nanoTime();
                    try {return provider.generate(RagContext.SYSTEM,prepared.data());}
                    finally {metrics.timer("traceback.chat.model").record(System.nanoTime()-start,TimeUnit.NANOSECONDS);capacity.release();}
                });
                transferred=true;
                try {generated=future.get(settings.timeoutSeconds(),TimeUnit.SECONDS);}
                catch(TimeoutException ex) {if(taskState.compareAndSet(0,2))capacity.release();future.cancel(true);throw ChatFailure.provider("MODEL_TIMEOUT");}
                catch(InterruptedException ex) {if(taskState.compareAndSet(0,2))capacity.release();future.cancel(true);Thread.currentThread().interrupt();throw ChatFailure.provider("GENERATION_INTERRUPTED");}
                catch(ExecutionException ex) {if(ex.getCause() instanceof ChatFailure failure)throw failure;throw ChatFailure.provider("MODEL_UNAVAILABLE");}
                answer=context.validate(generated.content(),prepared.evidence());
            }
            var evidence=new ArrayList<RagContext.Evidence>();
            for(var e:prepared.evidence())evidence.add(new RagContext.Evidence(e.reference(),e.source(),answer.references().contains(e.reference())));
            int n=1;for(var e:inherited.values())if(evidence.stream().noneMatch(v->v.source().key().equals(e.source().key())))evidence.add(new RagContext.Evidence("H"+(n++),e.source(),false));
            var metadata=new LinkedHashMap<String,Object>();metadata.put("protocol","rag-json-v2");metadata.put("provider","OLLAMA_LOCAL");metadata.put("model",settings.model());metadata.put("digest",settings.digest());
            metadata.put("batchSize",settings.batchSize());metadata.put("threads",settings.threads());metadata.put("temperature",settings.temperature());metadata.put("contextTokens",settings.contextTokens());metadata.put("outputTokens",settings.outputTokens());metadata.put("timeoutSeconds",settings.timeoutSeconds());
            metadata.put("retrievalMode",retrieved.effectiveMode().name());metadata.put("retrievalDegradation",retrieved.degradationCode());metadata.put("retrievalLimit",settings.retrievalChunks());metadata.put("minSemanticSimilarity",minSimilarity);
            metadata.put("contextBytes",prepared.bytes());metadata.put("evidenceCount",prepared.evidence().size());metadata.put("historyMessages",prepared.history().size());metadata.put("modelCalled",generated!=null);
            if(generated!=null) {metadata.put("inputTokens",generated.inputTokens());metadata.put("outputTokensUsed",generated.outputTokens());
                if(generated.inputTokens()!=null && generated.inputTokens()>=0)metrics.summary("traceback.chat.tokens.input").record(generated.inputTokens());
                if(generated.outputTokens()!=null && generated.outputTokens()>=0)metrics.summary("traceback.chat.tokens.output").record(generated.outputTokens());}
            var finalAnswer=answer;
            write.executeWithoutResult(t->{var who=authorization.requireAccess(jwt,org,false);conversations.find(org,conversation,who,true);
                checkSources(org,who,evidence);turns.complete(org,conversation,who,turn,finalAnswer,evidence,json.writeValueAsString(metadata));});
            metrics.counter("traceback.chat.generations","outcome",answer.status()).increment();
            return response(jwt,org,conversation,turn);
        } catch(RuntimeException ex) {
            String code=ex instanceof ChatFailure failure?failure.code():"GENERATION_FAILED";
            metrics.counter("traceback.chat.generations","outcome","FAILED").increment();
            if(code.equals("INVALID_CITATION") || code.equals("MISSING_CITATION"))metrics.counter("traceback.chat.citations.invalid").increment();
            if(code.equals("MODEL_TIMEOUT"))metrics.counter("traceback.chat.timeouts").increment();
            if(code.equals("MODEL_UNAVAILABLE"))metrics.counter("traceback.chat.provider.failures").increment();
            try {write.executeWithoutResult(t->turns.fail(turn,code));}catch(RuntimeException ignored) {metrics.counter("traceback.chat.recovery.required").increment();}
            throw ex;
        } finally {if(!transferred)capacity.release();}
    }
    private void checkSources(UUID org,Identity who,List<RagContext.Evidence> evidence) {
        for(var e:evidence) {var s=e.source();if(sources.resolve(org,who,s.documentId(),s.revisionId(),s.generationId(),s.chunkId(),s.configuration()).isEmpty())throw ChatFailure.conflict("SOURCE_CHANGED");}
    }
    private TurnResponse response(Jwt jwt,UUID org,UUID conversation,ChatTurnRepository.Turn turn) {
        return read.execute(t->{var who=authorization.requireAccess(jwt,org,false);conversations.find(org,conversation,who,false);
            var current=turns.find(org,conversation,who,turn.id());
            // Resolve by scoped message identity, rather than scanning an unbounded conversation.
            var message=turns.message(org,conversation,who,current.assistantMessageId());var view=visible(org,conversation,who,message);
            return new TurnResponse(conversation,current.userMessageId(),current.assistantMessageId(),view.content(),view.citations(),view.status(),view.errorCode(),turn.createdAt(),view.completedAt());});
    }
    private void validateQuestion(String question) {
        if(question==null || question.isBlank() || RagContext.bytes(question)>settings.maxQuestionBytes()
            || !question.codePoints().anyMatch(Character::isLetterOrDigit)
            || question.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t'))throw new IllegalArgumentException("Invalid question");
    }
    private Citation citation(RagContext.Evidence e) {var s=e.source();return new Citation(e.reference(),s.documentId(),s.revisionId(),s.chunkId(),s.generationId(),s.title(),s.section(),s.pageStart(),s.pageEnd(),s.excerpt());}
    public record Citation(String reference,UUID documentId,UUID revisionId,UUID chunkId,UUID generationId,String title,String section,Integer pageStart,Integer pageEnd,String excerpt) {}
    public record MessageView(UUID id,UUID turnId,long sequence,String role,String content,String status,String errorCode,List<Citation> citations,Instant createdAt,Instant completedAt,
                              @com.fasterxml.jackson.annotation.JsonIgnore List<RagContext.Evidence> dependencies) {}
    public record HistoryPage(ConversationRepository.Conversation conversation,List<MessageView> messages,long totalElements,int page,int size) {}
    public record TurnResponse(UUID conversationId,UUID userMessageId,UUID assistantMessageId,String answer,List<Citation> citations,String status,String errorCode,Instant createdAt,Instant completedAt) {}
}
