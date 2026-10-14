package dev.traceback.mcp;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import com.github.dockerjava.api.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Real, isolated Keycloak. Passwords/secrets exist only in this disposable fixture. */
final class McpKeycloak {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PASSWORD=UUID.randomUUID().toString();
    static final String SECRET=UUID.randomUUID().toString();
    static final GenericContainer<?> CONTAINER=create();
    private static GenericContainer<?> create() {
        try {
            var realm=JSON.readTree(Files.readString(Path.of("../dev/keycloak/m14-test-realm.json")));
            var root=(tools.jackson.databind.node.ObjectNode)realm;
            var clients=(tools.jackson.databind.node.ArrayNode)root.get("clients");
            for(var client:clients) if(client.path("serviceAccountsEnabled").asBoolean())
                ((tools.jackson.databind.node.ObjectNode)client).put("secret",SECRET);
            var shortClient=clients.get(1).deepCopy();
            ((tools.jackson.databind.node.ObjectNode)shortClient).put("clientId","traceback-mcp-short");
            ((tools.jackson.databind.node.ObjectNode)shortClient.get("attributes")).put("access.token.lifespan","1");
            clients.add(shortClient);
            var users=root.putArray("users");
            for(String name:List.of("alice","bob")) {
                var user=users.addObject().put("username",name).put("enabled",true).put("emailVerified",true)
                        .put("email",name+"@example.invalid").put("firstName","Synthetic").put("lastName",name);
                user.putArray("credentials").addObject().put("type","password").put("value",PASSWORD).put("temporary",false);
            }
            return new GenericContainer<>("quay.io/keycloak/keycloak:26.8.0").withExposedPorts(8080)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(8080))))
                    .withCommand("start-dev","--features=resource-indicators","--import-realm")
                    .withCopyToContainer(Transferable.of(JSON.writeValueAsBytes(root),0444),"/opt/keycloak/data/import/m14.json")
                    .waitingFor(Wait.forHttp("/realms/traceback-m14-test/.well-known/openid-configuration").forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(90)));
        } catch(Exception ex) {throw new IllegalStateException("Cannot prepare isolated Keycloak fixture");}
    }
    static String issuer() {return "http://127.0.0.1:"+CONTAINER.getMappedPort(8080)+"/realms/traceback-m14-test";}
    static String token(String user,String client,String resource) throws Exception {
        var cookies=new CookieManager();cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        var http=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        String verifier=UUID.randomUUID().toString()+UUID.randomUUID();
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state=UUID.randomUUID().toString();
        var params=new LinkedHashMap<String,String>(Map.of("client_id",client,"response_type","code","redirect_uri","http://127.0.0.1:8765/callback",
                "scope","openid","code_challenge_method","S256","code_challenge",challenge,"state",state));
        if(resource!=null)params.put("resource",resource);
        var login=http.send(HttpRequest.newBuilder(URI.create(issuer()+"/protocol/openid-connect/auth?"+form(params))).GET().build(),HttpResponse.BodyHandlers.ofString());
        var matcher=Pattern.compile("action=\"([^\"]+)\"").matcher(login.body());
        if(login.statusCode()!=200 || !matcher.find())throw new IllegalStateException("PKCE login form unavailable");
        URI action=URI.create(matcher.group(1).replace("&amp;","&"));
        if(!action.getHost().equals("127.0.0.1") || action.getPort()!=CONTAINER.getMappedPort(8080))throw new IllegalStateException("Unsafe login form");
        // Browsers treat HTTP loopback as a trustworthy context. Java's CookieManager
        // withholds Keycloak's Secure auth cookies there. Emulate that browser behavior
        // only for the exact loopback issuer/form origin validated above; never redirect.
        String localCookies=cookies.getCookieStore().getCookies().stream().filter(c -> !c.hasExpired())
                .map(c -> c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
        var authenticated=http.send(HttpRequest.newBuilder(action).header("Content-Type","application/x-www-form-urlencoded")
                .header("Cookie",localCookies)
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username",user,"password",PASSWORD)))).build(),HttpResponse.BodyHandlers.discarding());
        if(authenticated.statusCode()!=302 && authenticated.statusCode()!=303)
            throw new IllegalStateException("PKCE synthetic login rejected (HTTP "+authenticated.statusCode()+")");
        URI callback=URI.create(authenticated.headers().firstValue("Location").orElseThrow());
        var values=query(callback.getRawQuery());
        if(!callback.getHost().equals("127.0.0.1") || callback.getPort()!=8765 || !state.equals(values.get("state"))
                || !issuer().equals(values.get("iss")) || !values.containsKey("code"))throw new IllegalStateException("PKCE response rejected");
        var exchange=new LinkedHashMap<>(Map.of("grant_type","authorization_code","client_id",client,"code",values.get("code"),
                "redirect_uri","http://127.0.0.1:8765/callback","code_verifier",verifier));
        if(resource!=null)exchange.put("resource",resource);
        var response=http.send(HttpRequest.newBuilder(URI.create(issuer()+"/protocol/openid-connect/token")).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(exchange))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("PKCE token exchange rejected");
        return JSON.readTree(response.body()).get("access_token").asString();
    }
    static String machine() throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(issuer()+"/protocol/openid-connect/token"))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type","client_credentials","client_id","traceback-mcp-machine","client_secret",SECRET,
                        "resource","http://127.0.0.1:18084/mcp")))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Machine token unavailable");
        return JSON.readTree(response.body()).get("access_token").asString();
    }
    static String form(Map<String,String> values) {return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    static Map<String,String> query(String query) {
        var result=new HashMap<String,String>();for(String item:query.split("&")) {var part=item.split("=",2);
            String key=URLDecoder.decode(part[0],StandardCharsets.UTF_8),value=URLDecoder.decode(part[1],StandardCharsets.UTF_8);
            if(result.putIfAbsent(key,value)!=null)throw new IllegalStateException("Duplicate PKCE response parameter");}
        return result;
    }
}
