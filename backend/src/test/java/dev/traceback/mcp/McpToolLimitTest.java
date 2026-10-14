package dev.traceback.mcp;

import dev.traceback.incident.*;
import dev.traceback.incident.dto.IncidentResponse;
import dev.traceback.knowledge.DocumentService;
import dev.traceback.organization.OrganizationService;
import dev.traceback.search.*;
import dev.traceback.security.HumanIdentityPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolLimitTest {
    @Test void completeSerializedResultLimitIncludesTextFallbackAndLeaksNoPartialContent() {
        var incidents=mock(IncidentService.class);UUID org=UUID.randomUUID(),id=UUID.randomUUID();
        var jwt=Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://issuer.invalid").subject("alice").claim("traceback_principal_type","human").build();
        when(incidents.getIncident(jwt,org,id)).thenReturn(new IncidentResponse(id,org,"Private title","PRIVATE_SENTINEL".repeat(300),IncidentStatus.OPEN,0,Instant.now()));
        var settings=new McpSettings(true,"/mcp","http://127.0.0.1:8080/mcp","http://127.0.0.1:8080/mcp",List.of("127.0.0.1"),List.of(),16384,4096,8,4,120,10);
        var registry=new McpTools(mock(OrganizationService.class),incidents,mock(DocumentService.class),mock(SearchService.class),mock(SourceService.class),new HumanIdentityPolicy(),settings,new SimpleMeterRegistry());
        var result=registry.specifications().get(5).callHandler().apply(McpTransportContext.create(Map.of(McpConfiguration.CALLER,jwt)),
                new McpSchema.CallToolRequest("traceback_get_incident",Map.of("organizationId",org.toString(),"incidentId",id.toString()))).block();
        assertTrue(result.isError());assertEquals(Map.of("error","RESULT_LIMIT"),result.structuredContent());
        assertFalse(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result).contains("PRIVATE_SENTINEL"));
    }
}
