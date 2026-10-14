package dev.traceback.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/conversations")
public class ConversationController {
    private final ConversationService conversations;
    private final ChatService chat;
    public ConversationController(ConversationService conversations,ChatService chat) {this.conversations=conversations;this.chat=chat;}
    public record Title(@NotBlank @Size(max=120) String title) {}
    public record Question(@NotNull UUID idempotencyKey,@NotBlank @Size(max=4000) String content) {}
    @PostMapping public ResponseEntity<ConversationRepository.Conversation> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@Valid @RequestBody Title request) {
        var c=conversations.create(jwt,organizationId,request.title());return ResponseEntity.created(URI.create("/api/v1/organizations/"+organizationId+"/conversations/"+c.id())).cacheControl(CacheControl.noStore()).body(c);
    }
    @GetMapping public ResponseEntity<ConversationService.Page> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return noStore(conversations.list(jwt,organizationId,page,size));
    }
    @GetMapping("/{id}") public ResponseEntity<ChatService.HistoryPage> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return noStore(chat.history(jwt,organizationId,id,page,size));
    }
    @GetMapping("/{id}/messages/{messageId}") public ResponseEntity<ChatService.MessageView> message(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@PathVariable UUID id,@PathVariable UUID messageId) {
        return noStore(chat.message(jwt,organizationId,id,messageId));
    }
    @GetMapping("/{id}/messages/{messageId}/citations/{reference}") public ResponseEntity<ChatService.Citation> citation(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@PathVariable UUID id,@PathVariable UUID messageId,@PathVariable String reference) {
        return noStore(chat.resolveCitation(jwt,organizationId,id,messageId,reference));
    }
    @PatchMapping("/{id}") public ResponseEntity<ConversationRepository.Conversation> rename(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@PathVariable UUID id,@Valid @RequestBody Title request) {
        return noStore(conversations.rename(jwt,organizationId,id,request.title()));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@PathVariable UUID id) {
        conversations.delete(jwt,organizationId,id);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/{id}/messages") public ResponseEntity<ChatService.TurnResponse> message(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,@PathVariable UUID id,@Valid @RequestBody Question request) {
        var response=chat.submit(jwt,organizationId,id,request.idempotencyKey(),request.content());
        return ResponseEntity.status(response.status().equals("GENERATING")?HttpStatus.ACCEPTED:HttpStatus.OK).cacheControl(CacheControl.noStore()).body(response);
    }
    private <T> ResponseEntity<T> noStore(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
